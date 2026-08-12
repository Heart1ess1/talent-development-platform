#!/usr/bin/env bash
set -euo pipefail

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
: "${OSS_PRIVATE_BUCKET:?missing OSS_PRIVATE_BUCKET}"
OSS_REGION="${OSS_REGION:-cn-shanghai}"

json_field() {
  python3 -c '
import json
import sys

value = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    value = value[part]
if isinstance(value, bool):
    print(str(value).lower())
else:
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
png="$(mktemp /tmp/talent-courseware-smoke-XXXXXX.png)"
preview="$(mktemp /tmp/talent-courseware-preview-XXXXXX.png)"
headers_file="$(mktemp /tmp/talent-courseware-headers-XXXXXX.txt)"
deny_body="$(mktemp /tmp/talent-courseware-deny-XXXXXX.json)"
course_id=""
ticket_id=""
material_id=""
session_id=""
storage_key=""

object_exists() {
  [[ -n "$storage_key" ]] || return 1
  "$OSSUTIL" ls "oss://$OSS_PRIVATE_BUCKET/$storage_key" \
    --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole 2>/dev/null \
    | grep -q 'Object Number is: 1'
}

cleanup() {
  set +e
  if [[ -n "$session_id" && -n "$material_id" ]]; then
    curl -fsS -X POST "${auth[@]}" \
      "$API_BASE/course-materials/$material_id/preview-sessions/$session_id/close" >/dev/null 2>&1
  fi
  if [[ -n "$material_id" ]]; then
    curl -fsS -X DELETE "${auth[@]}" "$API_BASE/course-materials/$material_id" >/dev/null 2>&1
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
  if [[ -n "$course_id" ]]; then
    curl -fsS -X DELETE "${auth[@]}" "$API_BASE/courses/$course_id" >/dev/null 2>&1
  fi
  rm -f "$png" "$preview" "$headers_file" "$deny_body"
}
trap cleanup EXIT

python3 - "$png" <<'PY'
import binascii
import struct
import sys
import zlib

path = sys.argv[1]
width = height = 120
raw = b"".join(b"\x00" + bytes((20, 150, 90, 255)) * width for _ in range(height))

def chunk(kind, data):
    checksum = binascii.crc32(kind + data) & 0xffffffff
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)

payload = (
    b"\x89PNG\r\n\x1a\n"
    + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw, 9))
    + chunk(b"IEND", b"")
)
with open(path, "wb") as output:
    output.write(payload)
PY

capabilities="$(curl -fsS "${auth[@]}" "$API_BASE/storage/capabilities")"
test "$(printf '%s' "$capabilities" | json_field data.directUpload)" = "true"
test "$(printf '%s' "$capabilities" | json_field data.signedDownload)" = "true"

course_payload="$(printf '{"name":"__OSS_SMOKE_%s","description":"temporary production verification"}' "$stamp")"
course_response="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  --data "$course_payload" "$API_BASE/courses")"
course_id="$(printf '%s' "$course_response" | json_field data)"
test -n "$course_id"

size="$(wc -c < "$png" | tr -d ' ')"
ticket_payload="$(printf '{"originalName":"smoke.png","contentType":"image/png","size":%s}' "$size")"
ticket_response="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  --data "$ticket_payload" "$API_BASE/courses/$course_id/materials/upload-ticket")"
ticket_id="$(printf '%s' "$ticket_response" | json_field data.ticketId)"
upload_url="$(printf '%s' "$ticket_response" | json_field data.uploadUrl)"
method="$(printf '%s' "$ticket_response" | json_field data.method)"
test "$method" = "PUT"
storage_key="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.unquote(urllib.parse.urlparse(sys.argv[1]).path.lstrip("/")))' "$upload_url")"

curl -fsS -X PUT -H 'Content-Type: image/png' --data-binary "@$png" "$upload_url" >/dev/null
object_exists

complete_response="$(curl -fsS -X POST "${auth[@]}" \
  "$API_BASE/courses/$course_id/materials/upload-complete/$ticket_id")"
material_id="$(printf '%s' "$complete_response" | json_field data)"
test -n "$material_id"

preview_response="$(curl -fsS -X POST "${auth[@]}" \
  "$API_BASE/course-materials/$material_id/preview-sessions")"
session_id="$(printf '%s' "$preview_response" | json_field data.sessionId)"
test "$(printf '%s' "$preview_response" | json_field data.pageCount)" = "1"

curl -fsS -D "$headers_file" "${auth[@]}" \
  "$API_BASE/course-materials/$material_id/preview-sessions/$session_id/pages/1" -o "$preview"
grep -qi '^content-type: image/png' "$headers_file"
grep -qi '^cache-control: no-store' "$headers_file"
grep -qi '^content-disposition: inline' "$headers_file"
python3 - "$preview" <<'PY'
import struct
import sys

content = open(sys.argv[1], "rb").read()
if not content.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit("preview is not PNG")
width, height = struct.unpack(">II", content[16:24])
if (width, height) != (120, 176):
    raise SystemExit(f"watermark footer missing: {(width, height)}")
PY

deny_status="$(curl -sS -o "$deny_body" -w '%{http_code}' "${auth[@]}" \
  "$API_BASE/course-materials/$material_id")"
test "$deny_status" = "403"

curl -fsS -X POST "${auth[@]}" \
  "$API_BASE/course-materials/$material_id/preview-sessions/$session_id/close" >/dev/null
session_id=""
curl -fsS -X DELETE "${auth[@]}" "$API_BASE/course-materials/$material_id" >/dev/null
material_id=""
if object_exists; then
  echo "FAIL:temporary private object was not deleted"
  exit 1
fi
curl -fsS -X DELETE "${auth[@]}" "$API_BASE/courses/$course_id" >/dev/null
course_id=""
ticket_id=""

echo "APP_PRIVATE_DIRECT_UPLOAD_PREVIEW_DELETE_OK"
