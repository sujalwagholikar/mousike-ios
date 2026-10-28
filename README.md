# Codemagic build edition

Start with [CODEMAGIC_SETUP.md](CODEMAGIC_SETUP.md). Four manual workflows support simulator testing, unsigned device packaging, signed Ad Hoc IPA and signed App Store export. **These workflows have not been executed in Codemagic here.** Native build success and device playback remain unverified.

# Mousiké for iPhone

**Unlocked iOS port · 3.9.1 source preview · developed and owned by Sujal Wagholikar**

This is a native iPhone adaptation of the latest available **classic, non-Premier Android 3.9.0 source**. It adds local lossless audio support without Premier activation, an access-code screen, a Gemini key or an app account.

> **Release status: not yet compiled or verified on iOS.** This ZIP is a source/build package, **not an installable IPA**. The environment used to create it runs Linux and has no Xcode, Apple signing identity, simulator or physical iPhone. Host-side checks passed; native compilation, signing, device playback and performance remain unverified. Do not distribute it as a tested iPhone release.

## What is included

- Native SwiftUI Home, Search, album/artist browse, Library and organised Settings.
- Native AVPlayer expanded/mini player, queue, seek, likes, source-quality label and compact actual-output name with the system AirPlay route picker.
- Background audio capability and Apple Now Playing/lock-screen/headset commands.
- FLAC, Apple Lossless/ALAC and PCM WAV/AIFF import through Files. The native importer checks the **actual compressed file format**, not the filename, before calling it lossless.
- Three online source choices: Low, High, Super high. High considers available AAC up to 256 kbps; Super high selects the highest supplied Apple-compatible AAC source. Neither is advertised as lossless.
- Contextual radio after search; completed listens, likes and followed artists influence subsequent ordering and taste mixes. Recent repeats and long non-music videos are filtered.
- Taste mixes build a visible collection without starting music automatically.
- Coral accents, square foreground artwork without cropping, optional full player background, bundled Manrope/Inter/Roboto typography, Light/Dark/System appearance and reduced-motion support.
- Bounds on API metadata, artwork memory and collection size; one speculative next-source preparation on unconstrained inexpensive networks, disabled in Low Power Mode.
- Developer portrait and Instagram/GitHub/website links.

The Python API backend and vendored low-level pytubefix files are **byte-for-byte copies** of classic Android 3.9.0. See `docs/ANDROID_CORE_SHA256.json`. There is no yt-dlp, subprocess, remote Python service or runtime executable-code download.

**Platform-specific pieces necessarily change.** Android Compose, Chaquopy, Media3, Android services and audio effects do not run on iOS. SwiftUI, embedded CPython and AVFoundation replace them. The original Kotlin source is included under `Reference/Android-3.9.0` for audit and further porting; it is not secretly compiled into the iOS app. See the explicit feature/status matrix below and `docs/PORTING_STATUS.md`.

## Compatibility target

| Item | Target / status |
|---|---|
| Phone OS | iOS **15.0 or later**, ARM64 |
| Older phones | An iPhone that cannot run iOS 15 is unsupported; universal compatibility with every iPhone is not claimed |
| Simulator | Bundled ARM64 and x86_64 Python slices; use a simulator supported by your installed Xcode |
| Device tests | **Not run** |
| ProMotion | App permits normal system high-refresh rendering and respects Reduce Motion; no forced 120 Hz guarantee |
| Bluetooth/AirPlay | Native system routing controls are implemented, but physical routing has not been tested |
| Lossless | Actual imported FLAC/ALAC/PCM sources; not a promise of bit-perfect output over Bluetooth or every DAC |
| Online availability | Depends on the anonymous YouTube source response, region, network and playable supplied formats |

## Build on a Mac

You need **full Xcode**, its iOS platform and a Mac supported by that Xcode. Command Line Tools alone are insufficient. Runtime, Python dependencies, fonts and icon assets are already bundled; no Python package installation is required for a normal Xcode build.

1. Unzip using macOS Archive Utility or `unzip` so the bundled framework symlinks are preserved.
2. Open `Mousike.xcodeproj` in Xcode.
3. Select the **Mousike** target → **Signing & Capabilities**, choose **your** development team, and use an available bundle identifier if needed.
4. Select an installed iPhone simulator or your connected iPhone.
5. Build and run. On supported iOS versions, Xcode may ask you to enable Developer Mode on the phone.
6. Run the native tests, then complete the physical-device checklist before archiving a release.

Command-line simulator build:

```bash
cd Mousike-iOS
bash scripts/build-mac.sh simulator
```

Run native unit/UI tests on a simulator you actually have installed:

```bash
xcrun simctl list devices available
export IOS_TEST_DESTINATION='platform=iOS Simulator,id=YOUR_SIMULATOR_UDID'
bash scripts/build-mac.sh test
```

Archive for your development team:

```bash
export APPLE_TEAM_ID='YOUR_TEAM_ID'
bash scripts/build-mac.sh archive
```

Export a correctly signed IPA with the distribution options appropriate to **your** Apple account and provisioning:

```bash
export EXPORT_OPTIONS_PLIST='/absolute/path/to/your/ExportOptions.plist'
bash scripts/build-mac.sh export
```

Alternatively use Xcode Organizer → Distribute App. A source ZIP cannot be installed directly on an iPhone; a signed device build, TestFlight or another Apple-supported distribution method is required. There are **no bundled signing credentials**.

## Tests actually performed here

**42 host Python checks passed**: 34 backend/adaptation tests and 8 packaging tests. The packaging test that simulates framework installation mocks `codesign`; it does **not** certify actual signing.

All **12 Swift files** passed a tree-sitter syntax parse. This is **not** Swift type checking, SDK availability checking or an Xcode build. Native Swift unit/UI tests are provided, but have **not run**.

A real host-network probe was attempted. Search timed out. A source resolved to **AAC at 138 kbps**, but its media request returned **195 bytes of HTML** rather than a valid media header. Therefore live audible playback is **not verified**. The host uses a network proxy, unlike the native iOS transport; this result does not establish whether a physical iPhone succeeds or fails. Exact results are in `docs/LIVE_HOST_PROBE.json`.

Re-run host checks:

```bash
python3 scripts/generate_project.py
python3 -m unittest discover -s Tests -p 'test_*.py' -v
```

Optional live host probe:

```bash
python3 scripts/probe_live.py
```

The probe makes genuine provider requests and is not deterministic. The production app uses NSURLSession with normal Apple TLS and request cancellation; host HTTPX/proxy behaviour is different.

## Layout

```text
Mousike.xcodeproj/             Ready-generated app / unit / UI test targets
App/
  Core/                       Models, app feed, library and AVPlayer adapter
  Bridge/                     CPython embedding + pooled bounded NSURLSession transport
  UI/                         SwiftUI screens, artwork, player and Settings
  Resources/                  Filled logo, icons, portrait, fonts and privacy manifest
SharedPython/
  mousike_backend.py          Original classic Android backend, unchanged
  pytubefix/                  Original vendored low-level source, unchanged
  ios_api.py                  iOS boundary and Apple-compatible source policy
  packages/                   Pinned pure-Python dependencies and their notices
Vendor/Python/                Verified CPython 3.13 iOS XCFramework + stdlib slices
Reference/Android-3.9.0/       Original Kotlin reference; not an iOS runtime
Tests/                        Host backend / packaging tests + native XCTest sources
scripts/                      Project generation, stdlib packaging and Mac build commands
docs/                         Honest status, architecture, validation and device checklist
```

## Settings and privacy

Settings and listening history live in the app container. Artwork downloads and anonymous catalog/player requests necessarily go to YouTube/Google hosts. Search text and selected song IDs are sent to the catalog provider; do not interpret “no account” as “no provider network traffic.” No analytics SDK or cross-device/cloud account is added.

Imported audio copies are protected until first device unlock after boot, excluded from automatic backup, and explicitly removable from the song menu. Each import is limited to 50 files, each file to 512 MiB, and the library to 250 local files. Artwork is capped at 24 MiB decoded memory; the original backend API metadata cache is at most 8 entries / 3 MiB / 90 seconds. Clear cache does not delete imported songs.

The privacy manifest is a starting declaration for the implemented uses, **not an App Store review approval**. Before publishing, audit all SDK/runtime required-reason APIs and provider/network disclosures against the final binary and your distribution rules.

## Porting limits

This first source port does **not** claim full Android feature parity. Android EQ/device profile APIs, Gemini integrations, online file downloading, chapter-split long-video albums, the full Kotlin recommendation controller, manual transfer and Windows live sync are **not wired into this iOS app**. Ordinary albums browse into track lists, and typed long-form recommendations are filtered. See `docs/PORTING_STATUS.md` for the exact differences.

No guaranteed startup time, entitlement to higher provider bitrates, App Store acceptance, bit-perfect playback or all-iPhone compatibility is claimed.

## Owner and links

**Sujal Wagholikar** · [Instagram](https://www.instagram.com/sujalwagholikar/) · [GitHub](https://github.com/sujalwagholikar) · [Mousiké website](https://sujalwagholikar.github.io/mousike/)

First-party code: © 2026 Sujal Wagholikar. Third-party components retain their original licences. See `THIRD_PARTY_NOTICES.md` and the bundled notices. Manrope/Inter/Roboto font outlines and weights are retained; their internal name records were adjusted to distinct `Mousike…` names to prevent the source fonts’ duplicate PostScript names from colliding during iOS registration.
