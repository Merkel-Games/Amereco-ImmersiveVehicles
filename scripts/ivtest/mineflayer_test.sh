#!/usr/bin/env bash
# Mineflayer integration gate: boots the production server, joins it with a real
# vanilla-protocol bot, and asserts the mod's items are registered over the network
# and a content-pack vehicle spawns as observable entities.  Self-contained.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="${IVTEST_CACHE:-$HOME/.cache/ivtest}"
WORK="${IVTEST_WORK:-/tmp/ivtest-work}"
SRV="$WORK/mfserver"
PORT="${IVTEST_PORT:-25565}"
JAVA21="${IVTEST_JAVA21:-/usr/lib/jvm/java-21-openjdk/bin/java}"
BOTDIR="$SCRIPT_DIR/mineflayer"

MOD_JAR="${IVTEST_MOD_JAR:-$REPO/out/Immersive Vehicles-1.20.1-fabric-25.0.0.jar}"
[[ -f "$MOD_JAR" ]] || MOD_JAR="$REPO/mcinterfacefabric1201/build/libs/Immersive Vehicles-1.20.1-fabric-25.0.0.jar"
[[ -f "$MOD_JAR" ]] || { echo "FAIL: mod jar not found"; exit 1; }

command -v node >/dev/null || { echo "FAIL: node not installed"; exit 1; }
if [[ ! -d "$BOTDIR/node_modules/mineflayer" ]]; then
    echo "== installing mineflayer"
    ( cd "$BOTDIR" && npm install --no-audit --no-fund >/dev/null 2>&1 ) || { echo "FAIL: npm install"; exit 1; }
fi

echo "== staging server in $SRV"
pkill -9 -f "server.jar nogui" 2>/dev/null || true
sleep 1
rm -rf "$SRV"; mkdir -p "$SRV/mods"
cp "$CACHE/fabric-server-mc.1.20.1-loader.0.19.1-launcher.1.1.1.jar" "$SRV/server.jar"
cp "$CACHE/fabric-api.jar" "$SRV/mods/"
cp "$CACHE/ocp.jar" "$SRV/mods/"
cp "$MOD_JAR" "$SRV/mods/"
echo "eula=true" > "$SRV/eula.txt"
UUID=$(python3 -c "import hashlib,uuid; b=bytearray(hashlib.md5(b'OfflinePlayer:IVTester').digest()); b[6]=(b[6]&0x0f)|0x30; b[8]=(b[8]&0x3f)|0x80; print(str(uuid.UUID(bytes=bytes(b))))")
printf '[{"uuid":"%s","name":"IVTester","level":4,"bypassesPlayerLimit":true}]\n' "$UUID" > "$SRV/ops.json"
printf 'online-mode=false\nserver-port=%s\ngamemode=creative\nforce-gamemode=true\nlevel-type=minecraft\\:flat\nspawn-protection=0\nview-distance=6\nallow-flight=true\n' "$PORT" > "$SRV/server.properties"

echo "== starting server (Java 21, stdin held open)"
# tail -f keeps stdin open so the console reader never sees EOF and shuts down.
tail -f /dev/null | ( cd "$SRV" && "$JAVA21" -Xmx3G -jar server.jar nogui ) > "$SRV/console.log" 2>&1 &
PIPE_PID=$!

cleanup() {
    echo "== stopping server"
    pkill -9 -f "server.jar nogui" 2>/dev/null || true
    kill "$PIPE_PID" 2>/dev/null || true
    pkill -9 -f "tail -f /dev/null" 2>/dev/null || true
}
trap cleanup EXIT

for _ in $(seq 1 90); do
    grep -q "Done (" "$SRV/console.log" 2>/dev/null && break
    sleep 2
done
grep -q "Done (" "$SRV/console.log" || { echo "FAIL: server did not boot"; tail -25 "$SRV/console.log"; exit 1; }
echo "== server up"

echo "== running mineflayer bot"
IV_PORT="$PORT" IV_REPORT="$WORK/mineflayer-report.json" timeout 150 node "$BOTDIR/iv_bot_test.js"
BOT_EXIT=$?

# Assert the vehicle actually produced entities on the server side too.
grep -q "IVTester joined the game" "$SRV/console.log" || { echo "FAIL: bot never joined per server log"; exit 1; }
SESSION_EXC=$(grep -cE "Exception|Caused by" "$SRV/console.log" || true)
if [[ "$SESSION_EXC" -ne 0 ]]; then
    echo "FAIL: $SESSION_EXC exceptions in server log during bot session"; exit 1
fi

if [[ "$BOT_EXIT" -eq 0 ]]; then
    echo "== PASS: mineflayer integration test green"
else
    echo "== FAIL: bot reported failure (exit $BOT_EXIT)"
fi
exit $BOT_EXIT
