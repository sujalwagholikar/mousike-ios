# Porting status — not an iPhone release certification

Baseline: classic, unlocked Android 3.9.0, not Premier 1.1. The request’s added lossless requirement is implemented as native local-file import. This source port uses an iOS-only version 3.9.1 and does not modify existing Android installations.

| Area | Source implementation | Verification / difference |
|---|---|---|
| InnerTube / pytubefix API | Original Android Python files copied unchanged | SHA256 compared; host unit tests passed; no audible-device proof |
| Android network bridge | Replaced by pooled NSURLSession Python extension | New Objective-C implementation; uncompiled on Apple SDK |
| Python runtime | Bundled CPython 3.13, device and simulator slices | Upstream archive SHA256 verified; not launched on iOS |
| Online format selection | Apple AAC/MP4 and HLS, original-language audio | New Python adapter; 34 backend tests; Opus/WebM excluded for broad deployment compatibility |
| Audio playback | Native AVPlayer / AVAudioSession | Necessary replacement for Android Media3; no claim of binary-identical engine |
| Song start and buffering | Real AVPlayer timeControlStatus, stale-selection generation IDs, source pre-resolution, one bounded recovery | Implemented; physical timing and audible recovery unverified |
| Lossless | Real-format checked FLAC, ALAC, PCM import | Implemented against native APIs; physical decode/output unverified |
| Background and lock screen | Background audio mode, Now Playing metadata, headset/lock-screen remote commands | Source present; native tests pending |
| Output device | Actual AVAudioSession output names, AirPlay picker, system volume | Source present; physical Bluetooth/AirPlay pending |
| Home / Search | Music-only typed catalog parsing, bounded pages, debounced search | Host parser/policy tests; native UI tests pending |
| Albums | Browse and up to four continuation pages; open without autoplay | Host pagination tests; native UI pending |
| Artists | Browse and follow; original provider images | Source present; live search timed out on build host |
| Likes / taste | Local likes, completion events, recent-repeat suppression, artist preference ordering | A smaller iOS policy, not the full Kotlin session algorithm |
| Taste collection | Query derived from liked/completed/followed artists; opens a song list | Implemented without Gemini or an automatic-play action |
| Appearance | Coral, square foreground covers, full background toggle, fonts, theme, motion | Native view source only, not screenshot-verified |
| Onboarding | Simple optional genre choices, no access code | Source and no-auth packaging checks; native UI test not run |
| Artwork | Decode thumbnails to ≤720 px; 80 objects / 24 MiB cache | Bounds present; no physical memory profile |
| API metadata | Original 8 entries / 3 MiB / 90 sec cache; 4 MiB transport response cap | Original core retained, native bridge not executed |
| Online downloading | **Not ported** | Local Files import is implemented; there is no online Download button falsely claiming this feature |
| Long-video chapter albums | **Not ported** | Reference Kotlin included; typed songs exceeding 12 minutes/compilations are excluded from automatic discovery |
| Full Android queue controller | **Not ported** | Contextual native radio and local taste ordering are implemented separately |
| Android EQ / output profiles | **Not ported** | System output selection and volume only; no simulated EQ or B&O switch |
| Gemini / semantic AI | **Not ported** | No API-key field with a non-working implementation |
| Windows live continuity | **Not ported** | Premier-only Connect is outside this classic baseline adaptation |
| Signed IPA | **Not produced** | Requires Mac/Xcode, signing/provisioning and device validation |
| iPhone end-to-end pass | **Not achieved** | No physical phone or simulator is available in the build environment |

This is a usable body of source for continuing the iOS port on a Mac, not a claim that every Android feature now runs unchanged on every iPhone.
