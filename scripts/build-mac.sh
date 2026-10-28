#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]] || ! command -v xcodebuild >/dev/null; then
  echo "A Mac with full Xcode and an installed iOS platform is required." >&2; exit 2
fi
python3 scripts/generate_project.py
mode="${1:-simulator}"
case "$mode" in
  simulator)
    xcodebuild -project Mousike.xcodeproj -scheme Mousike -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build CODE_SIGNING_ALLOWED=NO ARCHS="$(uname -m)" build
    ;;
  test)
    : "${IOS_TEST_DESTINATION:?Set IOS_TEST_DESTINATION to a simulator destination returned by xcrun simctl list devices available}"
    xcodebuild -project Mousike.xcodeproj -scheme Mousike -configuration Debug -destination "$IOS_TEST_DESTINATION" -derivedDataPath build CODE_SIGNING_ALLOWED=NO -resultBundlePath build/Mousike-Tests.xcresult test
    ;;
  archive)
    : "${APPLE_TEAM_ID:?Set APPLE_TEAM_ID to your own Apple development team ID}"
    xcodebuild -project Mousike.xcodeproj -scheme Mousike -configuration Release -destination 'generic/platform=iOS' -archivePath build/Mousike.xcarchive DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -allowProvisioningUpdates archive
    ;;
  export)
    : "${EXPORT_OPTIONS_PLIST:?Set EXPORT_OPTIONS_PLIST to your signing/distribution options plist exported by Xcode}"
    xcodebuild -exportArchive -archivePath build/Mousike.xcarchive -exportPath build/export -exportOptionsPlist "$EXPORT_OPTIONS_PLIST" -allowProvisioningUpdates
    ;;
  *) echo 'Usage: bash scripts/build-mac.sh simulator|test|archive|export' >&2; exit 2 ;;
esac
