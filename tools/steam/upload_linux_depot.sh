#!/usr/bin/env bash
# Upload the native Linux / Steam Deck build of Upwardly to its Steam depot, FROM A LINUX MACHINE (the Steam Deck in
# Desktop Mode works). Uploading from Windows loses the executable bit on bin/Upwardly, so the Deck could not start it.
#
#   bash upload_linux_depot.sh <steam_login> <linux_depot_id> [build_number]
#
# It downloads Valve's steamcmd (Linux) and the Build <n> Linux package from the GitHub release, checks the
# package's SHA-256 against the release's checksums.sha256 and that bin/Upwardly is executable, writes the two
# SteamPipe scripts and uploads. It sets NO branch live (SetLive is empty): make the build live yourself on the
# Steamworks Builds page, on a test branch or default as you choose. steamcmd asks for your password and Steam
# Guard code itself; nothing is stored by this script.
set -euo pipefail
LOGIN="${1:?usage: upload_linux_depot.sh <steam_login> <linux_depot_id> [build_number]}"
DEPOT="${2:?usage: upload_linux_depot.sh <steam_login> <linux_depot_id> [build_number]}"
BUILD="${3:-70}"
APP=5427130
WORK="${HOME}/upwardly-steam-upload"
REL="https://github.com/verbal76/Climb-Up/releases/download/build-${BUILD}-desktop"
PKG="Upwardly.Build.${BUILD}.Linux.tar.gz"

mkdir -p "$WORK" && cd "$WORK"
echo "== 1/5 steamcmd"
if [ ! -x steamcmd/steamcmd.sh ]; then
  mkdir -p steamcmd
  curl -fsSL https://steamcdn-a.akamaihd.net/client/installer/steamcmd_linux.tar.gz | tar -xz -C steamcmd
fi

echo "== 2/5 Build ${BUILD} Linux package"
curl -fsSL -o "$PKG" "$REL/$PKG"
curl -fsSL -o checksums.sha256 "$REL/checksums.sha256"
want=$(grep -E "Upwardly[ .]Build[ .]${BUILD}[ .]Linux\.tar\.gz$" checksums.sha256 | cut -d' ' -f1 || true)
have=$(sha256sum "$PKG" | cut -d' ' -f1)
if [ -z "$want" ] || [ "$want" != "$have" ]; then echo "SHA-256 mismatch for $PKG (release says '$want', file is '$have'). Stopping."; exit 1; fi
echo "SHA-256 OK: $have"

echo "== 3/5 unpack"
rm -rf content && mkdir content
tar -xzf "$PKG" -C content
# the archive holds one folder (Upwardly/); the depot root is its inside, so the launch option bin/Upwardly matches
if [ -d content/Upwardly ]; then shopt -s dotglob; mv content/Upwardly/* content/; rmdir content/Upwardly; fi
[ -x content/bin/Upwardly ] || { echo "content/bin/Upwardly is missing or not executable. Stopping."; exit 1; }
echo "launcher OK: $(ls -l content/bin/Upwardly)"

echo "== 4/5 SteamPipe scripts"
mkdir -p scripts output
cat > scripts/app_build_linux.vdf <<EOF
"AppBuild"
{
	"AppID" "${APP}"
	"Desc" "Build ${BUILD} Linux (native)"
	"ContentRoot" "${WORK}/content/"
	"BuildOutput" "${WORK}/output/"
	"SetLive" ""
	"Depots"
	{
		"${DEPOT}" "depot_build_linux.vdf"
	}
}
EOF
cat > scripts/depot_build_linux.vdf <<EOF
"DepotBuild"
{
	"DepotID" "${DEPOT}"
	"FileMapping"
	{
		"LocalPath" "*"
		"DepotPath" "."
		"Recursive" "1"
	}
}
EOF

echo "== 5/5 upload (steamcmd will ask for your password and Steam Guard code)"
./steamcmd/steamcmd.sh +login "$LOGIN" +run_app_build "${WORK}/scripts/app_build_linux.vdf" +quit
echo "Done. Steamworks > SteamPipe > Builds: the new 'Build ${BUILD} Linux (native)' build is there, not live. Set it live yourself."
