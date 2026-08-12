#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
DATA_ROOT="${DATA_ROOT:-/data/talent-platform}"
PUBLIC_ORIGIN="${PUBLIC_ORIGIN:-http://139.224.51.21}"
cd "$APP_DIR"

if ! mountpoint -q /data; then
  echo "缺少 /data 数据盘挂载；为避免将 MySQL 写回系统盘，停止部署"
  exit 1
fi
install -d -m 755 \
  "$DATA_ROOT/mysql" \
  "$DATA_ROOT/uploads" \
  "$DATA_ROOT/preview-cache" \
  "$DATA_ROOT/backups" \
  "$DATA_ROOT/releases" \
  "$DATA_ROOT/logs"

random_hex() {
  local bytes="$1"
  openssl rand -hex "$bytes"
}

if [[ ! -f talent-platform.jar ]]; then
  echo "缺少 $APP_DIR/talent-platform.jar"
  exit 1
fi

if [[ ! -f .env ]]; then
  umask 077
  db_password="$(random_hex 24)"
  db_root_password="$(random_hex 32)"
  jwt_secret="$(random_hex 48)"
  admin_password="$(random_hex 12)"
  cat > .env <<EOF
DB_PASSWORD=${db_password}
DB_ROOT_PASSWORD=${db_root_password}
JWT_SECRET=${jwt_secret}
SUPER_ADMIN_USERNAME=superadmin
SUPER_ADMIN_PASSWORD=${admin_password}
CORS_ORIGINS=${PUBLIC_ORIGIN}
STORAGE_TYPE=local
OSS_ENDPOINT=
OSS_REGION=cn-shanghai
OSS_PUBLIC_ENDPOINT=
OSS_BUCKET=
OSS_PRIVATE_BUCKET=
OSS_PUBLIC_BUCKET=
CDN_BASE_URL=
OSS_ACCESS_KEY=
OSS_SECRET_KEY=
OSS_RAM_ROLE=
EOF
  chmod 600 .env
fi

docker compose config --quiet
docker compose pull db web
docker compose build --pull app
docker compose up -d
