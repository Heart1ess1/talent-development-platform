#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/talent-platform}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash install-runtime.sh 执行"
  exit 1
fi

dnf install -y docker docker-compose-plugin
if ! mountpoint -q /data; then
  echo "缺少 /data 数据盘挂载；为避免 Docker 在系统盘创建持久数据目录，停止安装"
  exit 1
fi
install -d -m 755 /etc/systemd/system/docker.service.d
install -m 644 "$APP_DIR/docker-talent-data.conf" \
  /etc/systemd/system/docker.service.d/talent-data.conf
systemctl daemon-reload
systemctl enable --now docker
docker version --format '{{.Server.Version}}'
docker compose version
