#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"

case "$APP_DIR" in
  ""|/|/opt|/opt/|/usr|/usr/|/var|/var/|/home|/home/|/root|/root/)
    echo "Refusing unsafe APP_DIR: $APP_DIR" >&2
    exit 1
    ;;
  /*) ;;
  *)
    echo "APP_DIR must be an absolute path: $APP_DIR" >&2
    exit 1
    ;;
esac

usage() {
  cat >&2 <<'EOF'
Usage: sudo ./install-https-certificates.sh ROOT_FULLCHAIN ROOT_KEY WWW_FULLCHAIN WWW_KEY

Installs the yryhx.cn and www.yryhx.cn certificates, enables the HTTPS Nginx
configuration, verifies the live endpoint, and rolls back automatically on failure.
EOF
  exit 2
}

[[ $# -eq 4 ]] || usage

ROOT_CERT="$1"
ROOT_KEY="$2"
WWW_CERT="$3"
WWW_KEY="$4"

for file in "$ROOT_CERT" "$ROOT_KEY" "$WWW_CERT" "$WWW_KEY"; do
  [[ -f "$file" ]] || { echo "Missing certificate file: $file" >&2; exit 1; }
done

for command in openssl docker curl sha256sum; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done

[[ -f "$APP_DIR/docker-compose.yml" ]] || { echo "Missing $APP_DIR/docker-compose.yml" >&2; exit 1; }
[[ -f "$APP_DIR/nginx-https.conf" ]] || { echo "Missing $APP_DIR/nginx-https.conf" >&2; exit 1; }
grep -q '"443:443"' "$APP_DIR/docker-compose.yml" || { echo "docker-compose.yml does not publish 443" >&2; exit 1; }
grep -q './certs:/etc/nginx/certs:ro' "$APP_DIR/docker-compose.yml" || { echo "docker-compose.yml does not mount ./certs" >&2; exit 1; }

openssl x509 -in "$ROOT_CERT" -noout -checkend 3600 >/dev/null
openssl x509 -in "$ROOT_CERT" -noout -checkhost yryhx.cn >/dev/null
openssl x509 -in "$WWW_CERT" -noout -checkend 3600 >/dev/null
openssl x509 -in "$WWW_CERT" -noout -checkhost www.yryhx.cn >/dev/null

cert_public_key_hash() {
  openssl x509 -in "$1" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | sha256sum \
    | awk '{print $1}'
}

private_key_hash() {
  openssl pkey -in "$1" -pubout -outform DER 2>/dev/null \
    | sha256sum \
    | awk '{print $1}'
}

[[ "$(cert_public_key_hash "$ROOT_CERT")" == "$(private_key_hash "$ROOT_KEY")" ]] \
  || { echo "yryhx.cn certificate and private key do not match" >&2; exit 1; }
[[ "$(cert_public_key_hash "$WWW_CERT")" == "$(private_key_hash "$WWW_KEY")" ]] \
  || { echo "www.yryhx.cn certificate and private key do not match" >&2; exit 1; }

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="$APP_DIR/backups/https-$timestamp"
staging_dir="$APP_DIR/certs.new.$timestamp"
mkdir -p "$backup_dir" "$staging_dir/yryhx.cn" "$staging_dir/www.yryhx.cn"

cp "$ROOT_CERT" "$staging_dir/yryhx.cn/fullchain.pem"
cp "$ROOT_KEY" "$staging_dir/yryhx.cn/privkey.pem"
cp "$WWW_CERT" "$staging_dir/www.yryhx.cn/fullchain.pem"
cp "$WWW_KEY" "$staging_dir/www.yryhx.cn/privkey.pem"
chmod 644 "$staging_dir/yryhx.cn/fullchain.pem" "$staging_dir/www.yryhx.cn/fullchain.pem"
chmod 600 "$staging_dir/yryhx.cn/privkey.pem" "$staging_dir/www.yryhx.cn/privkey.pem"

cp "$APP_DIR/nginx.conf" "$backup_dir/nginx.conf"
if [[ -d "$APP_DIR/certs" ]]; then
  cp -a "$APP_DIR/certs" "$backup_dir/certs"
fi

rollback() {
  echo "HTTPS activation failed; restoring the previous Nginx configuration" >&2
  cp "$backup_dir/nginx.conf" "$APP_DIR/nginx.conf"
  rm -rf "$APP_DIR/certs"
  if [[ -d "$backup_dir/certs" ]]; then
    cp -a "$backup_dir/certs" "$APP_DIR/certs"
  else
    mkdir -p "$APP_DIR/certs"
  fi
  (cd "$APP_DIR" && docker compose up -d --force-recreate web) || true
}
trap rollback ERR

rm -rf "$APP_DIR/certs"
mv "$staging_dir" "$APP_DIR/certs"
cp "$APP_DIR/nginx-https.conf" "$APP_DIR/nginx.conf"

cd "$APP_DIR"
docker compose up -d --force-recreate web
docker compose exec -T web nginx -t
curl -fsS --retry 30 --retry-delay 2 --retry-all-errors http://127.0.0.1/actuator/health >/dev/null
root_health="$(curl -fsS --retry 30 --retry-delay 2 --retry-all-errors \
  --resolve yryhx.cn:443:127.0.0.1 https://yryhx.cn/actuator/health)"
grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"$root_health"

www_headers="$(curl -fsSI --retry 30 --retry-delay 2 --retry-all-errors \
  --resolve www.yryhx.cn:443:127.0.0.1 https://www.yryhx.cn/)"
grep -qi '^location:[[:space:]]*https://yryhx\.cn/' <<<"$www_headers"

trap - ERR
echo "HTTPS_ACTIVE backup=$backup_dir"
