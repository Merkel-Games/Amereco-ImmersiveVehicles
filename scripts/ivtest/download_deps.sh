#!/usr/bin/env bash
# Downloads everything the IV Fabric test harness needs into a cache directory.
# Idempotent: skips files that already exist and are non-empty.
set -euo pipefail

CACHE="${IVTEST_CACHE:-$HOME/.cache/ivtest}"
mkdir -p "$CACHE"

MC_VERSION="1.20.1"
LOADER_VERSION="0.19.1"
LAUNCHER_VERSION="1.1.1"
FABRIC_API_VERSION="0.92.9+1.20.1"
OCP_SLUG="${OCP_SLUG:-immersive-vehicles-official-content-pack}"

UA="ivtest-harness/1.0 (automated mod porting test)"

fetch() { # fetch <url> <dest>
    local url="$1" dest="$2"
    if [[ -s "$dest" ]]; then
        echo "SKIP (cached): $dest"
        return 0
    fi
    echo "GET $url"
    curl -fSL --retry 3 -A "$UA" -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
    echo "OK: $dest ($(stat -c%s "$dest") bytes)"
}

# 1. Fabric server launcher (exact stack the user runs).
fetch "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${LAUNCHER_VERSION}/server/jar" \
      "$CACHE/fabric-server-mc.${MC_VERSION}-loader.${LOADER_VERSION}-launcher.${LAUNCHER_VERSION}.jar"

# 2. Fabric API from Modrinth (version-number endpoint; + must be URL-encoded).
FAPI_JSON=$(curl -fsSL -A "$UA" "https://api.modrinth.com/v2/project/fabric-api/version/${FABRIC_API_VERSION//+/%2B}")
FAPI_URL=$(echo "$FAPI_JSON" | python3 -c "import json,sys; v=json.load(sys.stdin); f=[f for f in v['files'] if f.get('primary')] or v['files']; print(f[0]['url'])")
FAPI_NAME=$(echo "$FAPI_JSON" | python3 -c "import json,sys; v=json.load(sys.stdin); f=[f for f in v['files'] if f.get('primary')] or v['files']; print(f[0]['filename'])")
fetch "$FAPI_URL" "$CACHE/$FAPI_NAME"
ln -sf "$FAPI_NAME" "$CACHE/fabric-api.jar"

# 3. Official Content Pack: newest file supporting MC 1.20.1 (pack data is loader-agnostic).
OCP_JSON=$(curl -fsSL -A "$UA" "https://api.modrinth.com/v2/project/${OCP_SLUG}/version")
readarray -t OCP_INFO < <(echo "$OCP_JSON" | python3 -c "
import json, sys
versions = json.load(sys.stdin)
for v in versions:  # Modrinth returns newest first
    if '1.20.1' in v.get('game_versions', []):
        files = [f for f in v['files'] if f.get('primary')] or v['files']
        print(files[0]['url'])
        print(files[0]['filename'])
        print(v['version_number'])
        break
else:
    sys.exit('No OCP version supporting MC 1.20.1 found')
")
fetch "${OCP_INFO[0]}" "$CACHE/${OCP_INFO[1]}"
ln -sf "${OCP_INFO[1]}" "$CACHE/ocp.jar"
echo "OCP version: ${OCP_INFO[2]} -> ${OCP_INFO[1]}"

echo "All dependencies cached in $CACHE"
