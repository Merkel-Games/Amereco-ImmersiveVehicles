#!/usr/bin/env bash
# Runs every gate in order.  Non-zero exit on the first failure.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
CACHE="${IVTEST_CACHE:-$HOME/.cache/ivtest}"
JAVA17="${IVTEST_JAVA17:-/usr/lib/jvm/java-17-openjdk}"
MODULE="$REPO/mcinterfacefabric1201"
GRADLE=("$JAVA17/bin/java" -classpath "$REPO/gradle/neoforge-wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain --no-daemon -p "$MODULE")

declare -a RESULTS=()
gate() { # gate <name> <command...>
    local name="$1"; shift
    echo ""
    echo "===================================================================="
    echo "== GATE: $name"
    echo "===================================================================="
    if "$@"; then
        RESULTS+=("PASS  $name")
    else
        RESULTS+=("FAIL  $name")
        summary
        exit 1
    fi
}
summary() {
    echo ""
    echo "==================== GATE SUMMARY ===================="
    printf '%s\n' "${RESULTS[@]}"
    echo "======================================================"
}

gate "deps (downloads cached)" "$SCRIPT_DIR/download_deps.sh"

build_and_stage() {
    JAVA_HOME="$JAVA17" "${GRADLE[@]}" build || return 1
    mkdir -p "$REPO/out"
    cp "$MODULE/build/libs/Immersive Vehicles-1.20.1-fabric-25.0.0.jar" "$REPO/out/" || return 1
}
gate "build (jar assembled)" build_and_stage

run_gametest() {
    mkdir -p "$MODULE/runGametest/mods"
    cp "$CACHE/ocp.jar" "$MODULE/runGametest/mods/"
    JAVA_HOME="$JAVA17" "${GRADLE[@]}" runGametest || return 1
    local junit="$MODULE/build/gametest/junit.xml"
    [[ -f "$junit" ]] && grep -q 'failures="0"' "$junit" && grep -q 'errors="0"' "$junit"
}
gate "gametest (server-side, OCP loaded)" run_gametest

write_client_options() { # write_client_options <runDir>
    cat > "$1/options.txt" <<EOF
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
}

run_bootonly() {
    mkdir -p "$MODULE/runTest/mods"
    cp "$CACHE/ocp.jar" "$MODULE/runTest/mods/"
    write_client_options "$MODULE/runTest"
    rm -f "$MODULE/runTest/ivtestbed-report.json"
    xvfb-run -a -s "-screen 0 1280x720x24" env ALSOFT_DRIVERS=null LIBGL_ALWAYS_SOFTWARE=1 JAVA_HOME="$JAVA17" \
        timeout 900 "${GRADLE[@]}" runTestmodClient -PivtestbedMode=bootonly
    python3 -c "import json,sys; r=json.load(open('$MODULE/runTest/ivtestbed-report.json')); sys.exit(0 if r.get('pass') else 1)"
}
gate "client bootonly (title screen under Xvfb)" run_bootonly

gate "server smoke (production stack, Java 21)" "$SCRIPT_DIR/server_smoke.sh"

gate "mineflayer bot (network client: items + vehicle spawn)" "$SCRIPT_DIR/mineflayer_test.sh"

gate "client e2e (join server, spawn vehicles, screenshots)" "$SCRIPT_DIR/client_e2e.sh"

summary
echo "ALL GATES GREEN — deliverable: $REPO/out/Immersive Vehicles-1.20.1-fabric-25.0.0.jar"
