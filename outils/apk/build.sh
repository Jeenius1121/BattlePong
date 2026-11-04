#!/usr/bin/env bash
# Fabrique l'APK de Battle Pong (sans Gradle) : bash outils/apk/build.sh <dossier build-tools> <android.jar> <keystore> <mot de passe> <sortie.apk>
# Le jeu (index.html, version.json, perso/) est rangé dans l'application ; versionCode et versionName viennent de version.json.
set -euo pipefail
BT="$1"; AJ="$2"; KS="$3"; MDP="$4"; SORTIE="$5"
ICI="$(cd "$(dirname "$0")" && pwd)"; RACINE="$(cd "$ICI/../.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
VER=$(python3 -c "import json;print(json.load(open('$RACINE/version.json'))['version'])")
CODE=$(python3 -c "a=[int(x) for x in '$VER'.split('.')]+[0,0];print(a[0]*10000+a[1]*100+a[2])")
mkdir -p "$T/assets/jeu" "$T/classes" "$T/gen"
cp "$RACINE/index.html" "$RACINE/version.json" "$T/assets/jeu/"; cp -r "$RACINE/perso" "$T/assets/jeu/"
"$BT/aapt2" compile --dir "$ICI/res" -o "$T/res.zip"
"$BT/aapt2" link -I "$AJ" --manifest "$ICI/AndroidManifest.xml" -A "$T/assets" -o "$T/base.apk" --java "$T/gen" \
  --version-code "$CODE" --version-name "$VER" --min-sdk-version 24 --target-sdk-version 34 "$T/res.zip"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$AJ" -classpath "$AJ" -d "$T/classes" $(find "$ICI/src" "$T/gen" -name '*.java') 2>&1 | grep -v "^warning\|^Note\|^1 warning" || true
(cd "$T/classes" && jar cf "$T/classes.jar" .)
"$BT/d8" --min-api 24 --lib "$AJ" --output "$T" "$T/classes.jar"
(cd "$T" && zip -q -j base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$T/base.apk" "$T/aligne.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$MDP" --out "$SORTIE" "$T/aligne.apk"
echo "APK $VER ($CODE) : $SORTIE"
