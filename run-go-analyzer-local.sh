#!/usr/bin/env bash
# Build and run the Octopus Go analyzer service locally (default port 8378).
# Mirrors run-jvm-analyzer-local.sh: long-running HTTP analyzer consumed by
# code-intelligence's GoAnalyzerExecutor (octopus.go.service-url).
set -euo pipefail

cd "$(dirname "$0")"
PORT="${OCTOPUS_GO_PORT:-8378}"
mkdir -p logs

echo "[go-analyzer] building..."
(cd go-project-analyzer && go build -o bin/go-analyzer .)

echo "[go-analyzer] serving on :${PORT} (log: logs/go-analyzer-service.log)"
cd go-project-analyzer && exec ./bin/go-analyzer serve --port "$PORT" >> ../logs/go-analyzer-service.log 2>&1
