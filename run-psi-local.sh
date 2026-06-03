#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-psi-local.sh — Start the Octopus PSI service locally on macOS (no Docker)
#
# What this does:
#   1. Builds the plugin JAR via :octopus-psi:buildPlugin (skipped if up-to-date)
#   2. Launches IntelliJ IDEA directly from the system installation with the
#      plugin, bypassing the Gradle IntelliJ plugin's runIde (which would
#      download and copy IDEA).  The IDEA installation path is read from the
#      IDEA_HOME environment variable, or defaults to /Applications/IntelliJ IDEA.app
#   3. IDEA starts in headless mode and listens on PORT (default 8377)
#   4. All output (stdout + stderr) is written to logs/psi-service.log
#      and also streamed to the terminal in real time.
#
# Usage:
#   ./run-psi-local.sh                        # default port 8377
#   PORT=9000 ./run-psi-local.sh              # custom port
#   IDEA_HOME=/path/to/idea ./run-psi-local.sh # custom IDEA path
# ---------------------------------------------------------------------------
set -euo pipefail

PORT="${PORT:-8377}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── IDEA installation ─────────────────────────────────────────────────────────
# Priority: 1. IDEA_HOME env var  2. /Applications/IntelliJ IDEA.app
if [ -n "${IDEA_HOME:-}" ]; then
  IDEA_APP="$IDEA_HOME"
elif [ -d "/Applications/IntelliJ IDEA.app" ]; then
  IDEA_APP="/Applications/IntelliJ IDEA.app"
else
  echo "ERROR: IntelliJ IDEA not found."
  echo "  Set IDEA_HOME to the root of your IntelliJ installation, e.g.:"
  echo "    export IDEA_HOME=/Applications/IntelliJ IDEA.app"
  echo "    export IDEA_HOME=/opt/idea/IC-2024.3"
  exit 1
fi

# Resolve the IDEA binary (macOS .app vs Linux tar.gz)
if [ -d "${IDEA_APP}/Contents" ]; then
  IDEA_BIN="${IDEA_APP}/Contents/MacOS/idea"
  IDEA_PLUGINS="${IDEA_APP}/Contents/plugins"
else
  IDEA_BIN="${IDEA_APP}/bin/idea.sh"
  IDEA_PLUGINS="${IDEA_APP}/plugins"
fi

if [ ! -x "$IDEA_BIN" ]; then
  echo "ERROR: IDEA binary not found at ${IDEA_BIN}"
  exit 1
fi

echo "==> IDEA installation: ${IDEA_APP}"
echo "==> IDEA binary      : ${IDEA_BIN}"

# ── Log & sandbox setup ─────────────────────────────────────────────────────
LOG_DIR="${SCRIPT_DIR}/logs"
LOG_FILE="${LOG_DIR}/psi-service.log"
SANDBOX_DIR="${SCRIPT_DIR}/octopus-psi/build/idea-sandbox/IC-2024.3"
mkdir -p "${LOG_DIR}" "${SANDBOX_DIR}/config" "${SANDBOX_DIR}/system" "${SANDBOX_DIR}/plugins"

echo "==> PSI service log  : ${LOG_FILE}"
echo "==> Sandbox dir      : ${SANDBOX_DIR}"
echo ""

{
  echo ""
  echo "======================================================="
  echo "  PSI service started at $(date '+%Y-%m-%d %H:%M:%S')"
  echo "  port=${PORT}"
  echo "  IDEA_HOME=${IDEA_APP}"
  echo "======================================================="
} | tee -a "${LOG_FILE}"

# ── Build ────────────────────────────────────────────────────────────────────
echo "==> Building octopus-psi plugin..."
"${SCRIPT_DIR}/gradlew" :octopus-psi:buildPlugin -q

# ── Deploy plugin ────────────────────────────────────────────────────────────
PLUGIN_ZIP=$(ls -t "${SCRIPT_DIR}/octopus-psi/build/distributions/"octopus-psi-*.zip 2>/dev/null | head -1)
if [ -z "$PLUGIN_ZIP" ]; then
  echo "ERROR: Plugin distribution ZIP not found. Build may have failed."
  exit 1
fi
echo "==> Plugin distribution: ${PLUGIN_ZIP}"

rm -rf "${SANDBOX_DIR}/plugins/octopus-psi"
unzip -q -o "$PLUGIN_ZIP" -d "${SANDBOX_DIR}/plugins/"
echo "==> Plugin deployed to ${SANDBOX_DIR}/plugins/octopus-psi"

# ── Run (no Gradle runIde, no copy of IDEA) ─────────────────────────────────
echo "==> Starting PSI service on port ${PORT} (headless)..."
echo "    Logs → ${LOG_FILE}"
echo "    Press Ctrl-C to stop."
echo ""

# IDEA expects JVM properties in IDEA_VM_OPTIONS, not as CLI args after the command.
export IDEA_VM_OPTIONS="\
  -Didea.headless.mode=true \
  -Didea.no.platform.update=true \
  -Didea.config.path=${SANDBOX_DIR}/config \
  -Didea.system.path=${SANDBOX_DIR}/system \
  -Didea.plugins.path=${SANDBOX_DIR}/plugins \
  -Djb.privacy.policy.text=<!--\ 999.999\ --> \
  -Djb.consents.confirmation.enabled=false \
  -Didea.trust.all.projects=true"

# Also set JAVA_TOOL_OPTIONS as a fallback for JVM flags
export JAVA_TOOL_OPTIONS="-Didea.headless.mode=true -Didea.no.platform.update=true ${JAVA_TOOL_OPTIONS:-}"

exec "${IDEA_BIN}" analyze serve --port "${PORT}" 2>&1 | tee -a "${LOG_FILE}"
