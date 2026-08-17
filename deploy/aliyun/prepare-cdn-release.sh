#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
RELEASE_ROOT="${RELEASE_ROOT:-/data/talent-platform/releases}"
INCOMING_JAR="${1:-/tmp/talent-platform.jar}"
ASSET_DIR="${2:-/tmp/talent-platform-assets}"
CANDIDATE_NAME="${3:-cdn-$(date +%Y%m%d-%H%M%S)}"
EXPECTED_CDN="https://static.yryhx.cn"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash prepare-cdn-release.sh <JAR> <静态资源目录> [候选名称] 执行"
  exit 1
fi
if ! mountpoint -q /data; then
  echo "数据盘 /data 未挂载，拒绝在系统盘创建发布候选"
  exit 1
fi
if [[ ! -f "$INCOMING_JAR" ]]; then
  echo "找不到待发布 JAR：$INCOMING_JAR"
  exit 1
fi
if [[ ! -d "$ASSET_DIR" ]]; then
  echo "找不到静态资源目录：$ASSET_DIR"
  exit 1
fi
if [[ -z "$(find "$ASSET_DIR" -type f -print -quit)" ]]; then
  echo "静态资源目录为空：$ASSET_DIR"
  exit 1
fi
if [[ ! "$CANDIDATE_NAME" =~ ^cdn-[A-Za-z0-9._-]+$ ]] || [[ "$CANDIDATE_NAME" == "cdn-ready" ]]; then
  echo "候选名称必须以 cdn- 开头，且只能包含字母、数字、点、下划线和连字符"
  exit 1
fi

resolved_app="$(readlink -f "$APP_DIR")"
resolved_release_root="$(readlink -f "$RELEASE_ROOT")"
case "$resolved_app" in
  /opt/talent-platform) ;;
  *) echo "拒绝操作未核准应用目录：$resolved_app"; exit 1 ;;
esac
case "$resolved_release_root" in
  /data/talent-platform/releases) ;;
  *) echo "拒绝操作未核准发布目录：$resolved_release_root"; exit 1 ;;
esac

index_html="$(unzip -p "$INCOMING_JAR" BOOT-INF/classes/static/index.html)"
asset_url="$(printf '%s' "$index_html" | grep -Eo 'https://static\.yryhx\.cn/assets/[^" ]+\.js' | head -n 1)"
if [[ -z "$asset_url" ]]; then
  echo "待发布 JAR 不是 $EXPECTED_CDN 专用构建"
  exit 1
fi
asset_relative="${asset_url#"$EXPECTED_CDN/assets/"}"
if [[ ! -f "$ASSET_DIR/$asset_relative" ]]; then
  echo "JAR 引用的主资源不在上传目录中：$asset_relative"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$resolved_app/.env"
set +a
: "${OSS_PUBLIC_BUCKET:?缺少 OSS_PUBLIC_BUCKET}"
: "${OSS_ENDPOINT:?缺少 OSS_ENDPOINT}"
: "${OSS_RAM_ROLE:?缺少 OSS_RAM_ROLE}"

bash "$resolved_app/sync-public-assets.sh" "$ASSET_DIR"

staging_dir="$resolved_release_root/staging"
candidate_dir="$staging_dir/$CANDIDATE_NAME"
if [[ -e "$candidate_dir" ]]; then
  echo "候选目录已存在：$candidate_dir"
  exit 1
fi

mkdir -p "$candidate_dir"
install -m 0644 "$INCOMING_JAR" "$candidate_dir/talent-platform.jar"
(
  cd "$candidate_dir"
  sha256sum talent-platform.jar > SHA256SUMS
)
(
  cd "$ASSET_DIR"
  find . -type f -print0 | sort -z | xargs -0 -r sha256sum > "$candidate_dir/ASSET-SHA256SUMS"
)
cat > "$candidate_dir/RELEASE-METADATA" <<EOF
created_at=$(date --iso-8601=seconds)
cdn_base=$EXPECTED_CDN
cdn_probe_asset=$asset_url
public_bucket=$OSS_PUBLIC_BUCKET
EOF

ready_tmp="$staging_dir/.cdn-ready.$$.tmp"
trap 'rm -f "$ready_tmp"' EXIT
ln -s "$candidate_dir" "$ready_tmp"
mv -Tf "$ready_tmp" "$staging_dir/cdn-ready"
trap - EXIT

echo "CDN_RELEASE_PREPARED=$candidate_dir"
echo "CDN_READY=$(readlink -f "$staging_dir/cdn-ready")"
echo "CDN_PROBE_ASSET=$asset_url"
sha256sum "$candidate_dir/talent-platform.jar"
