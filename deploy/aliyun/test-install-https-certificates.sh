#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_BASE="${TMPDIR:-/tmp}"
TEST_ROOT="$(mktemp -d "$TEMP_BASE/talent-https-test.XXXXXX")"

cleanup() {
  case "$TEST_ROOT" in
    "$TEMP_BASE"/talent-https-test.*) rm -rf "$TEST_ROOT" ;;
    *) echo "Refusing to clean unexpected test path: $TEST_ROOT" >&2 ;;
  esac
}
trap cleanup EXIT

FAKE_BIN="$TEST_ROOT/bin"
FIXTURES="$TEST_ROOT/fixtures"
mkdir -p "$FAKE_BIN" "$FIXTURES"

cat >"$FAKE_BIN/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
EOF

cat >"$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"www.yryhx.cn"* ]]; then
  if [[ "${FAKE_WWW_LOCATION_OK:-0}" == "1" ]]; then
    printf 'HTTP/2 301\r\nlocation: https://yryhx.cn/\r\n\r\n'
  else
    printf 'HTTP/2 200\r\n\r\n'
  fi
else
  printf '{"status":"UP"}\n'
fi
EOF
chmod +x "$FAKE_BIN/docker" "$FAKE_BIN/curl"

MSYS2_ARG_CONV_EXCL='/CN=' openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
  -subj '/CN=yryhx.cn' -addext 'subjectAltName=DNS:yryhx.cn' \
  -keyout "$FIXTURES/root.key" -out "$FIXTURES/root.pem" >/dev/null 2>&1
MSYS2_ARG_CONV_EXCL='/CN=' openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
  -subj '/CN=www.yryhx.cn' -addext 'subjectAltName=DNS:www.yryhx.cn' \
  -keyout "$FIXTURES/www.key" -out "$FIXTURES/www.pem" >/dev/null 2>&1

prepare_app() {
  local app_dir="$1"
  mkdir -p "$app_dir/certs/yryhx.cn" "$app_dir/certs/www.yryhx.cn"
  cp "$SCRIPT_DIR/docker-compose.yml" "$app_dir/docker-compose.yml"
  cp "$SCRIPT_DIR/nginx-https.conf" "$app_dir/nginx-https.conf"
  printf '# original nginx configuration\n' >"$app_dir/nginx.conf"
  printf 'old-root-cert\n' >"$app_dir/certs/yryhx.cn/fullchain.pem"
  printf 'old-root-key\n' >"$app_dir/certs/yryhx.cn/privkey.pem"
  printf 'old-www-cert\n' >"$app_dir/certs/www.yryhx.cn/fullchain.pem"
  printf 'old-www-key\n' >"$app_dir/certs/www.yryhx.cn/privkey.pem"
}

export PATH="$FAKE_BIN:$PATH"
export FAKE_DOCKER_LOG="$TEST_ROOT/docker.log"

if APP_DIR=/ "$SCRIPT_DIR/install-https-certificates.sh" \
  "$FIXTURES/root.pem" "$FIXTURES/root.key" "$FIXTURES/www.pem" "$FIXTURES/www.key" >/dev/null 2>&1; then
  echo 'Unsafe APP_DIR was accepted' >&2
  exit 1
fi

MISMATCH_APP="$TEST_ROOT/mismatch-app"
prepare_app "$MISMATCH_APP"
if APP_DIR="$MISMATCH_APP" FAKE_WWW_LOCATION_OK=1 "$SCRIPT_DIR/install-https-certificates.sh" \
  "$FIXTURES/root.pem" "$FIXTURES/www.key" "$FIXTURES/www.pem" "$FIXTURES/www.key" >/dev/null 2>&1; then
  echo 'Mismatched certificate and key were accepted' >&2
  exit 1
fi

SUCCESS_APP="$TEST_ROOT/success-app"
prepare_app "$SUCCESS_APP"
APP_DIR="$SUCCESS_APP" FAKE_WWW_LOCATION_OK=1 "$SCRIPT_DIR/install-https-certificates.sh" \
  "$FIXTURES/root.pem" "$FIXTURES/root.key" "$FIXTURES/www.pem" "$FIXTURES/www.key" \
  | grep -q '^HTTPS_ACTIVE backup='
cmp -s "$SUCCESS_APP/nginx.conf" "$SUCCESS_APP/nginx-https.conf"
openssl x509 -in "$SUCCESS_APP/certs/yryhx.cn/fullchain.pem" -noout -checkhost yryhx.cn >/dev/null
openssl x509 -in "$SUCCESS_APP/certs/www.yryhx.cn/fullchain.pem" -noout -checkhost www.yryhx.cn >/dev/null
grep -q 'compose up -d --force-recreate web' "$FAKE_DOCKER_LOG"
grep -q 'compose exec -T web nginx -t' "$FAKE_DOCKER_LOG"

ROLLBACK_APP="$TEST_ROOT/rollback-app"
prepare_app "$ROLLBACK_APP"
if APP_DIR="$ROLLBACK_APP" FAKE_WWW_LOCATION_OK=0 "$SCRIPT_DIR/install-https-certificates.sh" \
  "$FIXTURES/root.pem" "$FIXTURES/root.key" "$FIXTURES/www.pem" "$FIXTURES/www.key" >/dev/null 2>&1; then
  echo 'Invalid www redirect unexpectedly passed verification' >&2
  exit 1
fi
grep -q '^# original nginx configuration$' "$ROLLBACK_APP/nginx.conf"
grep -q '^old-root-cert$' "$ROLLBACK_APP/certs/yryhx.cn/fullchain.pem"
grep -q '^old-www-cert$' "$ROLLBACK_APP/certs/www.yryhx.cn/fullchain.pem"

echo 'HTTPS_INSTALL_TESTS_OK'
