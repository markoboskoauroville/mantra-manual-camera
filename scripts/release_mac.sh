#!/bin/zsh
# release_mac.sh — build Mantra Manual Camera AND Mantra Monitor on this Mac and release both in one GitHub release,
# marked Latest (Marko, 26.10.2026: "We are not using GitHub Actions to build the APK. It's built locally.";
# "On the same latest page, the latest version should be both updates").
#
#   scripts/release_mac.sh            build, check, release v<appVersion>, install the camera on the Pixel 7
#   scripts/release_mac.sh --no-release   build and check only
#
# The same gates as .github/workflows/build.yml: NDI runtime and bridge inside both APKs, signed with the permanent
# key (SIGNING_FINGERPRINT.txt), the version above the last release, the three numbers agreeing.
# The keystore password is read from ~/Developer/mantra-ndi-sdk/mantra-ndi.p12.password into the environment of
# the build only; it is never printed.
set -euo pipefail
cd "${0:A:h}/.."
REPO=markoboskoauroville/mantra-manual-camera
SDK=~/Library/Android/sdk
BT=$(ls -d $SDK/build-tools/* | sort -V | tail -1)
V=$(sed -n 's/^appVersion=//p' gradle.properties)
fail() { print -u2 "FAIL  $*"; exit 1; }

# the NDI SDK (gitignored) from the private SDK folder, as the workflow copies it
NDI=~/Developer/mantra-ndi-sdk
mkdir -p app/src/main/cpp/ndi/include app/src/main/jniLibs/arm64-v8a
cp $NDI/include/*.h app/src/main/cpp/ndi/include/
cp $NDI/lib/arm64-v8a/libndi_advanced.so app/src/main/jniLibs/arm64-v8a/
if [ -d monitor/src/main/jniLibs ]; then mkdir -p monitor/src/main/jniLibs/arm64-v8a; cp $NDI/lib/arm64-v8a/libndi_advanced.so monitor/src/main/jniLibs/arm64-v8a/; fi

LAST=$(gh release list -R $REPO --limit 100 --json tagName --jq '.[].tagName' | grep -E '^v[0-9]+$' | sed 's/^v//' | sort -n | tail -1)
[ -z "$LAST" ] || [ "$V" -gt "$LAST" ] || fail "appVersion is $V but v$LAST is already released: raise gradle.properties"

export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$SDK
SIGNING_PASSWORD=$(tr -d '\r\n' < $NDI/mantra-ndi.p12.password) gradle --offline -q assembleDebug

EXPECTED=$(tr -d ' \n\r' < SIGNING_FINGERPRINT.txt)
check() {   # apk name
  local apk=$1 name=$2
  local list=$(unzip -l "$apk")    # read whole: grep -q on a pipe would cut unzip off and pipefail calls it a failure
  [[ $list == *libndi_advanced.so* ]] || fail "$name: NDI runtime missing"
  [[ $list == *libndi_bridge.so* ]] || fail "$name: JNI bridge missing"
  local fp=$($BT/apksigner verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: \([0-9a-f]*\).*/\1/p' | head -1)
  [ "$fp" = "$EXPECTED" ] || fail "$name: signed with another key ($fp)"
  local code=$($BT/aapt2 dump badging "$apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
  [ "$code" = "$V" ] || fail "$name: version $code, expected $V"
  print "PASS  $name: NDI inside, the permanent key, version $V"
}
OUT=build/release; rm -rf $OUT; mkdir -p $OUT
CAM=$OUT/$V-mantra-manual-camera-v$V.apk
MON=$OUT/$V-mantra-monitor-v$V.apk
cp app/build/outputs/apk/debug/app-debug.apk $CAM
cp monitor/build/outputs/apk/debug/monitor-debug.apk $MON
check $CAM "Mantra Manual Camera"
check $MON "Mantra Monitor"
[ "${1:-}" = "--no-release" ] && { print "built, not released: $CAM $MON"; exit 0; }

gh release delete "v$V" -R $REPO --yes --cleanup-tag 2>/dev/null || true
gh release create "v$V" $CAM $MON -R $REPO --latest --title "v$V" --notes "Mantra Manual Camera v$V and its sister app Mantra Monitor v$V, built on the Mac. Both signed with the permanent key, so each updates over any earlier build carrying the same key.

Certificate: $EXPECTED

Powered by NDI. NDI is a registered trademark of Vizrt NDI AB."
print "RELEASED v$V (Latest): both APKs"

# versioning.md §4: keep the last two releases
gh release list -R $REPO --limit 100 --json tagName --jq '.[].tagName' | grep -E '^v[0-9]+$' | sort -t v -k2 -n -r | tail -n +3 \
  | while read -r old; do gh release delete "$old" -R $REPO --yes --cleanup-tag 2>/dev/null && print "removed $old"; done

# the camera onto the Pixel 7 (the remote goes onto its own phone when that phone is on adb)
ADB=$SDK/platform-tools/adb
if [[ $($ADB devices) == *"41241FDH2S10SJ"*device* ]]; then $ADB -s 41241FDH2S10SJ install -r $CAM | tail -1; fi
