#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== kgram bootstrap =="
echo "项目根目录: ${ROOT}"
echo

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "错误: 未找到命令 '$1'，请先安装。" >&2
    exit 1
  fi
}

require_cmd docker

if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "错误: 需要 Docker Compose v2（docker compose）或 docker-compose。" >&2
  exit 1
fi

echo "Docker: $(docker --version)"
echo "Compose: $($COMPOSE version 2>/dev/null || true)"
echo

echo "下一步建议："
echo "  1. 阅读 ${ROOT}/README.md 与 docs/decisions.md、docs/roadmap.md"
echo "  2. 按 deploy/openim/README.md 接入 OpenIM 官方 Docker Compose"
echo "  3. 复制 deploy/livekit/livekit.yaml.example 为 livekit.yaml 并替换占位密钥（勿提交）"
echo "  4. cd ${ROOT}/deploy && ${COMPOSE} --profile livekit up -d   # 可选：仅 LiveKit"
echo "  5. cd ${ROOT}/deploy && ${COMPOSE} --profile dev up -d       # 可选：Go 网关占位"
echo "  6. android/ 待 Telegram-Android fork 就绪后再构建客户端"
echo
echo "Agent / RAG 当前不在范围内，见 docs/decisions.md。"
