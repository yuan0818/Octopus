#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-jvm-analyzer-local.sh — Start the JVM Project Analyzer service locally on macOS (no Docker)
#
# What this does:
#   1. Builds the plugin JAR via :jvm-project-analyzer:buildPlugin (skipped if up-to-date)
#   2. Launches IntelliJ IDEA directly from the system installation with the
#      plugin, bypassing the Gradle IntelliJ plugin's runIde (which would
#      download and copy IDEA).  The IDEA installation path is read from the
#      IDEA_HOME environment variable, or defaults to IntelliJ IDEA 2.app (2025.3).
#   3. IDEA starts in headless mode and listens on PORT (default 8377)
#   4. All output (stdout + stderr) is written to logs/jvm-analyzer-service.log
#      and also streamed to the terminal in real time.
#
# Usage:
#   ./run-jvm-analyzer-local.sh                        # default port 8377
#   PORT=9000 ./run-jvm-analyzer-local.sh              # custom port
#   IDEA_HOME=/path/to/idea ./run-jvm-analyzer-local.sh # custom IDEA path
# ---------------------------------------------------------------------------
set -euo pipefail

PORT="${PORT:-8377}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── IDEA installation ─────────────────────────────────────────────────────────
# Priority: 1. IDEA_HOME env var  2. IntelliJ IDEA 2.app (2025.3)  3. any IntelliJ IDEA.app
# IDEA 2026.1+ ships with JBR 25 which is incompatible with the Gradle IntelliJ
# plugin's runIde task, and Kotlin PSI is deeply embedded in the platform making
# it unresolvable from the plugin classloader. 2025.3 uses classic plugin-based
# Kotlin support and is the recommended target.
if [ -n "${IDEA_HOME:-}" ]; then
  IDEA_APP="$IDEA_HOME"
elif [ -d "/Applications/IntelliJ IDEA 2.app" ]; then
  IDEA_APP="/Applications/IntelliJ IDEA 2.app"
elif [ -d "/Applications/IntelliJ IDEA.app" ]; then
  IDEA_APP="/Applications/IntelliJ IDEA.app"
else
  echo "ERROR: IntelliJ IDEA not found."
  echo "  Set IDEA_HOME to the root of your IntelliJ installation, e.g.:"
  echo "    export IDEA_HOME=/Applications/IntelliJ IDEA 2.app"
  echo "    export IDEA_HOME=/opt/idea/IC-2025.3"
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

# Auto-detect the IDEA version from the .app bundle and derive a sandbox dir
# name matching the installed version (e.g. IU-2025.3, IC-2025.3).
# Falls back to IC-2025.3 for older installations without a version key.
IDEA_VERSION=""
IDEA_BUILD=""
if [ -f "${IDEA_APP}/Contents/Info.plist" ]; then
  IDEA_BUILD=$(plutil -p "${IDEA_APP}/Contents/Info.plist" 2>/dev/null \
    | grep '"CFBundleVersion"' | awk '{print $3}' | tr -d '"')
  IDEA_VERSION=$(plutil -p "${IDEA_APP}/Contents/Info.plist" 2>/dev/null \
    | grep '"CFBundleShortVersionString"' | awk '{print $3}' | tr -d '"')
fi

if [ -n "$IDEA_BUILD" ]; then
  # CFBundleVersion is "IU-253.???" → extract the "IU" prefix
  IDEA_TYPE=$(echo "$IDEA_BUILD" | cut -d- -f1)
  if [ "IC" = "$IDEA_TYPE" ] || [ "IU" = "$IDEA_TYPE" ]; then
    SANDBOX_TAG="${IDEA_TYPE}-${IDEA_VERSION}"
  else
    SANDBOX_TAG="IC-${IDEA_VERSION:-2025.3}"
  fi
else
  SANDBOX_TAG="IC-2025.3"
fi
SANDBOX_DIR="${SCRIPT_DIR}/jvm-project-analyzer/build/idea-sandbox/${SANDBOX_TAG}"

echo "==> IDEA binary      : ${IDEA_BIN}"
echo "==> IDEA build       : ${IDEA_BUILD:-unknown}"
echo "==> Sandbox dir      : ${SANDBOX_DIR}"

# ── Log & sandbox setup ─────────────────────────────────────────────────────
LOG_DIR="${SCRIPT_DIR}/logs"
LOG_FILE="${LOG_DIR}/jvm-analyzer-service.log"
mkdir -p "${LOG_DIR}" "${SANDBOX_DIR}/config" "${SANDBOX_DIR}/system" "${SANDBOX_DIR}/plugins"

echo "==> JVM Analyzer log : ${LOG_FILE}"
echo "==> Sandbox dir      : ${SANDBOX_DIR}"
echo ""

{
  echo ""
  echo "======================================================="
  echo "  JVM Analyzer service started at $(date '+%Y-%m-%d %H:%M:%S')"
  echo "  port=${PORT}"
  echo "  IDEA_HOME=${IDEA_APP}"
  echo "======================================================="
} | tee -a "${LOG_FILE}"

# ── Build ────────────────────────────────────────────────────────────────────
echo "==> Building jvm-project-analyzer plugin..."
"${SCRIPT_DIR}/gradlew" :jvm-project-analyzer:buildPlugin -q

# ── Deploy plugin ────────────────────────────────────────────────────────────
PLUGIN_ZIP=$(ls -t "${SCRIPT_DIR}/jvm-project-analyzer/build/distributions/"jvm-project-analyzer-*.zip 2>/dev/null | head -1)
if [ -z "$PLUGIN_ZIP" ]; then
  echo "ERROR: Plugin distribution ZIP not found. Build may have failed."
  exit 1
fi
echo "==> Plugin distribution: ${PLUGIN_ZIP}"

rm -rf "${SANDBOX_DIR}/plugins/jvm-project-analyzer"
unzip -q -o "$PLUGIN_ZIP" -d "${SANDBOX_DIR}/plugins/"
echo "==> Plugin deployed to ${SANDBOX_DIR}/plugins/jvm-project-analyzer"

# ── Bundle Kotlin plugin from IDEA installation into sandbox ─────────────────
# The PSI code references Kotlin PSI classes (KtAnnotated etc) which are not
# included in the plugin JAR — they ship with IDEA's Kotlin plugin.  When
# runIde copies IDEA it includes bundled plugins automatically, but run-jvm-analyzer-local.sh
# uses the local IDEA installation which doesn't copy its bundled plugins.
KOTLIN_SRC="${IDEA_APP}/Contents/plugins/Kotlin"
KOTLIN_DST="${SANDBOX_DIR}/plugins/Kotlin"
if [ -d "$KOTLIN_SRC" ] && [ ! -d "$KOTLIN_DST" ]; then
  echo "==> Bundling Kotlin plugin from IDEA installation..."
  cp -R "$KOTLIN_SRC" "$KOTLIN_DST"
  echo "==> Kotlin plugin bundled (${KOTLIN_DST})"
fi

# ── Install plugin into IDEA's own plugins dir (so platform bundled plugins like Kotlin load) ──
# Derive the JetBrains config directory name from the detected version.
# CFBundleVersion "IU-253.12345.67" → "IntelliJIdea2025.3"
if [ -n "$IDEA_VERSION" ]; then
  IDEA_CONFIG_VER="IntelliJIdea${IDEA_VERSION}"
else
  IDEA_CONFIG_VER="IntelliJIdea2025.3"
fi
IDEA_USER_PLUGINS="${HOME}/Library/Application Support/JetBrains/${IDEA_CONFIG_VER}/plugins"
mkdir -p "${IDEA_USER_PLUGINS}"
ln -sfn "${SANDBOX_DIR}/plugins/jvm-project-analyzer" "${IDEA_USER_PLUGINS}/jvm-project-analyzer"
echo "==> Plugin linked to ${IDEA_USER_PLUGINS}/jvm-project-analyzer"

# ── Run (no Gradle runIde, no copy of IDEA) ─────────────────────────────────
echo "==> Starting JVM Analyzer service on port ${PORT} (headless)..."
echo "    Logs → ${LOG_FILE}"
echo "    Press Ctrl-C to stop."
echo ""

# IDEA expects JVM properties in IDEA_VM_OPTIONS, not as CLI args after the command.
# Build classpath including Kotlin PSI jars (needed for Kotlin analysis)
KOTLIN_CP=""
if [ -d "${IDEA_APP}/Contents/plugins/Kotlin/lib" ]; then
  for j in "${IDEA_APP}"/Contents/plugins/Kotlin/lib/*.jar; do
    KOTLIN_CP="${KOTLIN_CP}:${j}"
  done
  for j in "${IDEA_APP}"/Contents/plugins/Kotlin/kotlinc/lib/*.jar; do
    [ -f "$j" ] || continue
    KOTLIN_CP="${KOTLIN_CP}:${j}"
  done 2>/dev/null
fi

# On macOS, IDEA_VM_OPTIONS must point to a vmoptions FILE (not individual JVM flags).
# Write all JVM options to that file, then set IDEA_VM_OPTIONS to its path.
CUSTOM_VMOPTIONS="${SANDBOX_DIR}/config/idea.vmoptions"
cat > "$CUSTOM_VMOPTIONS" << VMEOF
-Xms256m
-Xmx4g
-XX:ReservedCodeCacheSize=512m
-Didea.headless.mode=true
-Didea.no.platform.update=true
-Didea.config.path=${SANDBOX_DIR}/config
-Didea.system.path=${SANDBOX_DIR}/system
-Didea.plugins.path=${SANDBOX_DIR}/plugins
-Djb.privacy.policy.text=<!-- 999.999 -->
-Djb.consents.confirmation.enabled=false
-Didea.trust.all.projects=true
VMEOF
export IDEA_VM_OPTIONS="${CUSTOM_VMOPTIONS}"

# Intentionally NOT setting JAVA_TOOL_OPTIONS — IDEA 2025.3 warns it overrides vmoptions.

exec "${IDEA_BIN}" analyze serve --port "${PORT}" 2>&1 | tee -a "${LOG_FILE}"
