#!/usr/bin/env bash
# Climb up / Upwardly for Linux: builds the self-contained native app-image (ELF launcher + bundled Java runtime + assets)
# and a TAR.GZ, mirroring tools/windows/package.ps1. Native x86-64 Linux, suitable for Steam Deck / Steam Machine (not Proton).
# Run on Linux x86-64 with a JDK 17+ (jpackage + jlink on PATH or $JAVA_HOME):
#   tools/linux/package.sh [BUILD_NUMBER]
# Output under build/linux/:
#   dist/Upwardly/bin/Upwardly        the native launcher
#   dist/Upwardly/lib/app/upwardly.jar + lib/app/assets/   the game
#   Upwardly-Linux.tar.gz, SHA256SUMS.txt, BUILD_INFO.txt
# Nothing here talks to Steam or GitHub Releases.
set -euo pipefail
BUILD_NUMBER="${1:-0}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
OUT="$ROOT/build/linux"
JARDIR="$ROOT/desktop/build/linux/input"
mkdir -p "$OUT"
VERSION="$(grep -oP "appVersionName = '\K[^']+" build.gradle)"
APPVER="${VERSION}.${BUILD_NUMBER}"
JP="${JAVA_HOME:-}/bin/jpackage"; [ -x "$JP" ] || JP="$(command -v jpackage)"
echo "Upwardly $VERSION build $BUILD_NUMBER (linux x86-64) via $JP"

# 1. the jar (Linux natives only)
./gradlew :desktop:linuxJar "-PbuildNumber=${BUILD_NUMBER}" --no-daemon
test -f "$JARDIR/upwardly.jar" || { echo "linuxJar missing"; exit 1; }

# 2. jpackage native app-image with a jlink'd runtime (same module set as the Windows build; jdk.crypto.ec for the pinned EC key parse)
DIST="$OUT/dist"; rm -rf "$DIST"; mkdir -p "$DIST"
ICON=""; [ -f "$ROOT/docs/play_store_icon_512.png" ] && ICON="--icon $ROOT/docs/play_store_icon_512.png"
"$JP" --type app-image --name Upwardly --app-version "$APPVER" --vendor 'Hot Attic Games' \
  --description 'Upwardly - a tower that does not exist, a climb that does.' --copyright 'Hot Attic Games' \
  $ICON --input "$JARDIR" --main-jar upwardly.jar --main-class com.hotatticgames.climbup.desktop.DesktopLauncher \
  --add-modules 'java.base,java.desktop,java.logging,java.management,java.naming,java.xml,jdk.unsupported,jdk.crypto.ec' \
  --java-options '-Dfile.encoding=UTF-8' --java-options '-Xms256m' --java-options '-Xmx1024m' --java-options '-XX:+UseG1GC' \
  --dest "$DIST"
APP="$DIST/Upwardly"
test -x "$APP/bin/Upwardly" || { echo "launcher missing"; exit 1; }

# 3. assets beside the jar (the launcher finds <jar-dir>/assets); drop the OTA key material (this build never goes online)
cp -r "$ROOT/assets" "$APP/lib/app/assets"
rm -rf "$APP/lib/app/assets/ota"
mkdir -p "$APP/lib/app/licenses"
cp "$ROOT/ASSETS.md" "$APP/lib/app/licenses/ASSETS.md" 2>/dev/null || true
chmod +x "$APP/bin/Upwardly"

# 4. identification + checksums
SHA="$(git rev-parse HEAD)"
cat > "$APP/BUILD_INFO.txt" <<EOF
Upwardly (Linux x86-64)
version: $VERSION
app version: $APPVER
source sha: $SHA
built: $(date -u +%Y-%m-%dT%H:%M:%SZ)
launch executable: bin/Upwardly
bundled runtime: $(cat "$APP/lib/runtime/release" 2>/dev/null | head -1)
EOF
( cd "$APP" && find . -type f | sort | while read -r f; do printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "${f#./}"; done ) > "$OUT/SHA256SUMS.txt"

# 5. tar.gz (preserve exec bits)
TAR="$OUT/Upwardly-Linux.tar.gz"; rm -f "$TAR"
tar -C "$DIST" -czf "$TAR" Upwardly
printf '%s  %s\n' "$(sha256sum "$TAR" | cut -d' ' -f1)" "$(basename "$TAR")" >> "$OUT/SHA256SUMS.txt"
echo "app-image: $APP"
echo "tar.gz:    $TAR  ($(du -h "$TAR" | cut -f1))"
