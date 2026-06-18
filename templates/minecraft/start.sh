#!/usr/bin/env bash
set -Eeuo pipefail

# Always run the server from the folder that contains this start.sh.
# This prevents Velocity/Paper from creating logs/plugins/config/eula.txt in /home/container.
cd "$(dirname "$0")"

JAR="${SERVER_JAR:-}"
if [ -z "$JAR" ]; then
  for f in Velocity.jar velocity.jar paper.jar purpur.jar spigot.jar server.jar minecraft.jar minecraft_server.jar; do
    if [ -f "$f" ]; then
      JAR="$f"
      break
    fi
  done
fi

if [ -z "$JAR" ]; then
  echo "No server jar found in $(pwd)"
  exit 1
fi

exec java ${JAVA_FLAGS:-} -jar "$JAR" ${SERVER_ARGS:-nogui}
