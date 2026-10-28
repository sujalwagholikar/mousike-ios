#!/bin/bash
set -euo pipefail
mkdir -p build/logs
xcrun simctl list devices available -j > build/simulators.json
DESTINATION=$(python3 - <<'PYSIM'
import json
root=json.load(open('build/simulators.json'))['devices']
d=next(d for group in root.values() for d in group if d.get('isAvailable') and d['name'].startswith('iPhone'))
print('platform=iOS Simulator,id='+d['udid'])
PYSIM
)
xcodebuild -project Mousike.xcodeproj -scheme Mousike -configuration Debug -destination "$DESTINATION" -derivedDataPath build/DerivedData -resultBundlePath build/NativeTests.xcresult CODE_SIGNING_ALLOWED=NO test 2>&1 | tee build/logs/simulator.log
