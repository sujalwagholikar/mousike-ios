#!/bin/bash
set -euo pipefail
mkdir -p build/logs build/ios/ipa
xcodebuild -project Mousike.xcodeproj -scheme Mousike -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build 2>&1 | tee build/logs/unsigned-device.log
test -f build/DerivedData/Build/Products/Release-iphoneos/Mousike.app/Mousike
mkdir -p build/unsigned/Payload
ditto build/DerivedData/Build/Products/Release-iphoneos/Mousike.app build/unsigned/Payload/Mousike.app
(cd build/unsigned && ditto -c -k --keepParent Payload ../ios/ipa/Mousike-UNSIGNED.ipa)
echo 'Unsigned device package only. It cannot be installed until validly signed/provisioned.' > build/ios/ipa/UNSIGNED-READ-ME.txt
