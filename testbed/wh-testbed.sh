#!/usr/bin/env bash
#
# wh-testbed.sh - Two-server cross-server Wormholes test rig.
#
# Drives the Multiplexor server manager to stand up a pair of isolated Purpur
# instances (ALPHA + BETA) on this machine, deploys the freshly-built Wormholes
# jar into both, writes a concise loopback-paired wormholes.toml into each, and starts
# them. Everything stays clear of the live PURPUR-MAIN instance.
#
# After `up`, the only remaining step is in-game (portals need rune blocks):
#   1. join ALPHA (localhost:25566), /wormholes wand, build a GATEWAY portal
#   2. open Pair & Destination, then Create Invite and copy the portal code
#   3. use Use Invite on BETA (or /wh network import <code>), then build a GATEWAY on
#      BETA and link it to ALPHA's gateway
#
# Usage:
#   ./wh-testbed.sh up         build jar, create/configure/start both servers
#   ./wh-testbed.sh redeploy   rebuild jar + copy into both (hotload via BileTools)
#   ./wh-testbed.sh restart    restart both servers
#   ./wh-testbed.sh down       stop both servers (keeps instances + worlds)
#   ./wh-testbed.sh destroy    stop + delete both instances entirely
#   ./wh-testbed.sh status     runtime states + tail of each wormholes-stats.txt
#   ./wh-testbed.sh logs <a|b> tail -f an instance log
#   ./wh-testbed.sh console <a|b>  attach the tmux console (Esc detaches)
#
set -euo pipefail

# ---- configuration ----------------------------------------------------------
MANAGER_DIR="${WH_MANAGER_DIR:-/Users/brianfopiano/Developer/RemoteGit/[Minecraft Server]}"
WH_PLUGIN="${WH_PLUGIN_DIR:-/Users/brianfopiano/Developer/RemoteGit/VolmitSoftware/WormholesPlugin}"
DROPINS="${MANAGER_DIR}/consumers/plugin-consumers/dropins/plugins"

A_NAME="WH-ALPHA"; A_GAME_PORT=25566; A_WH_PORT=8902
B_NAME="WH-BETA";  B_GAME_PORT=25567; B_WH_PORT=8903
WH_MC="${WH_MC:-26.2}"

MX() { "${MANAGER_DIR}/start.sh" "$@"; }

# ---- helpers ----------------------------------------------------------------
say() { printf '\033[36m[testbed]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[testbed] %s\033[0m\n' "$*" >&2; exit 1; }

instance_exists() { MX instance list 2>/dev/null | grep -qF -- "$1"; }
instance_path()   { MX instance path "$1" 2>/dev/null | tail -1; }
game_port()       { MX runtime states 2>/dev/null | awk -v n="$1" '$1==n{print $3; exit}'; }

set_prop() { # file key value
  local f="$1" k="$2" v="$3"
  [ -f "$f" ] || return 0
  if grep -q "^${k}=" "$f"; then
    sed -i '' "s|^${k}=.*|${k}=${v}|" "$f"
  else
    printf '%s=%s\n' "$k" "$v" >> "$f"
  fi
}

write_network_toml() { # configdir servername listenport
  local dir="$1" name="$2" port="$3"
  # WH_SIDEBAND=true (default) forces the game-port status sideband by NOT opening the raw
  # listener, so a loopback pair exercises the same path as two NAT'd prod servers.
  local listen_enabled="false"; [ "${WH_SIDEBAND:-true}" = "false" ] && listen_enabled="true"
  mkdir -p "$dir"
  cat > "${dir}/wormholes.toml" <<EOF
schema = 2
quality = "auto"

[network]
enabled = true
server-name = "${name}"
listen-enabled = ${listen_enabled}
listen-port = ${port}
advertise-host-override = "127.0.0.1"
trust-on-first-use = true
entity-transfer-deny-types = ""
EOF
  rm -f "${dir}/network.toml" "${dir}/main.toml" "${dir}/projection.toml" "${dir}/render.toml"
}

build_jar() {
  say "building Wormholes shadowJar..."
  ( cd "$WH_PLUGIN" && ./gradlew shadowJar -q )
  WH_JAR="$(ls -t "${WH_PLUGIN}"/build/libs/Wormholes-*.jar | grep -vE 'sources|javadoc' | head -1)"
  [ -n "${WH_JAR:-}" ] || die "no Wormholes jar found in ${WH_PLUGIN}/build/libs"
  say "jar: ${WH_JAR}"
}

deploy_to() { # instance-name servername wh-port
  local name="$1" sname="$2" whport="$3"
  local path; path="$(instance_path "$name")"
  [ -n "$path" ] && [ -d "$path" ] || die "cannot resolve path for ${name}"
  mkdir -p "${path}/plugins"
  cp -f "$WH_JAR" "${path}/plugins/Wormholes.jar"
  [ -f "${DROPINS}/BileTools.jar" ] && cp -f "${DROPINS}/BileTools.jar" "${path}/plugins/BileTools.jar"
  write_network_toml "${path}/plugins/Wormholes/config" "$sname" "$whport"
  say "deployed jar + wormholes.toml (${sname}, wh-port ${whport}) -> ${name}"
}

configure_props() { # instance-name game-port motd
  local name="$1" gport="$2" motd="$3"
  local path; path="$(instance_path "$name")"
  local props="${path}/server.properties"
  printf 'eula=true\n' > "${path}/eula.txt"
  touch "$props"
  set_prop "$props" server-port "$gport"
  set_prop "$props" online-mode false
  set_prop "$props" gamemode creative
  set_prop "$props" level-type "minecraft:normal"
  set_prop "$props" spawn-protection 0
  set_prop "$props" view-distance 8
  set_prop "$props" simulation-distance 6
  set_prop "$props" difficulty peaceful
  set_prop "$props" allow-flight true
  set_prop "$props" accepts-transfers true
  set_prop "$props" spawn-monsters false
  set_prop "$props" motd "$motd"
}

ensure_instance() { # name
  if instance_exists "$1"; then
    say "instance ${1} exists - reusing"
  else
    say "creating isolated purpur instance ${1} (mc ${WH_MC})..."
    MX server create "$1" --type purpur --isolated --mc "${WH_MC}" || say "create reported failure for ${1} (continuing — likely already exists)"
  fi
}

# ---- commands ---------------------------------------------------------------
cmd_up() {
  [ -x "${MANAGER_DIR}/start.sh" ] || die "manager not found at ${MANAGER_DIR}"
  build_jar
  ensure_instance "$A_NAME"
  ensure_instance "$B_NAME"
  configure_props "$A_NAME" "$A_GAME_PORT" "Wormholes ALPHA (wh ${A_WH_PORT})"
  configure_props "$B_NAME" "$B_GAME_PORT" "Wormholes BETA (wh ${B_WH_PORT})"
  deploy_to "$A_NAME" alpha "$A_WH_PORT"
  deploy_to "$B_NAME" beta  "$B_WH_PORT"
  say "starting both servers..."
  MX runtime start "$A_NAME" --no-console
  MX runtime start "$B_NAME" --no-console
  cmd_status
  local agp bgp
  agp="$(game_port "$A_NAME")"; agp="${agp:-$A_GAME_PORT}"
  bgp="$(game_port "$B_NAME")"; bgp="${bgp:-$B_GAME_PORT}"
  cat <<EOF

$(say "pair is up:")
  ALPHA  game=localhost:${agp}  wh-listen=${A_WH_PORT}  name=alpha
  BETA   game=localhost:${bgp}  wh-listen=${B_WH_PORT}  name=beta
  (the Multiplexor auto-assigns game ports - the values above are the live ones)

Cross-server GATEWAY link:
  1. Join ALPHA (localhost:${agp}). Run: /wormholes wand rune=gateway
     Build a GATEWAY portal, open Pair & Destination, click Create Invite, and copy the code.
  2. On BETA use Use Invite or run: /wh network import <code>
     Then build a GATEWAY on BETA and link it to ALPHA. Check with /wh status.
EOF
}

cmd_redeploy() {
  build_jar
  deploy_to "$A_NAME" alpha "$A_WH_PORT"
  deploy_to "$B_NAME" beta  "$B_WH_PORT"
  say "jars copied; BileTools should hotload. Use 'restart' if you need a clean reload."
}

cmd_restart() { MX runtime restart "$A_NAME" --no-console; MX runtime restart "$B_NAME" --no-console; cmd_status; }
cmd_down()    { MX runtime stop "$A_NAME" || true; MX runtime stop "$B_NAME" || true; }
cmd_destroy() { cmd_down; MX instance delete "$A_NAME" || true; MX instance delete "$B_NAME" || true; }

cmd_status() {
  say "runtime states:"
  MX runtime states 2>/dev/null | grep -E "^(${A_NAME}|${B_NAME})[[:space:]]" || say "  (neither instance running)"
  for n in "$A_NAME" "$B_NAME"; do
    local p; p="$(instance_path "$n" 2>/dev/null)"
    local stats="${p}/plugins/Wormholes/wormholes-stats.txt"
    if [ -f "$stats" ]; then
      say "${n} wormholes-stats.txt (head):"
      head -12 "$stats" | sed 's/^/    /'
    fi
  done
}

cmd_logs()    { local n; n="$([ "${1:-}" = b ] && echo "$B_NAME" || echo "$A_NAME")"; tail -f "$(instance_path "$n")/logs/latest.log"; }
cmd_console() { local n; n="$([ "${1:-}" = b ] && echo "$B_NAME" || echo "$A_NAME")"; MX runtime console "$n"; }

case "${1:-}" in
  up)       cmd_up ;;
  redeploy) cmd_redeploy ;;
  restart)  cmd_restart ;;
  down)     cmd_down ;;
  destroy)  cmd_destroy ;;
  status)   cmd_status ;;
  logs)     cmd_logs "${2:-a}" ;;
  console)  cmd_console "${2:-a}" ;;
  *) awk '/^set -euo/{exit} NR>1 && /^#/{sub(/^# ?/,"");print}' "$0"; exit 0 ;;
esac
