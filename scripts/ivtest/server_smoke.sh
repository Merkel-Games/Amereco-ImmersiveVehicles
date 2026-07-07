#!/usr/bin/env bash
# Production-environment smoke test: boots the EXACT user stack
# (fabric-server launcher 1.1.1 / loader 0.19.1 / MC 1.20.1 / Java 21,
# fabric-api 0.92.9, IV fabric jar, Official Content Pack) and asserts a
# clean boot.  With --keep-running the server is left up for client_e2e.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="${IVTEST_CACHE:-$HOME/.cache/ivtest}"
WORK="${IVTEST_WORK:-/tmp/ivtest-work}/server"
PORT="${IVTEST_PORT:-25565}"
JAVA21="${IVTEST_JAVA21:-/usr/lib/jvm/java-21-openjdk/bin/java}"
ALLOWLIST="$SCRIPT_DIR/log-allowlist.txt"

KEEP=0
for a in "$@"; do
    case "$a" in
        --keep-running) KEEP=1 ;;
    esac
done

MOD_JAR="${IVTEST_MOD_JAR:-$REPO/out/Immersive Vehicles-1.20.1-fabric-25.0.0.jar}"
if [[ ! -f "$MOD_JAR" ]]; then
    MOD_JAR="$REPO/mcinterfacefabric1201/build/libs/Immersive Vehicles-1.20.1-fabric-25.0.0.jar"
fi
[[ -f "$MOD_JAR" ]] || { echo "FAIL: mod jar not found (build it first)"; exit 1; }

echo "== server_smoke: staging in $WORK (mod: $MOD_JAR)"
# Pre-kill anything still holding our port from a previous run.
pkill -9 -f "server.jar nogui" 2>/dev/null || true
sleep 1
rm -rf "$WORK"
mkdir -p "$WORK/mods"
cp "$CACHE/fabric-server-mc.1.20.1-loader.0.19.1-launcher.1.1.1.jar" "$WORK/server.jar"
cp "$CACHE/fabric-api.jar" "$WORK/mods/"
cp "$CACHE/ocp.jar" "$WORK/mods/"
cp "$MOD_JAR" "$WORK/mods/"
echo "eula=true" > "$WORK/eula.txt"
cat > "$WORK/server.properties" <<EOF
online-mode=false
server-port=$PORT
gamemode=creative
force-gamemode=true
level-type=minecraft\\:flat
generator-settings={"layers"\\: [{"block"\\: "minecraft\\:bedrock", "height"\\: 1}, {"block"\\: "minecraft\\:dirt", "height"\\: 2}, {"block"\\: "minecraft\\:grass_block", "height"\\: 1}], "biome"\\: "minecraft\\:plains"}
spawn-protection=0
view-distance=6
sync-chunk-writes=false
motd=IV Fabric smoke test
EOF

FIFO="$WORK/console.in"
mkfifo "$FIFO"
# Keeper holds the FIFO's write end open so the console thread never sees EOF.
sleep infinity > "$FIFO" &
KEEPER_PID=$!
(cd "$WORK" && exec "$JAVA21" -Xmx3G -jar server.jar nogui < "$FIFO" > console.log 2>&1) &
SERVER_PID=$!
echo "$SERVER_PID" > "$WORK/server.pid"
echo "$KEEPER_PID" > "$WORK/keeper.pid"

echo "== waiting for server boot (300s budget)"
if ! timeout 300 bash -c "until grep -q 'Done (' '$WORK/console.log' 2>/dev/null; do
        if ! kill -0 $SERVER_PID 2>/dev/null; then exit 2; fi; sleep 2; done"; then
    echo "FAIL: server did not reach Done ("
    tail -40 "$WORK/console.log" || true
    kill "$KEEPER_PID" "$SERVER_PID" 2>/dev/null || true
    exit 1
fi
BOOT_LINE=$(grep "Done (" "$WORK/console.log" | head -1)
echo "== booted: $BOOT_LINE"

FAILED=0
if ! grep -q "Welcome to MTS VERSION" "$WORK/console.log"; then
    echo "FAIL: MTS welcome banner missing — mod did not initialize"
    FAILED=1
fi
# Content-pack assets must all resolve (this catches the Fabric classloader gap).
OBJ_FAILS=$(grep -c "Could not parse model" "$WORK/console.log" || true)
if [[ "$OBJ_FAILS" -ne 0 ]]; then
    echo "FAIL: $OBJ_FAILS content-pack OBJ models failed to load (pack asset resolution broken)"
    grep "Could not parse model" "$WORK/console.log" | head -3
    FAILED=1
fi
# Real JVM crashes: stack traces and mixin failures.  IV routes its own (often benign) messages
# through an "MTSERROR:" channel, so those are excluded; genuine exceptions are not.
BAD_LINES=$(grep -E "^\s+at |Exception|Caused by|Mixin.*fail|Failed to start the minecraft server|FATAL\]" "$WORK/console.log" \
    | grep -v "MTSERROR:" | grep -v -F -f "$ALLOWLIST" || true)
if [[ -n "$BAD_LINES" ]]; then
    echo "FAIL: unexpected JVM errors in server log:"
    echo "$BAD_LINES" | head -30
    FAILED=1
fi
if grep -q "Could not find any pack directories" "$WORK/console.log"; then
    echo "FAIL: pack directory discovery failed"
    FAILED=1
fi

stop_server() {
    echo "== stopping server"
    echo "stop" > "$FIFO"
    for _ in $(seq 1 60); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
    kill "$KEEPER_PID" 2>/dev/null || true
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "FAIL: server did not stop cleanly, killing"
        kill -9 "$SERVER_PID" 2>/dev/null || true
        return 1
    fi
    return 0
}

if [[ "$FAILED" -ne 0 ]]; then
    stop_server || true
    exit 1
fi

if [[ "$KEEP" -eq 1 ]]; then
    echo "== PASS (server left running: pid=$SERVER_PID port=$PORT, stop with: echo stop > $FIFO)"
    exit 0
fi

stop_server || exit 1
echo "== PASS: clean boot and shutdown on the production stack"
