#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
UPLOAD_ROOT="${UPLOAD_ROOT:-/var/lib/docker/volumes/talent-platform_uploads_data/_data}"
OSSUTIL="${OSSUTIL:-/usr/local/bin/ossutil}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash migrate-local-files.sh 执行"
  exit 1
fi
if [[ ! -x "$OSSUTIL" ]]; then
  echo "未找到 ossutil：$OSSUTIL"
  exit 1
fi
if [[ ! -f "$APP_DIR/.env" ]]; then
  echo "未找到 $APP_DIR/.env"
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$APP_DIR/.env"
set +a

: "${OSS_PRIVATE_BUCKET:?缺少 OSS_PRIVATE_BUCKET}"
: "${OSS_PUBLIC_BUCKET:?缺少 OSS_PUBLIC_BUCKET}"
: "${OSS_ENDPOINT:?缺少 OSS_ENDPOINT}"
: "${OSS_RAM_ROLE:?缺少 OSS_RAM_ROLE}"
OSS_REGION="${OSS_REGION:-cn-shanghai}"

resolved_root="$(readlink -f "$UPLOAD_ROOT")"
case "$resolved_root" in
  /var/lib/docker/volumes/talent-platform_uploads_data/_data) ;;
  *) echo "拒绝迁移未核准目录：$resolved_root"; exit 1 ;;
esac

cd "$APP_DIR"
mkdir -p migration
avatar_list="$APP_DIR/migration/avatar-storage-keys.txt"

docker compose exec -T db sh -lc \
  'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" talent_platform -e "select avatar_storage_key from sys_user where avatar_storage_key is not null and avatar_storage_key<>\"\""' \
  > "$avatar_list"

echo "复制全部历史对象到私有 Bucket（源文件不会删除）"
"$OSSUTIL" cp -r "$resolved_root/" "oss://$OSS_PRIVATE_BUCKET/" \
  --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole --update

echo "复制头像对象到公共 Bucket（源文件不会删除）"
while IFS= read -r key; do
  [[ -z "$key" ]] && continue
  source_path="$(readlink -f "$resolved_root/$key")"
  case "$source_path" in
    "$resolved_root"/*) ;;
    *) echo "跳过非法头像路径：$key"; continue ;;
  esac
  [[ -f "$source_path" ]] || { echo "头像文件缺失：$key"; continue; }
  "$OSSUTIL" cp "$source_path" "oss://$OSS_PUBLIC_BUCKET/$key" \
    --endpoint "$OSS_ENDPOINT" --region "$OSS_REGION" --mode EcsRamRole --update
done < "$avatar_list"

echo "迁移复制完成。请先执行对象数量/抽样哈希验收，再将 STORAGE_TYPE 切换为 oss。"
