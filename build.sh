#!/usr/bin/env bash
# Builds MG4 Launcher (SWI69) without Gradle: aapt2 -> javac -> D8 -> signing (v1+v2).
# Usage: ./build.sh            (version from version.properties)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
T="$ROOT/tools"
APP="$ROOT/app"
OUT="$ROOT/build"
ANDROID_JAR="$T/android.jar"
KEYSTORE="${KEYSTORE:-$ROOT/keys/platform.p12}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-platform}"

source "$ROOT/version.properties"   # VERSION_CODE, VERSION_NAME
OUT_NAME="MG4Launcher-$VERSION_NAME.apk"

# --- external tools (excluded from git): if missing, download them from the official Google sources ---
# Pinned versions with verified SHA-256 hashes. D8 8.5.35 is the version used for the APKs already published.
PLATFORM_URL="https://dl.google.com/android/repository/platform-28_r06.zip"
PLATFORM_SHA256="8452dbbf9668a428abb243c4f02a943b7aa83af3cca627629a15c4c09f28e7bd"
ANDROID_JAR_SHA256="d20853d289a5161d9cf1c1268d7923076487d4fa68c970b0733e2d5a0ccfc3c5"
R8_VERSION="8.5.35"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
R8_SHA256="4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294"
# signing library (needed only if d8.jar does not already contain it)
APKSIG_VERSION="8.5.2"
APKSIG_URL="https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/$APKSIG_VERSION/apksig-$APKSIG_VERSION.jar"
APKSIG_SHA256="c4f6fdf2148490296f422a7eb37647ea11d1c3b02f1f2349c33ce257f3c29b1f"

missing_tool() {  # $1 = file to provide, $2 = description
  echo
  echo "ERROR: cannot obtain tools/$1."
  echo "  Provide the file tools/$1 ($2) and run ./build.sh again."
  exit 1
}

hash_ok() {  # $1 = file, $2 = expected sha256
  [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ]
}

TMP_DL="$(mktemp -d)"
trap 'rm -rf "$TMP_DL"' EXIT

if [ ! -f "$T/android.jar" ]; then
  echo "==> tools/android.jar missing: downloading the Android API 28 platform (platform-28_r06)"
  if curl -fsSL --retry 3 -o "$TMP_DL/platform.zip" "$PLATFORM_URL" \
     && hash_ok "$TMP_DL/platform.zip" "$PLATFORM_SHA256" \
     && unzip -q -o "$TMP_DL/platform.zip" android-9/android.jar -d "$TMP_DL" \
     && hash_ok "$TMP_DL/android-9/android.jar" "$ANDROID_JAR_SHA256"; then
    mv "$TMP_DL/android-9/android.jar" "$T/android.jar"
    echo "    android.jar downloaded and verified"
  else
    missing_tool "android.jar" "android.jar of the Android API 28 platform, from the Android SDK"
  fi
fi

if [ ! -f "$T/d8.jar" ]; then
  echo "==> tools/d8.jar missing: downloading R8/D8 $R8_VERSION from Google Maven"
  if curl -fsSL --retry 3 -o "$TMP_DL/r8.jar" "$R8_URL" \
     && hash_ok "$TMP_DL/r8.jar" "$R8_SHA256"; then
    mv "$TMP_DL/r8.jar" "$T/d8.jar"
    echo "    d8.jar downloaded and verified"
  else
    missing_tool "d8.jar" "D8/R8 $R8_VERSION, com.android.tools:r8 from Google Maven"
  fi
fi

SIGN_CP="$T/d8.jar"
if ! unzip -l "$T/d8.jar" 'com/android/apksig/ApkSigner.class' >/dev/null 2>&1; then
  if [ ! -f "$T/apksig.jar" ]; then
    echo "==> tools/apksig.jar missing: downloading apksig $APKSIG_VERSION from Google Maven"
    if curl -fsSL --retry 3 -o "$TMP_DL/apksig.jar" "$APKSIG_URL" \
       && hash_ok "$TMP_DL/apksig.jar" "$APKSIG_SHA256"; then
      mv "$TMP_DL/apksig.jar" "$T/apksig.jar"
      echo "    apksig.jar downloaded and verified"
    else
      missing_tool "apksig.jar" "signing library com.android.tools.build:apksig from Google Maven"
    fi
  fi
  SIGN_CP="$T/d8.jar:$T/apksig.jar"
fi

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/tools"

SRC="$APP/java"
MANIFEST="$APP/AndroidManifest.xml"
RENAME=()
# built-in radio logo pack (app/assets/loghi)
ASSETS=()
if [ -d "$APP/assets" ]; then ASSETS=(-A "$APP/assets"); fi

# 1.0.4: MG4 Store bundled inside the launcher (assets/MG4Store.apk). The Store APK lives in tools/ (excluded from git,
# like the other files in tools/); another path can be given with STORE_APK=...
# InfectedM fork: the Store is OPTIONAL. Without tools/MG4Store.apk the launcher still builds, just without the Store
# (the "Install MG4 Store" button does not appear). If the file is present it must be signed with the platform key.
STORE_APK="${STORE_APK:-$T/MG4Store.apk}"
if [ ! -f "$STORE_APK" ]; then
  echo "==> MG4 Store: not found ($STORE_APK) — building without the Store"
else
  # it must be signed with the same platform key as the launcher (otherwise it cannot be installed silently)
  # (awk reads the whole output, without "exit": with pipefail a pipe closed early would stop the script)
  STORE_CERT="$(keytool -printcert -jarfile "$STORE_APK" 2>/dev/null | awk '/SHA256:/ && !f {print $2; f=1}' || true)"
  OWN_CERT="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$KEY_PASS" -alias "$KEY_ALIAS" 2>/dev/null | awk '/SHA256:/ && !f {print $2; f=1}' || true)"
  if [ -z "$STORE_CERT" ] || [ "$STORE_CERT" != "$OWN_CERT" ]; then
    echo "ERROR: MG4 Store ($STORE_APK) is not signed with the launcher's platform key."
    exit 1
  fi
  mkdir -p "$OUT/assets"
  rm -f "$OUT/assets/"*.apk
  cp "$STORE_APK" "$OUT/assets/MG4Store.apk"
  STORE_INFO="$("$T/aapt2" dump badging "$STORE_APK" 2>/dev/null | sed -n 1p || true)"
  echo "==> MG4 Store included: ${STORE_INFO} · $(stat -c %s "$STORE_APK") bytes"
  ASSETS+=(-A "$OUT/assets")
fi

echo "==> resources (aapt2)"
"$T/aapt2" compile --dir "$APP/res" -o "$OUT/res/compiled.zip"
"$T/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" "${RENAME[@]}" "${ASSETS[@]}" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res/compiled.zip"

echo "==> java (javac, bytecode 8)"
find "$SRC" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# Java 8 API from the JDK (lambdas), Android API from android.jar
if ! javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
     -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  grep -v 'Picked up JAVA_TOOL_OPTIONS' "$OUT/javac.log"
  echo "compilation failed"; exit 1
fi
grep -v -E '^(Picked up JAVA_TOOL_OPTIONS|Note: )' "$OUT/javac.log" || true

echo "==> dex (D8)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$T/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' || true
test -f "$OUT/dex/classes.dex" || { echo "D8 failed"; exit 1; }

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)

echo "==> signing"
javac -nowarn -d "$OUT/tools" -cp "$SIGN_CP" "$T/src/Sign.java" 2>&1 | grep -v 'Picked up' || true
java -cp "$OUT/tools:$SIGN_CP" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" \
  "$OUT/unsigned.apk" "$OUT/$OUT_NAME" 2>&1 | grep -v 'Picked up' || true
test -f "$OUT/$OUT_NAME"
ls -la "$OUT/$OUT_NAME"
