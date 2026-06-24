#!/usr/bin/env bash
# Start code-intelligence and trigger Platform analysis
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_PATH="${PLATFORM_PATH:-$HOME/workspace/Platform}"   # override via PLATFORM_PATH env var
API="http://localhost:8080"
LOG="$SCRIPT_DIR/logs/code-intelligence.log"

# Kill existing server if running
OLD_PID=$(pgrep -f "OctopusApplication\|code-intelligence.*jar" 2>/dev/null || true)
if [ -n "$OLD_PID" ]; then
  echo "==> Killing existing code-intelligence (PID $OLD_PID)..."
  kill "$OLD_PID"
  sleep 2
fi

# Start server in background
echo "==> Starting code-intelligence (log: $LOG)..."
nohup "$SCRIPT_DIR/gradlew" :code-intelligence:bootRun > "$LOG" 2>&1 &
SERVER_PID=$!
echo "    Gradle PID: $SERVER_PID"

# Wait for server to be ready
echo "==> Waiting for server on port 8080..."
for i in $(seq 1 60); do
  if curl -sf "$API/api/query/projects" > /dev/null 2>&1; then
    echo "    Server is UP (attempt $i)"
    break
  fi
  sleep 3
  echo "    ... waiting ($((i*3))s)"
done

# Trigger Platform analysis
echo "==> Triggering local analysis for Platform..."
RESPONSE=$(curl -sf -X POST "$API/api/analysis/local" \
  -H "Content-Type: application/json" \
  -d "{\"projectPath\": \"$PLATFORM_PATH\", \"projectName\": \"Platform\"}")

echo "    Response: $RESPONSE"
TASK_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('id','?'))" 2>/dev/null || echo "?")
echo ""
echo "==> Analysis started! Task ID: $TASK_ID"
echo "    Poll: curl $API/api/analysis/tasks/$TASK_ID"
echo "    Or watch: http://localhost:3000"
