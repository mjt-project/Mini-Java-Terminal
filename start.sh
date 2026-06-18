```bash
#!/usr/bin/env bash
set -Eeuo pipefail

# ==================================================
# MJT Minecraft Startup Script
# Purpose:
#   - Restore server.properties before Minecraft starts
#   - Auto-detect Vanilla/Paper jar or Forge unix_args.txt
#   - Keep flags readable and easy to maintain
# ==================================================

APP_DIR="/home/container"
cd "$APP_DIR"

# ---------- Console colors ----------
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
CYAN="\033[36m"
RESET="\033[0m"

info() {
  echo -e "${CYAN}[START]${RESET} $*"
}

ok() {
  echo -e "${GREEN}[OK]${RESET} $*"
}

warn() {
  echo -e "${YELLOW}[WARN]${RESET} $*"
}

fail() {
  echo -e "${RED}[ERROR]${RESET} $*"
  exit 1
}

# ---------- Restore server.properties ----------
# Do not use: rm server.properties
# Reason: it fails when the file does not exist and is less safe.
restore_server_properties() {
  if [[ -f "server_backup.properties" ]]; then
    cp -f "server_backup.properties" "server.properties"
    ok "Restored server.properties from server_backup.properties"
  else
    warn "server_backup.properties not found. Keeping current server.properties."
  fi
}

# ---------- Detect server launch target ----------
detect_launch_args() {
  # Manual override:
  #   SERVER_JAR=minecraft_server.jar ./start-minecraft.sh
  if [[ -n "${SERVER_JAR:-}" ]]; then
    [[ -f "$SERVER_JAR" ]] || fail "SERVER_JAR is set but file not found: $SERVER_JAR"
    echo "-jar" "$SERVER_JAR" "nogui"
    return
  fi

  # Common Vanilla/Paper/Purpur style.
  # Avoid using server.jar here if server.jar is reserved for MJT itself.
  local jar_candidates=(
    "minecraft_server.jar"
    "paper.jar"
    "purpur.jar"
    "spigot.jar"
    "bukkit.jar"
  )

  for jar in "${jar_candidates[@]}"; do
    if [[ -f "$jar" ]]; then
      echo "-jar" "$jar" "nogui"
      return
    fi
  done

  # Forge modern launcher:
  # libraries/net/minecraftforge/forge/<version>/unix_args.txt
  local forge_base="libraries/net/minecraftforge/forge"

  if [[ -d "$forge_base" ]]; then
    local forge_dir
    forge_dir="$(find "$forge_base" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"

    if [[ -n "$forge_dir" && -f "$forge_dir/unix_args.txt" ]]; then
      echo "@$forge_dir/unix_args.txt" "nogui"
      return
    fi
  fi

  fail "No supported Minecraft server target found. Expected minecraft_server.jar/paper.jar/purpur.jar or Forge unix_args.txt."
}

# ---------- Java config ----------
JAVA_BIN="${JAVA_BIN:-java}"
XMS="${XMS:-128M}"
MAX_RAM_PERCENTAGE="${MAX_RAM_PERCENTAGE:-95.0}"

# Java flags kept readable for future developers.
# IgnoreUnrecognizedVMOptions helps when Java versions change.
JAVA_FLAGS=(
  "-Xms${XMS}"
  "-XX:MaxRAMPercentage=${MAX_RAM_PERCENTAGE}"

  "-XX:+IgnoreUnrecognizedVMOptions"
  "-XX:+UseG1GC"
  "-XX:+ParallelRefProcEnabled"
  "-XX:MaxGCPauseMillis=200"
  "-XX:+UnlockExperimentalVMOptions"
  "-XX:+DisableExplicitGC"

  "-XX:G1NewSizePercent=30"
  "-XX:G1MaxNewSizePercent=40"
  "-XX:G1HeapRegionSize=8M"
  "-XX:G1ReservePercent=20"
  "-XX:G1HeapWastePercent=5"
  "-XX:G1MixedGCCountTarget=4"
  "-XX:InitiatingHeapOccupancyPercent=15"
  "-XX:G1MixedGCLiveThresholdPercent=90"
  "-XX:G1RSetUpdatingPauseTimePercent=5"

  "-XX:SurvivorRatio=32"
  "-XX:+PerfDisableSharedMem"
  "-XX:MaxTenuringThreshold=1"

  "-Dusing.aikars.flags=https://mcflags.emc.gs"
  "-Daikars.new.flags=true"
  "-Dterminal.jline=false"
  "-Dterminal.ansi=true"
)

# ---------- Start ----------
clear

info "Working directory: $APP_DIR"
info "Java version:"
"$JAVA_BIN" -version

restore_server_properties

read -r -a SERVER_ARGS <<< "$(detect_launch_args)"

info "Launch command:"
echo "$JAVA_BIN ${JAVA_FLAGS[*]} ${SERVER_ARGS[*]}"
echo

exec "$JAVA_BIN" "${JAVA_FLAGS[@]}" "${SERVER_ARGS[@]}"
```
