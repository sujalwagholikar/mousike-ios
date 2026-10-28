from pathlib import Path
import plistlib,subprocess,zipfile,tempfile
files=list(Path('build/ios/ipa').glob('*.ipa'))
if not files: raise SystemExit('No exported IPA found; see export.log')
for ipa in files:
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(ipa) as z:
            if z.testzip(): raise SystemExit('IPA CRC failure')
            z.extractall(tmp)
        apps=list((Path(tmp)/'Payload').glob('*.app'))
        if len(apps)!=1: raise SystemExit('Expected one app in Payload')
        app=apps[0]
        if not (app/'embedded.mobileprovision').is_file(): raise SystemExit('Missing provisioning profile')
        subprocess.run(['codesign','--verify','--deep','--strict',str(app)],check=True)
        with (app/'Info.plist').open('rb') as f:info=plistlib.load(f)
        print('Verified exported IPA structure/signature:',ipa.name,info['CFBundleIdentifier'])
print('This verifies packaging and signing, not audible playback or App Store acceptance.')
