#!/usr/bin/env bash
# Creates a disposable, offline-mode Velocity test network and proves that a
# Bots4Velo client can authenticate, reach PLAY, change backend and recover
# after controlled backend/proxy outages. It is intentionally self-contained:
# Paper/Velocity/AuthMe binaries are downloaded at runtime and are never
# committed to this repository.
set -Eeuo pipefail

MINECRAFT_VERSION="${1:?usage: run-network-integration.sh <minecraft-version> <protocol-id>}"
PROTOCOL_ID="${2:?usage: run-network-integration.sh <minecraft-version> <protocol-id>}"
WORK_ROOT="${WORK_ROOT:-$PWD/.integration-network/$MINECRAFT_VERSION}"
PLUGIN_JAR="${PLUGIN_JAR:-$PWD/build/libs/bots4velo-*.jar}"
PAPER_JAVA="${PAPER_JAVA:-${JAVA_HOME:-}/bin/java}"
USER_AGENT="bots4velo-integration/2.4.0 (https://github.com/Bots4Velo/Bots4Velo)"
PROXY_PORT="${PROXY_PORT:-25590}"
LOBBY_PORT="${LOBBY_PORT:-25591}"
AFK_PORT="${AFK_PORT:-25592}"
PAPER_START_TIMEOUT="${PAPER_START_TIMEOUT:-300}"
PIDS=()

log() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  local process
  for process in "${PIDS[@]:-}"; do
    kill "$process" 2>/dev/null || true
  done
}
trap cleanup EXIT

wait_for_port() {
  local port="$1" timeout="$2"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "Port $port did not start within ${timeout}s"
}

wait_for_log() {
  local file="$1" pattern="$2" timeout="$3"
  local end=$((SECONDS + timeout))
  while (( SECONDS < end )); do
    grep -Eq "$pattern" "$file" 2>/dev/null && return 0
    sleep 1
  done
  tail -n 160 "$file" 2>/dev/null || true
  die "Did not find /$pattern/ in $file within ${timeout}s"
}

wait_for_new_log() {
  local file="$1" pattern="$2" previous_lines="$3" timeout="$4"
  local end=$((SECONDS + timeout))
  local first_new_line=$((previous_lines + 1))
  while (( SECONDS < end )); do
    if [[ -f "$file" ]] && tail -n "+$first_new_line" "$file" 2>/dev/null | grep -Eq "$pattern"; then
      return 0
    fi
    sleep 1
  done
  tail -n 160 "$file" 2>/dev/null || true
  die "Did not find a new /$pattern/ in $file within ${timeout}s"
}

download_fill_artifact() {
  local project="$1" version="$2" destination="$3" response url
  response="$(curl --fail --retry 3 --connect-timeout 20 --max-time 90 --silent --show-error \
    -H "User-Agent: $USER_AGENT" \
    "https://fill.papermc.io/v3/projects/$project/versions/$version/builds")"
  url="$(python3 -c '
import json
import sys
for build in json.load(sys.stdin):
    if build.get("channel") == "STABLE":
        print(build["downloads"]["server:default"]["url"])
        break
' <<<"$response")"
  [[ -n "$url" ]] || die "No stable $project build exists for $version"
  curl --fail --retry 3 --connect-timeout 20 --max-time 240 --location --silent --show-error -H "User-Agent: $USER_AGENT" \
    --output "$destination" "$url"
}

download_authme() {
  local suffix="$1" release_url url
  if [[ -n "${AUTHME_JAR:-}" ]]; then
    [[ -f "$AUTHME_JAR" ]] || die "AUTHME_JAR does not exist: $AUTHME_JAR"
    cp "$AUTHME_JAR" "$WORK_ROOT/lobby/plugins/AuthMe.jar"
    return
  fi
  release_url="https://api.github.com/repos/AuthMe/AuthMeReloaded/releases/tags/6.0.0"
  url="$(curl --fail --retry 3 --connect-timeout 20 --max-time 90 --silent --show-error -H "User-Agent: $USER_AGENT" "$release_url" | \
    python3 -c '
import json
import sys
suffix = sys.argv[1]
for asset in json.load(sys.stdin).get("assets", []):
    if asset.get("name", "").endswith(suffix):
        print(asset["browser_download_url"])
        break
' "$suffix")"
  [[ -n "$url" ]] || die "AuthMe 6.0.0 asset ending in $suffix was not found"
  curl --fail --retry 3 --connect-timeout 20 --max-time 180 --location --silent --show-error -H "User-Agent: $USER_AGENT" \
    --output "$WORK_ROOT/lobby/plugins/AuthMe.jar" "$url"
}

start_paper() {
  local name="$1" port="$2"
  local directory="$WORK_ROOT/$name"
  mkdir -p "$directory"
  cp "$WORK_ROOT/paper.jar" "$directory/paper.jar"
  printf 'eula=true\n' > "$directory/eula.txt"
  cat > "$directory/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$port
online-mode=false
enforce-secure-profile=false
enable-status=true
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=10
motd=Bots4Velo CI $name $MINECRAFT_VERSION
# Bots4Velo acknowledges this URL without downloading it. The same offer is
# intentionally sent after a proxy restart to exercise duplicate pack handling.
resource-pack=https://example.invalid/bots4velo-ci.zip
resource-pack-sha1=da39a3ee5e6b4b0d3255bfef95601890afd80709
require-resource-pack=false
EOF
  (
    cd "$directory"
    exec "$PAPER_JAVA" -Xms256M -Xmx768M -jar paper.jar --nogui
  ) > "$directory/console.log" 2>&1 &
  local paper_pid="$!"
  PIDS+=("$paper_pid")
  if [[ "$name" == "lobby" ]]; then
    LOBBY_PID="$paper_pid"
  else
    AFK_PID="$paper_pid"
  fi
  wait_for_port "$port" "$PAPER_START_TIMEOUT"
  wait_for_log "$directory/console.log" 'Done \(' "$PAPER_START_TIMEOUT"
}

start_velocity() {
  local velocity_directory="$WORK_ROOT/velocity"
  mkdir -p "$velocity_directory/plugins/bots4velo"
  cp "$WORK_ROOT/velocity.jar" "$velocity_directory/velocity.jar"
  cp "$PLUGIN_JAR" "$velocity_directory/plugins/bots4velo.jar"
  cat > "$velocity_directory/velocity.toml" <<EOF
config-version = "2.8"
bind = "127.0.0.1:$PROXY_PORT"
motd = "Bots4Velo $MINECRAFT_VERSION integration"
show-max-players = 20
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "NONE"
ping-passthrough = "DISABLED"
[servers]
lobby = "127.0.0.1:$LOBBY_PORT"
afk = "127.0.0.1:$AFK_PORT"
try = ["lobby"]
[advanced]
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
EOF
  cat > "$velocity_directory/plugins/bots4velo/config.yml" <<EOF
proxy:
  address: "127.0.0.1"
  port: $PROXY_PORT
  virtual-host: "localhost"
  virtual-port: $PROXY_PORT
  protocol-version: "$MINECRAFT_VERSION"
runtime:
  auto-start-delay-ms: 1000
  maximum-bots: 2
  spawn-interval-ms: 250
  command-interval-ms: 100
  resource-pack-mode: "ACCEPT_WITHOUT_DOWNLOAD"
  reconnect:
    initial-delay-ms: 500
    maximum-delay-ms: 2000
    multiplier: 1.0
    jitter: 0.0
    maximum-attempts: 6
  # Keeps the integration bot assigned to AFK. This deliberately retries the
  # assignment after a backend/network interruption so the test can prove the
  # bot returns to the recovered backend rather than merely proving its port
  # opens again.
  presence-rules:
    - id: "maintain-afk"
      server: "afk"
      selector: "IntegrationBot"
      minimum-bots: 1
      maximum-humans: 10
      interval-ms: 750
bots:
  IntegrationBot:
    enabled: true
    username: "B4VCI_${PROTOCOL_ID}"
    password: "Bots4VeloTest"
    target-server: "afk"
    protocol-detection-server: "afk"
    server-switch-command: "server {server}"
    server-switch-delay-ms: 500
    server-switch-maximum-attempts: 6
    auth:
      mode: "AUTO"
      login-command: "login {password}"
      register-command: "register {password} {password}"
      login-delay-ms: 250
      fallback-register-delay-ms: 500
      after-auth-delay-ms: 250
      timeout-ms: 30000
      login-prompts: ["(?i)(please login|/login)"]
      register-prompts: ["(?i)(please register|/register)"]
      success-messages: ["(?i)(registered successfully|logged in successfully|login successful)"]
      failure-messages: ["(?i)(incorrect password|captcha|2fa|verification code|banned)"]
  AuthenticationTimeout:
    enabled: true
    username: "B4VTimeout${PROTOCOL_ID}"
    password: "Bots4VeloTest"
    auth:
      # A new account receives a REGISTER UI; LOGIN intentionally declines it,
      # exercising the pre-join UI timeout rather than accepting a password.
      mode: "LOGIN"
      login-command: "b4vnoop"
      register-command: "b4vnoop"
      login-delay-ms: 100
      fallback-register-delay-ms: 100
      after-auth-delay-ms: 0
      timeout-ms: 2500
      login-prompts: ["(?i)(please login|/login)"]
      register-prompts: ["(?i)(please register|/register)"]
      success-messages: []
      failure-messages: []
EOF
  (
    cd "$velocity_directory"
    exec "$PAPER_JAVA" -Xms256M -Xmx768M -jar velocity.jar
  ) > "$velocity_directory/console.log" 2>&1 &
  PIDS+=("$!")
  VELOCITY_PID="$!"
  wait_for_port "$PROXY_PORT" 60
  wait_for_log "$velocity_directory/console.log" 'bots4velo initialized' 60
}

restart_velocity() {
  kill "$VELOCITY_PID"
  wait "$VELOCITY_PID" || true
  start_velocity
}

mkdir -p "$WORK_ROOT/lobby/plugins" "$WORK_ROOT/afk"
shopt -s nullglob
plugin_candidates=( $PLUGIN_JAR )
(( ${#plugin_candidates[@]} == 1 )) || die "Expected exactly one plugin JAR from $PLUGIN_JAR"
PLUGIN_JAR="${plugin_candidates[0]}"

log "Downloading Paper $MINECRAFT_VERSION and Velocity"
if [[ -n "${PAPER_JAR:-}" ]]; then
  [[ -f "$PAPER_JAR" ]] || die "PAPER_JAR does not exist: $PAPER_JAR"
  cp "$PAPER_JAR" "$WORK_ROOT/paper.jar"
else
  download_fill_artifact paper "$MINECRAFT_VERSION" "$WORK_ROOT/paper.jar"
fi
if [[ -n "${VELOCITY_JAR:-}" ]]; then
  [[ -f "$VELOCITY_JAR" ]] || die "VELOCITY_JAR does not exist: $VELOCITY_JAR"
  cp "$VELOCITY_JAR" "$WORK_ROOT/velocity.jar"
else
  download_fill_artifact velocity "3.5.0-SNAPSHOT" "$WORK_ROOT/velocity.jar"
fi

case "$MINECRAFT_VERSION" in
  1.16.5) download_authme '-Spigot-Legacy.jar' ;;
  *)      download_authme '-Paper.jar' ;;
esac

log "Starting authentication lobby and two Paper backends"
start_paper lobby "$LOBBY_PORT"
start_paper afk "$AFK_PORT"

log "Starting Velocity and Bots4Velo"
start_velocity
VELOCITY_LOG="$WORK_ROOT/velocity/console.log"
wait_for_log "$VELOCITY_LOG" 'entered PLAY' 90
wait_for_log "$VELOCITY_LOG" 'confirmed server switch to afk' 90
wait_for_log "$VELOCITY_LOG" '(submitting its (login|registration) command|matched authentication success)' 90
wait_for_log "$VELOCITY_LOG" 'resource pack: SUCCESSFULLY_LOADED' 90
wait_for_log "$VELOCITY_LOG" 'Bot AuthenticationTimeout stopped after authentication timed out after 2500 ms' 90
wait_for_log "$WORK_ROOT/afk/console.log" "B4VCI_${PROTOCOL_ID} joined the game" 90

log "Fault test: interrupt AFK backend, then prove the presence rule restores the bot"
velocity_log_lines=$(wc -l < "$VELOCITY_LOG")
kill "$AFK_PID"
wait "$AFK_PID" || true
sleep 2
start_paper afk "$AFK_PORT"
wait_for_log "$WORK_ROOT/afk/console.log" 'Done \(' "$PAPER_START_TIMEOUT"
wait_for_log "$WORK_ROOT/afk/console.log" "B4VCI_${PROTOCOL_ID} joined the game" 90
wait_for_new_log "$VELOCITY_LOG" 'resource pack: SUCCESSFULLY_LOADED' "$velocity_log_lines" 90

log "Fault test: restart Velocity; enabled bot must return to PLAY"
restart_velocity
wait_for_log "$VELOCITY_LOG" 'entered PLAY' 90
wait_for_log "$VELOCITY_LOG" 'resource pack: SUCCESSFULLY_LOADED' 90

log "Integration passed: Minecraft $MINECRAFT_VERSION / protocol $PROTOCOL_ID"
