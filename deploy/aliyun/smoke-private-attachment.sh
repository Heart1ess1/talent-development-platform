#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
OSSUTIL="${OSSUTIL:-/usr/local/bin/ossutil}"
API_BASE="${API_BASE:-http://127.0.0.1/api/v1}"
cd "$APP_DIR"

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${JWT_SECRET:?missing JWT_SECRET}"
: "${OSS_ENDPOINT:?missing OSS_ENDPOINT}"
: "${OSS_PUBLIC_ENDPOINT:?missing OSS_PUBLIC_ENDPOINT}"
: "${OSS_PRIVATE_BUCKET:?missing OSS_PRIVATE_BUCKET}"
OSS_REGION="${OSS_REGION:-cn-shanghai}"

json_field() {
  python3 -c '
import json
import sys

value = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    value = value[part]
print(value)
' "$1"
}

row="$(docker compose exec -T db sh -lc \
  'mysql -B -N -uroot -p"$MYSQL_ROOT_PASSWORD" talent_platform -e "select id,username,display_name,role,must_change_password,security_version from sys_user where username=\"superadmin\""' \
  | tr -d '\r')"
IFS=$'\t' read -r uid username display_name role must_change security_version <<< "$row"

token="$(python3 - "$JWT_SECRET" "$uid" "$username" "$display_name" "$role" "$must_change" "$security_version" <<'PY'
import base64
import hashlib
import hmac
import json
import sys
import time

secret, uid, username, name, role, must_change, security_version = sys.argv[1:]
key = secret.encode()
if len(key) >= 64:
    algorithm, digest = "HS512", hashlib.sha512
elif len(key) >= 48:
    algorithm, digest = "HS384", hashlib.sha384
else:
    algorithm, digest = "HS256", hashlib.sha256

def encode(value):
    raw = json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode()
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

now = int(time.time())
header = encode({"alg": algorithm, "typ": "JWT"})
payload = encode({
    "sub": username,
    "uid": int(uid),
    "name": name,
    "role": role,
    "mustChange": must_change.lower() in ("1", "true"),
    "sv": int(security_version),
    "iat": now,
    "exp": now + 600,
})
body = f"{header}.{payload}"
signature = base64.urlsafe_b64encode(hmac.new(key, body.encode(), digest).digest()).rstrip(b"=").decode()
print(f"{body}.{signature}")
PY
)"

auth=(-H "Authorization: Bearer $token")
stamp="$(date -u +%Y%m%d%H%M%S)"
source_file="$(mktemp /tmp/talent-attachment-smoke-XXXXXX.txt)"
download_file="$(mktemp /tmp/talent-attachment-download-XXXXXX.txt)"
headers_file="$(mktemp /tmp/talent-attachment-headers-XXXXXX.txt)"
task_id=""
ticket_id=""
attachment_id=""
storage_key=""
step="initialize"
trap 'status=$?; echo "FAIL_STEP=$step STATUS=$status" >&2' ERR

object_exists() {
  [[ -n "$storage_key" ]] || return 1
  "$OSSUTIL" ls "oss://$OSS_PRIVATE_BUCKET/$storage_key" \
    --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole 2>/dev/null \
    | grep -q 'Object Number is: 1'
}

cleanup() {
  set +e
  if [[ -n "$attachment_id" && -n "$task_id" ]]; then
    curl -fsS -X DELETE "${auth[@]}" \
      "$API_BASE/tasks/$task_id/attachments/$attachment_id" >/dev/null 2>&1
  fi
  if object_exists; then
    "$OSSUTIL" rm "oss://$OSS_PRIVATE_BUCKET/$storage_key" -f \
      --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole >/dev/null 2>&1
  fi
  if [[ -n "$ticket_id" ]]; then
    docker compose exec -T db sh -lc \
      "mysql -N -uroot -p\"\$MYSQL_ROOT_PASSWORD\" talent_platform -e \"delete from object_upload_ticket where id='$ticket_id'\"" \
      >/dev/null 2>&1
  fi
  if [[ -n "$task_id" ]]; then
    curl -fsS -X DELETE "${auth[@]}" "$API_BASE/tasks/$task_id" >/dev/null 2>&1
  fi
  rm -f "$source_file" "$download_file" "$headers_file"
}
trap cleanup EXIT

printf 'private attachment signed transfer %s\n' "$stamp" > "$source_file"
deadline="$(date -d '+1 day' '+%Y-%m-%dT%H:%M:%S')"
task_payload="$(printf '{"title":"__OSS_ATTACHMENT_SMOKE_%s","description":"temporary production verification","requirements":"delete automatically","deadline":"%s"}' "$stamp" "$deadline")"
step="create_task"
echo "CHECK:create_task"
task_response="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  --data "$task_payload" "$API_BASE/tasks")"
task_id="$(printf '%s' "$task_response" | json_field data)"

size="$(wc -c < "$source_file" | tr -d ' ')"
ticket_payload="$(printf '{"originalName":"smoke.txt","contentType":"text/plain","size":%s}' "$size")"
step="create_upload_ticket"
echo "CHECK:create_upload_ticket"
ticket_response="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  --data "$ticket_payload" "$API_BASE/tasks/$task_id/attachments/upload-ticket")"
ticket_id="$(printf '%s' "$ticket_response" | json_field data.ticketId)"
upload_url="$(printf '%s' "$ticket_response" | json_field data.uploadUrl)"
test "$(printf '%s' "$ticket_response" | json_field data.method)" = "PUT"
storage_key="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.unquote(urllib.parse.urlparse(sys.argv[1]).path.lstrip("/")))' "$upload_url")"

step="put_signed_upload"
echo "CHECK:put_signed_upload"
curl -fsS -X PUT -H 'Content-Type: text/plain' --data-binary "@$source_file" "$upload_url" >/dev/null
object_exists

step="complete_upload"
echo "CHECK:complete_upload"
complete_response="$(curl -fsS -X POST "${auth[@]}" \
  "$API_BASE/tasks/$task_id/attachments/upload-complete/$ticket_id")"
attachment_id="$(printf '%s' "$complete_response" | json_field data)"

step="request_signed_download"
echo "CHECK:request_signed_download"
download_status="$(curl -sS -D "$headers_file" -o /dev/null -w '%{http_code}' "${auth[@]}" \
  "$API_BASE/task-attachments/$attachment_id")"
test "$download_status" = "302"
grep -qi '^cache-control: no-store' "$headers_file"
signed_url="$(sed -n 's/^[Ll]ocation: //p' "$headers_file" | tr -d '\r')"
test -n "$signed_url"
python3 - "$signed_url" "$OSS_PUBLIC_ENDPOINT" <<'PY'
import sys
import urllib.parse

signed = urllib.parse.urlparse(sys.argv[1])
public = urllib.parse.urlparse(sys.argv[2])
if signed.scheme != "https":
    raise SystemExit("signed download is not HTTPS")
if not signed.hostname or not public.hostname or not signed.hostname.endswith(public.hostname):
    raise SystemExit("signed download does not use public OSS endpoint")
if "Expires" not in urllib.parse.parse_qs(signed.query):
    raise SystemExit("signed download has no expiration")
PY

step="download_signed_object"
echo "CHECK:download_signed_object"
signed_download_status="$(curl -sS "$signed_url" -o "$download_file" -w '%{http_code}')"
if [[ "$signed_download_status" != "200" ]]; then
  python3 - "$download_file" "$signed_download_status" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, status = sys.argv[1:]
try:
    root = ET.parse(path).getroot()
    code = root.findtext("Code") or "unknown"
    message = root.findtext("Message") or "unknown"
except Exception:
    code, message = "unknown", "unparseable OSS response"
print(f"SIGNED_DOWNLOAD_FAILED http={status} code={code} message={message}", file=sys.stderr)
PY
  false
fi
test "$(sha256sum "$source_file" | cut -d' ' -f1)" = "$(sha256sum "$download_file" | cut -d' ' -f1)"

step="delete_attachment"
echo "CHECK:delete_attachment"
curl -fsS -X DELETE "${auth[@]}" \
  "$API_BASE/tasks/$task_id/attachments/$attachment_id" >/dev/null
attachment_id=""
if object_exists; then
  echo "FAIL:temporary attachment object was not deleted"
  exit 1
fi
step="delete_task"
echo "CHECK:delete_task"
curl -fsS -X DELETE "${auth[@]}" "$API_BASE/tasks/$task_id" >/dev/null
task_id=""
ticket_id=""
step="complete"

echo "APP_PRIVATE_SIGNED_UPLOAD_DOWNLOAD_DELETE_OK"
