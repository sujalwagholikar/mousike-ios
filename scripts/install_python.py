#!/usr/bin/env python3
"""Xcode build phase: copy selected stdlib slice; package signed .fwork modules.

Follows CPython 3.13's iOS layout. Does not install code at application runtime.
"""
from pathlib import Path
import os
import plistlib
import shutil
import subprocess

def install(env=os.environ):
    root=Path(env['PROJECT_DIR']); app=Path(env['CODESIGNING_FOLDER_PATH'])
    framework=root/'Vendor/Python/Python.xcframework'
    simulator=env['PLATFORM_NAME']=='iphonesimulator'
    slice_name='ios-arm64_x86_64-simulator' if simulator else 'ios-arm64'
    arch=env.get('CURRENT_ARCH','')
    if arch not in ('arm64','x86_64'):arch=env.get('ARCHS','arm64').split()[0]
    if not simulator:arch='arm64'
    common=framework/'lib'; specific=framework/slice_name/('lib-'+arch)
    if not specific.is_dir(): raise SystemExit('Missing bundled Python slice '+str(specific))
    lib=app/'python/lib'
    if lib.exists():shutil.rmtree(lib)
    shutil.copytree(common,lib,ignore=shutil.ignore_patterns('libpython*.dylib','__pycache__','test','idlelib','tkinter','turtledemo','ensurepip'))
    shutil.copytree(specific,lib,dirs_exist_ok=True,ignore=shutil.ignore_patterns('libpython*.dylib','__pycache__'))
    modules=lib/'python3.13/lib-dynload'; destination=app/'Frameworks';destination.mkdir(parents=True,exist_ok=True)
    # Remove old generated module frameworks so incremental builds don't retain stale code.
    for old in destination.glob('*.framework'):
        if old.name != 'Python.framework' and any(old.glob('*.origin')):shutil.rmtree(old)
    for extension in sorted(modules.glob('*.so')):
        name=extension.name.split('.')[0]
        if name.startswith(('xx','_test')):extension.unlink();continue
        folder=destination/(name+'.framework');folder.mkdir(exist_ok=True)
        binary=folder/name;shutil.move(extension,binary)
        relative=str(extension.relative_to(app)).removesuffix('.so')+'.fwork'
        (app/relative).write_text('Frameworks/'+folder.name+'/'+name+'\n')
        (folder/(name+'.origin')).write_text(relative+'\n')
        info={'CFBundleExecutable':name,'CFBundleIdentifier':env['PRODUCT_BUNDLE_IDENTIFIER']+'.'+name.replace('_','-'),'CFBundleName':name,'CFBundlePackageType':'FMWK','CFBundleShortVersionString':'3.13','CFBundleVersion':'1','MinimumOSVersion':env.get('IPHONEOS_DEPLOYMENT_TARGET','15.0'),'CFBundleSupportedPlatforms':['iPhoneSimulator' if simulator else 'iPhoneOS']}
        with (folder/'Info.plist').open('wb') as f:plistlib.dump(info,f)
        privacy=extension.with_name(name+'.xcprivacy')
        if privacy.exists():shutil.copy2(privacy,folder/'PrivacyInfo.xcprivacy')
        identity=env.get('EXPANDED_CODE_SIGN_IDENTITY') or '-'
        subprocess.run(['/usr/bin/codesign','--force','--sign',identity,'--timestamp=none',str(folder)],check=True)
    # The app never writes bytecode into its signed bundle.
    for cache in lib.rglob('__pycache__'):shutil.rmtree(cache)
    print('Bundled Python 3.13 stdlib installed for',slice_name,arch)
if __name__=='__main__':install()
