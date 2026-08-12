#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
OSSUTIL="${OSSUTIL:-/usr/local/bin/ossutil}"
cd "$APP_DIR"

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${JWT_SECRET:?missing JWT_SECRET}"
: "${OSS_ENDPOINT:?missing OSS_ENDPOINT}"
: "${OSS_PUBLIC_BUCKET:?missing OSS_PUBLIC_BUCKET}"
OSS_REGION="${OSS_REGION:-cn-shanghai}"

admin_avatar_count() {
  docker compose exec -T db sh -lc \
    'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" talent_platform -e "select count(*) from sys_user where username=\"superadmin\" and avatar_storage_key is not null"' \
    | tr -d '\r'
}

if [[ "$(admin_avatar_count)" != "0" ]]; then
  echo "SKIP:superadmin already has an avatar"
  exit 2
fi

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

png="$(mktemp /tmp/talent-avatar-smoke-XXXXXX.png)"
readback="$(mktemp /tmp/talent-avatar-readback-XXXXXX.png)"
avatar_url=""
storage_key=""

cleanup() {
  if [[ -n "$avatar_url" ]]; then
    curl -fsS -X DELETE -H "Authorization: Bearer $token" \
      http://127.0.0.1/api/v1/profile/avatar >/dev/null 2>&1 || true
  fi
  rm -f "$png" "$readback"
}
trap cleanup EXIT

python3 - "$png" <<'PY'
import binascii
import struct
import sys
import zlib

path = sys.argv[1]
width = height = 120
raw = b"".join(b"\x00" + bytes((31, 111, 235, 255)) * width for _ in range(height))

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

upload="$(curl -fsS -H "Authorization: Bearer $token" \
  -F "file=@$png;type=image/png" http://127.0.0.1/api/v1/profile/avatar)"
avatar_url="$(printf '%s' "$upload" | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["data"]["avatarUrl"])')"
storage_key="$(docker compose exec -T db sh -lc \
  'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" talent_platform -e "select avatar_storage_key from sys_user where username=\"superadmin\""' \
  | tr -d '\r')"
test -n "$storage_key"

"$OSSUTIL" ls "oss://$OSS_PUBLIC_BUCKET/$storage_key" \
  --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole \
  | grep -q 'Object Number is: 1'
curl -fsS "http://127.0.0.1$avatar_url" -o "$readback"
test "$(sha256sum "$png" | cut -d' ' -f1)" = "$(sha256sum "$readback" | cut -d' ' -f1)"

curl -fsS -X DELETE -H "Authorization: Bearer $token" \
  http://127.0.0.1/api/v1/profile/avatar >/dev/null
avatar_url=""
test "$(admin_avatar_count)" = "0"
if "$OSSUTIL" ls "oss://$OSS_PUBLIC_BUCKET/$storage_key" \
  --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole 2>/dev/null \
  | grep -q 'Object Number is: 1'; then
  echo "FAIL:temporary public object was not deleted"
  exit 1
fi

echo "APP_PUBLIC_UPLOAD_READ_DELETE_OK"
