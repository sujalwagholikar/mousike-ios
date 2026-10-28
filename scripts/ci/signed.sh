#!/bin/bash
set -euo pipefail
mkdir -p build/logs
# prepare.py must run BEFORE use-profiles; regenerating afterward discards signing edits.
xcode-project use-profiles 2>&1 | tee build/logs/signing.log
xcode-project build-ipa --project Mousike.xcodeproj --scheme Mousike 2>&1 | tee build/logs/export.log
python3 scripts/ci/verify_ipa.py
