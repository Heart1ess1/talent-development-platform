#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
CANDIDATE_DIR="${1:-$APP_DIR/staging/cdn-ready}"
EXPECTED_CDN="https://static.yryhx.cn"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash activate-cdn-release.sh [候选包目录] 执行"
  exit 1
fi

resolved_app="$(readlink -f "$APP_DIR")"
resolved_candidate="$(readlink -f "$CANDIDATE_DIR")"
case "$resolved_app" in
  /opt/talent-platform) ;;
  *) echo "拒绝操作未核准应用目录：$resolved_app"; exit 1 ;;
esac
case "$resolved_candidate" in
  "$resolved_app"/staging/*) ;;
  *) echo "拒绝使用应用暂存目录之外的候选包：$resolved_candidate"; exit 1 ;;
esac

candidate_jar="$resolved_candidate/talent-platform.jar"
checksums="$resolved_candidate/SHA256SUMS"
if [[ ! -f "$candidate_jar" || ! -f "$checksums" ]]; then
  echo "候选 JAR 或 SHA256SUMS 不存在"
  exit 1
fi

cd "$resolved_candidate"
sha256sum --check --strict SHA256SUMS >/dev/null

index_html="$(unzip -p "$candidate_jar" BOOT-INF/classes/static/index.html)"
asset_url="$(printf '%s' "$index_html" | grep -Eo 'https://static\.yryhx\.cn/assets/[^" ]+\.js' | head -n 1)"
if [[ -z "$asset_url" ]]; then
  echo "候选包不是 static.yryhx.cn 专用构建"
  exit 1
fi

headers="$(curl -fsSI --retry 5 --retry-delay 2 "$asset_url")"
printf '%s' "$headers" | grep -qi '^content-type:.*javascript'
printf '%s' "$headers" | grep -qi '^cache-control:.*immutable'

cd "$resolved_app"
set -a
# shellcheck disable=SC1091
source .env
set +a
: "${OSS_PUBLIC_BUCKET:?缺少 OSS_PUBLIC_BUCKET}"
: "${OSS_RAM_ROLE:?缺少 OSS_RAM_ROLE}"

stamp="$(date +%Y%m%d%H%M%S)"
backup_dir="$resolved_app/backups/cdn-activation-$stamp"
mkdir -p "$backup_dir"
cp -p .env "$backup_dir/.env"
cp -p talent-platform.jar "$backup_dir/talent-platform.jar"

rollback() {
  status=$?
  if [[ $status -eq 0 ]]; then return; fi
  echo "CDN 候选包启用失败，正在恢复原 JAR 与环境变量" >&2
  cp -p "$backup_dir/.env" .env
  cp -p "$backup_dir/talent-platform.jar" talent-platform.jar
  docker compose up -d --no-deps --force-recreate app >/dev/null 2>&1 || true
  exit "$status"
}
trap rollback ERR

if grep -q '^CDN_BASE_URL=' .env; then
  sed -i "s#^CDN_BASE_URL=.*#CDN_BASE_URL=$EXPECTED_CDN#" .env
else
  printf '\nCDN_BASE_URL=%s\n' "$EXPECTED_CDN" >> .env
fi
install -m 0644 "$candidate_jar" talent-platform.jar
docker compose up -d --no-deps --force-recreate app
curl -fsS --retry 45 --retry-delay 2 --retry-all-errors http://127.0.0.1/actuator/health
printf '\n'

active_hash="$(sha256sum talent-platform.jar | awk '{print $1}')"
candidate_hash="$(sha256sum "$candidate_jar" | awk '{print $1}')"
test "$active_hash" = "$candidate_hash"
echo "CDN_RELEASE_ACTIVE=$active_hash"
echo "CDN_ASSET=$asset_url"
