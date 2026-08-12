#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
OSSUTIL="${OSSUTIL:-/usr/local/bin/ossutil}"
cd "$APP_DIR"

set -a
# shellcheck disable=SC1091
source .env
set +a

OSS_REGION="${OSS_REGION:-cn-shanghai}"

required=(OSS_ENDPOINT OSS_PUBLIC_ENDPOINT OSS_PRIVATE_BUCKET OSS_PUBLIC_BUCKET OSS_RAM_ROLE)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "MISSING:$name"
    exit 1
  fi
done
if [[ "$OSS_PUBLIC_ENDPOINT" == *-internal.* ]]; then
  echo "INVALID:OSS_PUBLIC_ENDPOINT must be public"
  exit 1
fi
if [[ "$STORAGE_TYPE" != "oss" ]]; then
  echo "INVALID:STORAGE_TYPE=$STORAGE_TYPE"
  exit 1
fi
if [[ ! -x "$OSSUTIL" ]]; then
  echo "MISSING:ossutil"
  exit 1
fi

token=$(curl -fsS -X PUT -H 'X-aliyun-ecs-metadata-token-ttl-seconds: 60' \
  http://100.100.100.200/latest/api/token)
role=$(curl -fsS -H "X-aliyun-ecs-metadata-token: $token" \
  http://100.100.100.200/latest/meta-data/ram/security-credentials/)
if [[ "$role" != "$OSS_RAM_ROLE" ]]; then
  echo "INVALID:bound RAM role does not match OSS_RAM_ROLE"
  exit 1
fi

"$OSSUTIL" ls "oss://$OSS_PRIVATE_BUCKET/" \
  --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole >/dev/null
"$OSSUTIL" ls "oss://$OSS_PUBLIC_BUCKET/" \
  --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole >/dev/null

docker compose ps
curl -fsS http://127.0.0.1/actuator/health
printf '\nOSS_SWITCH_READY\n'
