#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"
INCOMING_JAR="${1:-/tmp/talent-platform.jar}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash update-app.sh <新 JAR 路径> 执行"
  exit 1
fi
if [[ ! -f "$INCOMING_JAR" ]]; then
  echo "找不到待部署 JAR：$INCOMING_JAR"
  exit 1
fi

install -m 644 "$INCOMING_JAR" "$APP_DIR/talent-platform.jar"
cd "$APP_DIR"
docker compose up -d --no-deps --force-recreate app
curl -fsS --retry 30 --retry-delay 2 --retry-all-errors http://127.0.0.1/actuator/health
printf '\n'
sha256sum "$APP_DIR/talent-platform.jar"
