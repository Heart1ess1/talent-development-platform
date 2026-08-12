#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
BACKUP_DIR="${BACKUP_DIR:-/data/talent-platform/backups/mysql}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash backup-mysql.sh 执行"
  exit 1
fi
if ! mountpoint -q /data; then
  echo "缺少 /data 数据盘挂载，拒绝把备份写入系统盘"
  exit 1
fi

cd "$APP_DIR"
install -d -m 700 "$BACKUP_DIR"
timestamp="$(date +%Y%m%d-%H%M%S)"
final_file="$BACKUP_DIR/talent-platform-$timestamp.sql.gz"
temp_file="$(mktemp "$BACKUP_DIR/.talent-platform-$timestamp.XXXXXX.sql.gz")"

cleanup() {
  rm -f "$temp_file"
}
trap cleanup EXIT

docker compose exec -T db sh -lc \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events --triggers --hex-blob --databases talent_platform' \
  | gzip -9 > "$temp_file"
gzip -t "$temp_file"
chmod 600 "$temp_file"
mv "$temp_file" "$final_file"
sha256sum "$final_file" > "$final_file.sha256"
chmod 600 "$final_file.sha256"

find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'talent-platform-*.sql.gz' -o -name 'talent-platform-*.sql.gz.sha256' \) \
  -mtime "+$RETENTION_DAYS" -delete

trap - EXIT
printf 'BACKUP=%s\n' "$final_file"
cat "$final_file.sha256"
