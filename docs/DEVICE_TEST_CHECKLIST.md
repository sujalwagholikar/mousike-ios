# Required iPhone end-to-end checks

**All items below are pending. None have been marked passed by host-side tests.**

1. Compile Debug and Release on a Mac with the selected Xcode. Resolve any Swift/Objective-C type, SDK availability, linker or packaging errors before claiming a working iOS app.
2. Run the provided XCTest and XCUITest targets; retain `.xcresult` output.
3. Sign and install on a physical ARM64 iPhone on iOS 15 if supported by the selected toolchain, and a current iPhone/iOS version. Verify the unlocked app opens without any code/account gate.
4. Verify the embedded Python module can import `ios_api`, `innertube`, `httpx`, `pytubefix`, `_ssl`, `_hashlib`, `unicodedata` and other needed native standard-library extensions. Check signatures and `.fwork` markers.
5. Search at least 10 real songs in different languages. Confirm typed music-only results; check real thumbnails and album/artist pages, including a paginated album. Record actual provider refusals rather than marking them passed.
6. Play at least 10 varied public recordings. Measure tap-to-**audible** output from cold start and prepared next-source states; distinguish network resolution, media preparation and audible output. Check no false “Playing” label during buffering.
7. Exercise rapid A→B→C selection, next, pause during resolution, resume, seek, network loss, reconnect, invalid/expired source, background/foreground and recovery. Confirm stale A/B responses cannot replace C and buffering eventually becomes playback or a readable error.
8. Import known FLAC, ALAC, PCM WAV and AIFF fixtures; verify codec/sample-rate labels and audible playback. Reject MP3/AAC disguised with a `.flac` extension or compressed WAV. Test Files/iCloud permission/copy behaviour, missing files and low storage.
9. Pause/resume/seek/next from the lock screen and headset; verify artwork and duration. Lock the phone for 15 minutes while streaming and while playing imported lossless files.
10. Test built-in speaker, wired adapter/DAC if owned, Bluetooth headphones, AirPlay and route disconnection. Incoming calls should pause correctly and resume only when permitted. Do not claim Bluetooth is lossless merely because the source file is FLAC.
11. Verify Low/High/Super high against the **actual returned format**; unavailable 256 kbps must never appear as a fabricated entitlement or transcode. Check iOS 15’s default media User-Agent and iOS 16+ public custom User-Agent option on real media hosts.
12. Confirm search-seeded related playback does not replay sequential search results, respects recent repeats and excludes long generic videos. Skips caused by network failures must not damage taste history.
13. Import/remove/like songs, relaunch, reboot and unlock; validate saved metadata, protected local-file reads and deletion. Confirm local-file deletion stops that item and does not delete the user’s original file.
14. Test SE-sized, standard and Max screens, landscape, large accessibility text, VoiceOver, Light/Dark/System and Reduce Motion. Inspect navigation safe areas and the expanded player’s scroll range.
15. Run Instruments during at least an hour of playback and scrolling: Allocations, Leaks, Hangs, Animation Hitches, Energy and Network. Verify bounded cache growth and no sustained main-thread work. Check ProMotion on supported hardware and Low Power Mode behaviour.
16. Archive, validate code signatures, audit final privacy manifests/required reason APIs and provider/distribution requirements, then export using your signing/provisioning. Only after this should the result be described as an installable, tested iPhone version.
