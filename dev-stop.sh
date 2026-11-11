#!/usr/bin/env bash
# 停止 dev-start.sh 启动的后端服务与前端；加 --infra 同时停止 MySQL / Neo4j / RabbitMQ 容器（数据卷保留）。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
PIDS="$ROOT/logs/pids"

for f in "$PIDS"/*.pid; do
  [[ -e "$f" ]] || continue
  name="$(basename "$f" .pid)"; pid="$(cat "$f")"
  if kill -0 "$pid" 2>/dev/null; then
    # 前端是 npm → vite 的进程树，先结束子进程
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill -TERM "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null
    echo "已停止 $name（PID $pid）"
  fi
  rm -f "$f"
done

if [[ "${1:-}" == "--infra" ]]; then
  if docker compose version >/dev/null 2>&1; then docker compose -f docker-compose.infra.yml down
  elif command -v podman >/dev/null 2>&1; then podman compose -f docker-compose.infra.yml down
  elif command -v podman-compose >/dev/null 2>&1; then podman-compose -f docker-compose.infra.yml down
  elif command -v docker-compose >/dev/null 2>&1; then docker-compose -f docker-compose.infra.yml down
  fi
fi
