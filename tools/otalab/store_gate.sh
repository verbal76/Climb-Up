#!/usr/bin/env bash
# Play-compatible packaging gate. The STORE edition is the game compiled into one APK with no way to fetch or load code (Google Play forbids downloaded executable code); the sideload
# edition (the OTA host) is a separate application with its own id. This gate inspects the built store-style APK (the unmodified :android release path) and proves it carries none of the
# OTA host's machinery, that it has the same game classes the module carries, and that its permissions are what the shipped game declares. A positive control runs the same scanner over the
# OTA host APK, which must be found to load code, so a scanner that sees nothing cannot pass silently.
# usage: store_gate.sh STORE_APK HOST_APK MODULE_CLASSES_TXT(dexdump "Class descriptor" lines of the module dex) BUILD_TOOLS_DIR
set -u
STORE=$1; HOSTAPK=$2; MODCLS=$3; BT=$4; W=$(mktemp -d); FAILS=0
pass() { echo "PASS  $*"; }; fail() { echo "FAIL  $*"; FAILS=$((FAILS+1)); }
scan() { # prints "class -> loader type" for every app-code class that references a dynamic class loader
  local apk=$1 d=$2; mkdir -p "$d"; unzip -qo "$apk" 'classes*.dex' -d "$d"
  for f in "$d"/classes*.dex; do "$BT/dexdump" -d "$f" 2>/dev/null; done | awk '/Class descriptor/ {cls=$4} /dalvik\/system\/(Dex|InMemoryDex|Path|DelegateLast)ClassLoader/ {print cls " -> " $0}' | sed "s/  */ /g" | cut -c1-200 | sort -u
}
classes() { local apk=$1 d=$2; mkdir -p "$d"; unzip -qo "$apk" 'classes*.dex' -d "$d"; for f in "$d"/classes*.dex; do "$BT/dexdump" -f "$f" | grep "Class descriptor"; done | sed "s/.*'\(L[^']*;\)'.*/\1/" | sort -u; }

echo "== store APK: $(basename "$STORE")"
scan "$STORE" "$W/store" > "$W/store_loaders.txt"; scan "$HOSTAPK" "$W/host" > "$W/host_loaders.txt"
echo "   dynamic class-loader references in the STORE APK from the game's own classes (com.hotatticgames): $(grep -c "^'\?Lcom/hotatticgames/" "$W/store_loaders.txt" || true)"
grep "Lcom/hotatticgames/" "$W/store_loaders.txt" && fail "store APK: game/host code references a dynamic class loader" || pass "store APK: no game or host class references DexClassLoader/PathClassLoader/InMemoryDexClassLoader"
echo "   (references from third-party libraries in the store APK, informational: $(grep -vc "Lcom/hotatticgames/" "$W/store_loaders.txt" || true))"; grep -v "Lcom/hotatticgames/" "$W/store_loaders.txt" | head -5 | sed 's/^/     /'
grep -q "Lcom/hotatticgames/climbup/otalab/LabLauncher" "$W/host_loaders.txt" && pass "control: the scanner finds the loader in the OTA host APK (LabLauncher)" || fail "control: the scanner did not find the loader in the OTA host APK"

classes "$STORE" "$W/s2" > "$W/store_classes.txt"; classes "$HOSTAPK" "$W/h2" > "$W/host_classes.txt"
n=$(grep -c "Lcom/hotatticgames/climbup/host/" "$W/store_classes.txt" || true); [ "${n:-0}" -eq 0 ] && pass "store APK: no OTA host classes (com.hotatticgames.climbup.host: 0)" || fail "store APK contains $n OTA host classes"
for pkg in spi otalab module; do n=$(grep -c "Lcom/hotatticgames/climbup/$pkg/" "$W/store_classes.txt" || true); [ "${n:-0}" -eq 0 ] && pass "store APK: no climbup.$pkg classes" || fail "store APK contains $n climbup.$pkg classes"; done
# same game: every game class of the module (everything under com/hotatticgames/climbup except the module's own entry/self-test package) is in the store APK and vice versa
sed "s/.*'\(L[^']*;\)'.*/\1/" "$MODCLS" | sort -u | grep "^Lcom/hotatticgames/climbup/" | grep -v "^Lcom/hotatticgames/climbup/module/" > "$W/mod_game.txt"
grep "^Lcom/hotatticgames/climbup/" "$W/store_classes.txt" | grep -v "^Lcom/hotatticgames/climbup/android/" > "$W/store_game.txt"
echo "   game classes: module $(wc -l < "$W/mod_game.txt"), store APK $(wc -l < "$W/store_game.txt")"
if cmp -s "$W/mod_game.txt" "$W/store_game.txt"; then pass "the store APK and the module carry exactly the same game classes"; else fail "game class sets differ between store APK and module"; diff "$W/mod_game.txt" "$W/store_game.txt" | head -20; fi
echo "== permissions"
for a in "$STORE" "$HOSTAPK"; do echo "   $(basename "$a"): $("$BT/aapt2" dump permissions "$a" | grep -E "^uses-permission" | sed "s/uses-permission: name='\(.*\)'.*/\1/" | sort | tr '\n' ' ')"; done
"$BT/aapt2" dump permissions "$STORE" | grep -qE "REQUEST_INSTALL_PACKAGES|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|SYSTEM_ALERT_WINDOW" && fail "store APK declares a permission the game must not need" || pass "store APK declares no install, storage-wide or overlay permission"
echo "== package identity"
echo "   store: $("$BT/aapt2" dump badging "$STORE" | grep -m1 '^package' | cut -c1-120)"; echo "   host : $("$BT/aapt2" dump badging "$HOSTAPK" | grep -m1 '^package' | cut -c1-120)"
sp=$("$BT/aapt2" dump badging "$STORE" | grep -m1 '^package' | sed "s/.*name='\([^']*\)'.*/\1/"); hp=$("$BT/aapt2" dump badging "$HOSTAPK" | grep -m1 '^package' | sed "s/.*name='\([^']*\)'.*/\1/")
[ "$sp" != "$hp" ] && pass "the OTA host has its own application id ($hp), distinct from the game's ($sp): it cannot replace an installed game" || fail "host and store share an application id"
[ "$FAILS" -eq 0 ] && { echo "STORE GATE PASSED"; exit 0; } || { echo "$FAILS STORE GATE CHECK(S) FAILED"; exit 1; }
