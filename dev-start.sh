#!/usr/bin/env bash
# 本地一键启动：基础设施（MySQL / Neo4j / RabbitMQ）+ 全部后端服务 + 前端开发服务器。
#
#   ./dev-start.sh              # 构建并启动全部
#   ./dev-start.sh --no-build   # 跳过构建，直接用已有的 boot jar
#   ./dev-start.sh --no-infra   # 基础设施已在运行时跳过 compose
#   ./dev-stop.sh               # 停止（加 --infra 同时停止容器）
#
# 日志：logs/<服务>.log；PID：logs/pids/<服务>.pid
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
LOGS="$ROOT/logs"; PIDS="$LOGS/pids"
mkdir -p "$LOGS" "$PIDS"

BUILD=1; INFRA=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-infra) INFRA=0 ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "未知参数: $arg"; exit 1 ;;
  esac
done

# 服务：模块名 端口 健康检查路径
SERVICES=(
  "code-intelligence 8080 /api/query/projects"
  "octopus-git 8081 /actuator/health"
  "octopus-code-reconstruct 8082 /actuator/health"
  "octopus-review-agent 8083 /api/business-flow/models"
  "octopus-code-base 8084 /actuator/health"
  "octopus-analysis-scheduler 8085 /actuator/health"
  "octopus-scenario-runner 8086 /api/business-testing/capabilities"
)

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
wait_port() { # name port seconds
  local i
  for ((i = 0; i < $3; i += 2)); do port_open "$2" && return 0; sleep 2; done
  return 1
}

# ---------------------------------------------------------------- Java 21
if [[ -z "${JAVA21:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JAVA21="$(/usr/libexec/java_home -v 21)/bin/java"
  elif [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA21="$JAVA_HOME/bin/java"
  else
    JAVA21="$(command -v java || true)"
  fi
fi
if [[ -z "$JAVA21" ]] || ! "$JAVA21" -version 2>&1 | grep -Eq 'version "(2[1-9]|[3-9][0-9])'; then
  echo "需要 Java 21 或更高版本运行服务（可设置 JAVA21=/path/to/java）。当前: ${JAVA21:-未找到}"; exit 1
fi
export JAVA_HOME="$(cd "$(dirname "$JAVA21")/.." && pwd)"

# ---------------------------------------------------------------- 环境变量（仅本地开发默认值）
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-octpus123}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
export GIT_PASSWORD="${GIT_PASSWORD:-dummy}"
export JMETER_HOME="${JMETER_HOME:-$ROOT/data/tools/apache-jmeter-5.6.3}"
if [[ -z "${OCTOPUS_ENCRYPTION_KEY:-}" ]]; then
  KEY_FILE="$LOGS/.dev-encryption-key"
  [[ -f "$KEY_FILE" ]] || LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 >"$KEY_FILE"
  export OCTOPUS_ENCRYPTION_KEY="$(cat "$KEY_FILE")"
  echo "提示：未设置 OCTOPUS_ENCRYPTION_KEY，使用本地开发密钥 $KEY_FILE（请勿用于生产）"
fi

# ---------------------------------------------------------------- 基础设施
if [[ $INFRA -eq 1 ]]; then
  if docker compose version >/dev/null 2>&1; then COMPOSE=(docker compose)
  elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then COMPOSE=(podman compose)
  elif command -v podman-compose >/dev/null 2>&1; then COMPOSE=(podman-compose)
  elif command -v docker-compose >/dev/null 2>&1; then COMPOSE=(docker-compose)
  else echo "未找到 docker / podman compose；请先启动 MySQL、Neo4j、RabbitMQ，再用 --no-infra 运行"; exit 1
  fi
  echo "==> 启动基础设施（${COMPOSE[*]}）"
  "${COMPOSE[@]}" -f docker-compose.infra.yml up -d
  for p in "MySQL 3306" "Neo4j 7687" "RabbitMQ 5672"; do
    set -- $p
    printf '    等待 %-9s :%s ... ' "$1" "$2"
    if wait_port "$1" "$2" 120; then echo "就绪"; else echo "超时"; exit 1; fi
  done
  sleep 5   # MySQL/Neo4j 端口打开后仍需几秒完成初始化
fi

# ---------------------------------------------------------------- 构建
if [[ $BUILD -eq 1 ]]; then
  echo "==> 构建后端（bootJar）与 JMeter 扩展 distribution"
  TASKS=()
  for s in "${SERVICES[@]}"; do set -- $s; TASKS+=(":$1:bootJar"); done
  ./gradlew --console=plain -x test "${TASKS[@]}" :octopus-jmeter-ext:distribution
  if [[ -d "$JMETER_HOME/lib/ext" ]]; then
    echo "==> 安装 octopus-jmeter-ext（含 SDK 与 ExtensionProbe）到 $JMETER_HOME"
    cp -f octopus-jmeter-ext/build/jmeter-extension/lib/ext/*.jar "$JMETER_HOME/lib/ext/"
    cp -f octopus-jmeter-ext/build/jmeter-extension/lib/*.jar "$JMETER_HOME/lib/"
  else
    echo "提示：$JMETER_HOME 不是 JMeter 安装目录，业务测试执行与扩展校验将不可用"
  fi
fi

# ---------------------------------------------------------------- 后端
start_service() { # module port
  local module="$1" port="$2" jar
  if [[ -f "$PIDS/$module.pid" ]] && kill -0 "$(cat "$PIDS/$module.pid")" 2>/dev/null; then
    echo "    $module 已在运行（PID $(cat "$PIDS/$module.pid")）"; return
  fi
  if port_open "$port"; then echo "    跳过 $module：端口 $port 已被占用"; return; fi
  jar="$(ls "$module"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1 || true)"
  if [[ -z "$jar" ]]; then echo "    跳过 $module：未找到 boot jar（去掉 --no-build 重新运行）"; return; fi
  nohup "$JAVA21" -jar "$jar" >"$LOGS/$module.log" 2>&1 &
  echo $! >"$PIDS/$module.pid"
  echo "    $module → PID $!，端口 $port，日志 logs/$module.log"
}

echo "==> 启动后端服务"
for s in "${SERVICES[@]}"; do set -- $s; start_service "$1" "$2"; done

# ---------------------------------------------------------------- 前端
echo "==> 启动前端（Vite，端口 3000）"
if [[ -f "$PIDS/frontend.pid" ]] && kill -0 "$(cat "$PIDS/frontend.pid")" 2>/dev/null; then
  echo "    前端已在运行"
else
  ( cd octopus-frontend
    [[ -d node_modules ]] || npm install
    nohup npm run dev -- --host 127.0.0.1 >"$LOGS/frontend.log" 2>&1 &
    echo $! >"$PIDS/frontend.pid" )
  echo "    前端 → PID $(cat "$PIDS/frontend.pid")，日志 logs/frontend.log"
fi

# ---------------------------------------------------------------- 健康检查
echo "==> 等待服务就绪（最多 3 分钟）"
ALL=("${SERVICES[@]}" "frontend 3000 /")
for s in "${ALL[@]}"; do
  set -- $s
  printf '    %-28s :%s  ' "$1" "$2"
  if wait_port "$1" "$2" 180; then
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$2$3" || true)"
    echo "已监听（HTTP $code）"
  else
    echo "未就绪，查看 logs/$1.log"
  fi
done

cat <<EOF

完成。打开 http://localhost:3000
  · 业务 API 测试 → DSL 工作台：            http://localhost:3000/business-testing/dsl
  · 业务 API 测试 → JMeter 扩展与函数：      http://localhost:3000/business-testing/extensions
首次使用 DSL / 扩展 / 轮询授权前，请对 MySQL 执行：
  octopus-scenario-runner/src/main/resources/db/migrations/20261014_dsl_extensions_poll_authorization.sql
停止：./dev-stop.sh（加 --infra 同时停止容器）
EOF
