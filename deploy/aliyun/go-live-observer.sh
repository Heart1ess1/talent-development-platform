#!/usr/bin/env bash
set -Euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
CONFIG_FILE="${CONFIG_FILE:-/etc/default/talent-platform-go-live-observer}"
TIMER_UNIT="${TIMER_UNIT:-talent-platform-go-live-observer.timer}"

if [[ -f "$CONFIG_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$CONFIG_FILE"
fi

: "${GO_LIVE_OBSERVE_UNTIL_EPOCH:?GO_LIVE_OBSERVE_UNTIL_EPOCH is required}"
: "${GO_LIVE_OBSERVE_LOG:=/data/talent-platform/monitoring/go-live-observation.tsv}"
: "${GO_LIVE_ASSET_URL:=https://static.yryhx.cn/assets/index-nISaq9DD.js}"

now_epoch="$(date +%s)"
timestamp="$(date -Is)"
install -d -m 0750 "$(dirname "$GO_LIVE_OBSERVE_LOG")"

if (( now_epoch >= GO_LIVE_OBSERVE_UNTIL_EPOCH )); then
  printf '%s\tCOMPLETE\tdeadline=%s\n' "$timestamp" "$GO_LIVE_OBSERVE_UNTIL_EPOCH" >>"$GO_LIVE_OBSERVE_LOG"
  systemctl disable --now "$TIMER_UNIT" >/dev/null 2>&1 || true
  exit 0
fi

temp_dir="$(mktemp -d /tmp/talent-go-live-observer.XXXXXX)"
cleanup() {
  case "$temp_dir" in
    /tmp/talent-go-live-observer.*) rm -rf -- "$temp_dir" ;;
  esac
}
trap cleanup EXIT

request_headers() {
  local url="$1" output="$2"
  if ! curl --noproxy '*' -sS -o /dev/null -D "$output" --connect-timeout 10 --max-time 30 "$url"; then
    printf 'HTTP/0 000\r\n\r\n' >"$output"
  fi
}

header_value() {
  local name="$1" file="$2"
  awk -v name="$name" 'BEGIN{IGNORECASE=1} $0 ~ "^" name ":" {sub(/^[^:]+:[[:space:]]*/, ""); sub(/\r$/, ""); value=$0} END{print value}' "$file"
}

status_code() {
  local code
  code="$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' --connect-timeout 10 --max-time 30 "$1" || true)"
  printf '%s' "${code:-000}"
}

root_http_headers="$temp_dir/root-http.headers"
www_https_headers="$temp_dir/www-https.headers"
static_http_headers="$temp_dir/static-http.headers"
static_https_headers="$temp_dir/static-https.headers"
request_headers 'http://yryhx.cn/' "$root_http_headers"
request_headers 'https://www.yryhx.cn/' "$www_https_headers"
request_headers "${GO_LIVE_ASSET_URL/https:\/\//http://}" "$static_http_headers"
request_headers "$GO_LIVE_ASSET_URL" "$static_https_headers"

root_http_code="$(awk 'NR==1{print $2}' "$root_http_headers")"
root_http_location="$(header_value Location "$root_http_headers")"
root_https_code="$(status_code 'https://yryhx.cn/')"
www_https_code="$(awk 'NR==1{print $2}' "$www_https_headers")"
www_https_location="$(header_value Location "$www_https_headers")"
static_http_code="$(awk 'NR==1{print $2}' "$static_http_headers")"
static_http_location="$(header_value Location "$static_http_headers")"
static_https_code="$(awk 'NR==1{print $2}' "$static_https_headers")"
static_content_type="$(header_value Content-Type "$static_https_headers")"
static_cache_control="$(header_value Cache-Control "$static_https_headers")"
health_body="$(curl --noproxy '*' -fsS --connect-timeout 10 --max-time 30 'https://yryhx.cn/actuator/health' || true)"
unauth_code="$(status_code 'https://yryhx.cn/api/v1/exams/attempts/1/status')"
data_used_percent="$(df -P /data 2>/dev/null | awk 'NR==2{gsub(/%/,"",$5); print $5}' || true)"

cd "$APP_DIR"
app_container="$(docker compose ps -q app 2>/dev/null || true)"
db_container="$(docker compose ps -q db 2>/dev/null || true)"
app_state="$(docker inspect -f '{{.State.Status}}' "$app_container" 2>/dev/null || true)"
db_health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$db_container" 2>/dev/null || true)"
recent_error_count="$(docker compose logs --since 6m app 2>&1 | grep -Eic '(^|[[:space:]])ERROR([[:space:]]|$)|Exception' || true)"

failures=()
[[ "$root_http_code" == '301' && "$root_http_location" == 'https://yryhx.cn/' ]] || failures+=(root_http_redirect)
[[ "$root_https_code" == '200' ]] || failures+=(root_https)
[[ "$www_https_code" == '301' && "$www_https_location" == 'https://yryhx.cn/' ]] || failures+=(www_https_redirect)
[[ "$static_http_code" == '301' && "$static_http_location" == "$GO_LIVE_ASSET_URL" ]] || failures+=(static_http_redirect)
[[ "$static_https_code" == '200' ]] || failures+=(static_https)
[[ "$static_content_type" == text/javascript* || "$static_content_type" == application/javascript* ]] || failures+=(static_mime)
[[ "$static_cache_control" == *immutable* ]] || failures+=(static_cache)
[[ "$health_body" == *'"status":"UP"'* ]] || failures+=(health)
[[ "$unauth_code" == '401' ]] || failures+=(unauth_boundary)
[[ "$app_state" == 'running' ]] || failures+=(app_container)
[[ "$db_health" == 'healthy' ]] || failures+=(db_container)
[[ "$data_used_percent" =~ ^[0-9]+$ ]] && (( data_used_percent < 85 )) || failures+=(data_disk)

result='PASS'
if (( ${#failures[@]} > 0 )); then result='FAIL'; fi
printf '%s\t%s\troot=%s/%s\twww=%s\tstatic=%s/%s\thealth=%s\tunauth=%s\tapp=%s\tdb=%s\tdata=%s%%\trecent_errors=%s\n' \
  "$timestamp" "$result" "$root_http_code" "$root_https_code" "$www_https_code" "$static_http_code" "$static_https_code" \
  "$health_body" "$unauth_code" "$app_state" "$db_health" "$data_used_percent" "$recent_error_count" >>"$GO_LIVE_OBSERVE_LOG"

if [[ "$result" == 'FAIL' ]]; then
  printf '%s\tDETAIL\t%s\n' "$timestamp" "$(IFS=,; echo "${failures[*]}")" >>"$GO_LIVE_OBSERVE_LOG"
  exit 1
fi
