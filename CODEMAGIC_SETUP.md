# Build Mousiké on Codemagic from Windows

This edition supplies complete source, bundled CPython device/simulator runtime, pinned Python dependencies, native resources and `codemagic.yaml` at repository root. No app unlock code is required. The streaming Python core is unchanged.

**Status:** Codemagic configuration prepared and host checks performed; no Codemagic account/build has been accessed here. Swift/Objective-C compilation, native tests, export and iPhone playback are still unverified. Existing source-port feature limitations remain in `docs/PORTING_STATUS.md`. This update does not turn the source preview into a certified production release.

## 1. Update your existing repository

Extract the new ZIP and copy its contents into your existing local repository folder, replacing matching files. Preserve your existing `.git` directory. Ensure `codemagic.yaml`, `App`, `Vendor`, `SharedPython` and `Mousike.xcodeproj` are directly in the repository root, not nested inside another Mousike-iOS folder.

In Windows Command Prompt, inside your existing repository:

```bat
git add --renormalize .
git add .
git commit -m "Configure native iOS builds and IPA export for Codemagic"
git push origin main
```

The default app bundle identifier is `com.sujalwagholikar.mousike.ios`. If you choose another, change it in **both** `scripts/generate_project.py` and each signed workflow's `ios_signing.bundle_identifier` in `codemagic.yaml`. The generator is authoritative; editing only the generated Xcode project is not enough.

## 2. Connect Codemagic

1. Sign in at https://codemagic.io/ and connect your GitHub account.
2. Add/select `sujalwagholikar/mousike-ios` as a **native iOS / codemagic.yaml** project. UI labels can change; select YAML-based configuration rather than a Flutter setup.
3. Select branch `main`. Codemagic should discover the root `codemagic.yaml`.
4. Select a workflow below and start a manual build. All four workflows use a macOS M2 machine, the available latest Xcode and Python 3.13. They do not require CocoaPods or Flutter.
5. Open the actual script logs if a build fails. The previous GitHub screenshot showed a failed run but no error log, so its exact cause has not been diagnosed or claimed fixed.

## 3. Choose the correct workflow

| Workflow ID | Output if successful | Signing needed | Installation |
|---|---|---|---|
| `ios-simulator` | Native unit/UI test result bundle and logs | No Apple distribution credentials | Simulator checks only; no iPhone IPA |
| `ios-unsigned` | `Mousike-UNSIGNED.ipa` and warning text | No distribution credentials | **Not directly installable**; needs valid later signing/provisioning |
| `ios-adhoc` | Signed exported `.ipa` | Apple distribution certificate + matching Ad Hoc profile | Only devices registered in that profile |
| `ios-appstore` | App Store-signed exported `.ipa` | Apple distribution certificate + matching App Store profile | Distribution through App Store Connect/TestFlight; not a direct arbitrary-device installer |

Start with simulator validation. For a directly installable test build on your registered iPhone, use **ios-adhoc** after configuring signing. An unsigned device archive is deliberately named UNSIGNED; successful ZIP packaging does not make it installable.

## 4. Configure signing for a signed IPA

A signed device workflow requires suitable Apple Developer Program access and signing resources. Keep private keys/certificates in Codemagic's signing settings, never in GitHub or this source ZIP.

1. Register your chosen App ID/bundle identifier in your Apple developer account.
2. For Ad Hoc, register your iPhone's UDID and include it in a matching Ad Hoc provisioning profile.
3. In Codemagic **Team settings → codemagic.yaml settings → Code signing identities**, add the Apple distribution certificate with its private key (`.p12` and its password) and matching `.mobileprovision` profile. Alternatively use Codemagic's documented Apple Developer integration to generate/fetch suitable resources, then follow its signing identity instructions.
4. Ensure the profile's team, certificate, bundle identifier, distribution type and expiration match. Uploading an App Store profile does not satisfy an Ad Hoc workflow.
5. Run `ios-adhoc` or `ios-appstore`. Codemagic's `environment.ios_signing` loads resources by distribution type and bundle identifier; the script runs `xcode-project use-profiles` **after** project generation, then `xcode-project build-ipa --project Mousike.xcodeproj --scheme Mousike`.
6. Download the resulting `.ipa` from the build's **Artifacts** section. Logs are retained in `build/logs` and Codemagic's Xcode log location. The pipeline fails if export produces no IPA, if Payload structure is invalid, if the app has no embedded profile, or if strict signature verification fails.

No publishing stanza or App Store upload is configured. The App Store workflow exports only. TestFlight/App Store publication requires your separate setup/review and authorization.

## 5. What changed

- Four Codemagic workflows with explicit output purposes.
- Build preparation restores three known Python runtime symlinks if a Windows extraction/checkout represents them as text. Unexpected file contents or links outside the project fail instead of being overwritten.
- `.gitattributes` preserves LF for shell, Python, YAML and native source files.
- Python 3.13 explicitly selected for host checks, including the existing GitHub workflow. This avoids depending on an old system Python; it is not a diagnosis of the unseen GitHub failure.
- Project generation precedes signing profile application. No generator runs afterward to discard Codemagic's signing changes.
- Native simulator workflow selects an actually installed iPhone simulator and runs XCTest/XCUITest, retaining `.xcresult`.
- Unsigned packaging builds the actual ARM64 device target and checks the executable exists before creating `Payload/Mousike.app`.
- Signed export uses Codemagic's native Xcode tooling and verifies the resulting IPA structure/signature.
- All application source, backend, resources and original reference code remain included.

## 6. Troubleshooting

| Failure | Next action |
|---|---|
| `No matching profiles found` | Add a non-expired profile for the exact bundle ID and selected distribution type; ensure its certificate/private key is available |
| Certificate/private key missing | Add a `.p12` containing the matching private key through Codemagic signing identities |
| Xcode compilation/linker error | Open the first real `error:` in the retained native log; source has not previously been compiled on Apple SDKs |
| Python framework/header missing | Check `Vendor/Python`, restored links and the `Install bundled Python` phase log; do not remove runtime slices |
| Old Python/dependency error | Confirm workflow environment selects Python 3.13 and uses `python3` from that environment |
| Generated project lost signing edits | Do not run `generate_project.py` after `xcode-project use-profiles` |
| Unsigned IPA cannot install | Expected: use signed Ad Hoc for registered devices or your legitimate re-signing/provisioning process |
| App Store IPA does not install by tapping | Expected: use its supported distribution path; choose Ad Hoc for direct registered-device testing |
| Build succeeds but playback fails | Follow `docs/DEVICE_TEST_CHECKLIST.md`; compilation/signing does not verify YouTube availability or audible playback |

## Official research references

- Native iOS: https://docs.codemagic.io/yaml-quick-start/building-a-native-ios-app/
- Signing identities and distribution types: https://docs.codemagic.io/yaml-code-signing/signing-ios/
- YAML configuration/artifacts: https://docs.codemagic.io/yaml-basic-configuration/yaml-getting-started/
- Simulator builds: https://docs.codemagic.io/yaml-code-signing/ios-simulator-builds/
- Signing failures: https://docs.codemagic.io/troubleshooting/common-ios-issues/

Owned and developed by **Sujal Wagholikar**. App access remains unlocked. Apple platform signing is separate from app activation.
