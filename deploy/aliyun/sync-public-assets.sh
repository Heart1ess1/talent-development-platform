#!/usr/bin/env bash
set -euo pipefail

ASSET_DIR="${1:-/tmp/talent-platform-assets}"
OSSUTIL="${OSSUTIL:-/usr/local/bin/ossutil}"

: "${OSS_PUBLIC_BUCKET:?缺少 OSS_PUBLIC_BUCKET}"
: "${OSS_ENDPOINT:?缺少 OSS_ENDPOINT}"
: "${OSS_RAM_ROLE:?缺少 OSS_RAM_ROLE}"
OSS_REGION="${OSS_REGION:-cn-shanghai}"

if [[ ! -x "$OSSUTIL" ]]; then
  echo "未找到 ossutil：$OSSUTIL"
  exit 1
fi
if [[ ! -d "$ASSET_DIR" ]]; then
  echo "静态资源目录不存在：$ASSET_DIR"
  exit 1
fi

"$OSSUTIL" cp -r "$ASSET_DIR/" "oss://$OSS_PUBLIC_BUCKET/assets/" \
  --endpoint "$OSS_ENDPOINT" \
  --region "$OSS_REGION" \
  --mode EcsRamRole \
  --cache-control "public, max-age=31536000, immutable" \
  --job 8 \
  --update

echo "静态资源已上传到 oss://$OSS_PUBLIC_BUCKET/assets/"
