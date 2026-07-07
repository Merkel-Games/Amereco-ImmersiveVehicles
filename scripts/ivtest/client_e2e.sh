#!/usr/bin/env bash
# Full end-to-end test: a production fabric server (real launcher, Java 21, IV jar + OCP)
# plus a headless dev client under Xvfb that auto-joins via quickPlay, spawns OCP vehicles,
# screenshots them, and writes ivtestbed-report.json.  Self-contained: starts the server
# directly (no FIFO), tracks its PID, and kills it on exit.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="${IVTEST_CACHE:-$HOME/.cache/ivtest}"
WORK="${IVTEST_WORK:-/tmp/ivtest-work}"
PORT="${IVTEST_PORT:-25565}"
JAVA21="${IVTEST_JAVA21:-/usr/lib/jvm/java-21-openjdk/bin/java}"
JAVA17="${IVTEST_JAVA17:-/usr/lib/jvm/java-17-openjdk}"
MODULE="$REPO/mcinterfacefabric1201"
RUNDIR="$MODULE/runTest"
OUTDIR="$REPO/test-output"
SRV="$WORK/server"

MOD_JAR="${IVTEST_MOD_JAR:-$REPO/out/Immersive Vehicles-1.20.1-fabric-25.0.0.jar}"
[[ -f "$MOD_JAR" ]] || MOD_JAR="$MODULE/build/libs/Immersive Vehicles-1.20.1-fabric-25.0.0.jar"
[[ -f "$MOD_JAR" ]] || { echo "FAIL: mod jar not found"; exit 1; }

echo "== e2e: staging server in $SRV"
pkill -9 -f "server.jar nogui" 2>/dev/null || true
sleep 1
rm -rf "$SRV"
mkdir -p "$SRV/mods"
cp "$CACHE/fabric-server-mc.1.20.1-loader.0.19.1-launcher.1.1.1.jar" "$SRV/server.jar"
cp "$CACHE/fabric-api.jar" "$SRV/mods/"
cp "$CACHE/ocp.jar" "$SRV/mods/"
cp "$MOD_JAR" "$SRV/mods/"
echo "eula=true" > "$SRV/eula.txt"
# Pre-op the test player (offline UUID) so /give and /tp work from the client.
OFFLINE_UUID=$(python3 -c "import hashlib,uuid; b=bytearray(hashlib.md5(b'OfflinePlayer:IVTester').digest()); b[6]=(b[6]&0x0f)|0x30; b[8]=(b[8]&0x3f)|0x80; print(str(uuid.UUID(bytes=bytes(b))))")
cat > "$SRV/ops.json" <<EOF
[{"uuid": "$OFFLINE_UUID", "name": "IVTester", "level": 4, "bypassesPlayerLimit": true}]
EOF
cat > "$SRV/server.properties" <<EOF
online-mode=false
server-port=$PORT
gamemode=creative
force-gamemode=true
level-type=minecraft\\:flat
spawn-protection=0
view-distance=6
allow-flight=true
motd=IV e2e
EOF

echo "== starting server (Java 21)"
( cd "$SRV" && exec "$JAVA21" -Xmx3G -jar server.jar nogui ) > "$SRV/console.log" 2>&1 &
SERVER_PID=$!

cleanup() {
    echo "== killing server pid $SERVER_PID"
    kill "$SERVER_PID" 2>/dev/null || true
    sleep 3
    kill -9 "$SERVER_PID" 2>/dev/null || true
    pkill -9 -f "server.jar nogui" 2>/dev/null || true
}
trap cleanup EXIT

echo "== waiting for server boot"
for _ in $(seq 1 60); do
    grep -q "Done (" "$SRV/console.log" 2>/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "FAIL: server died before boot"; tail -30 "$SRV/console.log"; exit 1; }
    sleep 3
done
grep -q "Done (" "$SRV/console.log" || { echo "FAIL: server did not reach Done"; tail -30 "$SRV/console.log"; exit 1; }
echo "== server up: $(grep 'Done (' "$SRV/console.log" | head -1)"

echo "== preparing client run dir"
mkdir -p "$RUNDIR/mods"
cp "$CACHE/ocp.jar" "$RUNDIR/mods/"
rm -f "$RUNDIR/ivtestbed-report.json"
rm -rf "$RUNDIR/screenshots-ivtest"
cat > "$RUNDIR/options.txt" <<EOF
pauseOnLostFocus:false
renderDistance:4
simulationDistance:5
soundCategory_master:0.0
soundCategory_music:0.0
onboardAccessibility:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
fullscreen:false
EOF

echo "== launching headless client (quickPlay -> localhost:$PORT)"
xvfb-run -a -s "-screen 0 1280x720x24" env \
    ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 JAVA_HOME="$JAVA17" \
    timeout 1500 "$JAVA17/bin/java" \
    -classpath "$REPO/gradle/neoforge-wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain --no-daemon \
    -p "$MODULE" runTestmodClient \
    -PivtestbedMode=e2e "-PivtestbedServer=localhost:$PORT" \
    > "$WORK/client.log" 2>&1
echo "== client exited ($?); verdict comes from the report"

REPORT="$RUNDIR/ivtestbed-report.json"
[[ -f "$REPORT" ]] || { echo "FAIL: no report produced"; tail -50 "$WORK/client.log"; exit 1; }
mkdir -p "$OUTDIR"
cp "$REPORT" "$OUTDIR/"
cp -r "$RUNDIR/screenshots-ivtest" "$OUTDIR/" 2>/dev/null || true

python3 - "$REPORT" <<'PYEOF'
import json, sys
r = json.load(open(sys.argv[1]))
print(json.dumps(r, indent=1, ensure_ascii=False)[:3000])
problems = []
if not r.get("pass"):
    problems.append("report pass=false: " + str(r.get("failReason")))
shots = r.get("screenshots", [])
worldshots = [s for s in shots if s["name"].startswith("iv_world") or s["name"] == "iv_thirdperson"]
if not worldshots:
    problems.append("no world screenshots captured")
for s in worldshots:
    if s["stddev"] <= 10 or s["nonBlackPct"] <= 0.2:
        problems.append(f"screenshot {s['name']} looks blank: {s}")
if problems:
    print("E2E PROBLEMS:"); [print(" -", p) for p in problems]
    sys.exit(1)
print("E2E REPORT OK")
PYEOF
RESULT=$?
if [[ "$RESULT" -eq 0 ]]; then echo "== PASS: e2e complete, artifacts in $OUTDIR"; else echo "== FAIL: e2e report validation failed"; fi
exit $RESULT
