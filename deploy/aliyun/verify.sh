#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
cd "$APP_DIR"

mountpoint -q /data
test "$(findmnt -n -o TARGET -T /data/talent-platform/mysql)" = "/data"

docker compose ps
curl -fsS http://127.0.0.1/actuator/health
printf '\n'

db_container="$(docker compose ps -q db)"
app_container="$(docker compose ps -q app)"
test "$(docker inspect "$db_container" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Source}}{{end}}{{end}}')" = "/data/talent-platform/mysql"
test "$(docker inspect "$app_container" --format '{{range .Mounts}}{{if eq .Destination "/data/uploads"}}{{.Source}}{{end}}{{end}}')" = "/data/talent-platform/uploads"
test "$(docker inspect "$app_container" --format '{{range .Mounts}}{{if eq .Destination "/data/preview-cache"}}{{.Source}}{{end}}{{end}}')" = "/data/talent-platform/preview-cache"

docker compose exec -T db sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" talent_platform -N -e "SELECT COUNT(1) FROM flyway_schema_history; SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1; SELECT COUNT(1) FROM sys_user;"'

if [[ "${VERIFY_BOOTSTRAP_LOGIN:-false}" == "true" ]]; then
  admin_username="$(sed -n 's/^SUPER_ADMIN_USERNAME=//p' .env)"
  admin_password="$(sed -n 's/^SUPER_ADMIN_PASSWORD=//p' .env)"
  login_response="$(curl -fsS -H 'Content-Type: application/json' --data-binary "{\"username\":\"${admin_username}\",\"password\":\"${admin_password}\"}" http://127.0.0.1/api/v1/auth/login)"
  if ! grep -q '"token"' <<<"$login_response"; then
    echo "LOGIN_FAILED"
    exit 1
  fi
  echo "LOGIN_OK"
else
  echo "LOGIN_SKIPPED (set VERIFY_BOOTSTRAP_LOGIN=true only before the initial password change)"
fi
