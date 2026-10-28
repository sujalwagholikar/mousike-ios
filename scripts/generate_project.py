#!/usr/bin/env python3
"""Deterministic Xcode project generator; no Ruby, CocoaPods or XcodeGen."""
from pathlib import Path
import hashlib
import json
import plistlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
objects = {}
def uid(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
def obj(identifier, isa, **fields):
    key = uid(identifier); objects[key] = dict(isa=isa, **fields); return key
def file(path, kind):
    return obj('file:' + path, 'PBXFileReference', lastKnownFileType=kind, path=path, sourceTree='SOURCE_ROOT')
def build(name, ref, **kwargs):
    return obj('build:' + name, 'PBXBuildFile', fileRef=ref, **kwargs)
def phase(name, isa, files):
    return obj(name, isa, buildActionMask=2147483647, files=files, runOnlyForDeploymentPostprocessing=0)
def config_list(name, settings):
    refs=[]
    for variant in ['Debug', 'Release']:
        options=dict(settings)
        options.update(SWIFT_OPTIMIZATION_LEVEL='-Onone' if variant=='Debug' else '-O', SWIFT_COMPILATION_MODE='singlefile' if variant=='Debug' else 'wholemodule')
        refs.append(obj(name+variant,'XCBuildConfiguration',name=variant,buildSettings=options))
    return obj(name+'configs','XCConfigurationList',buildConfigurations=refs,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')

swift=list(sorted(ROOT.glob('App/**/*.swift')))
objc=list(sorted(ROOT.glob('App/**/*.m')))
source_refs=[file(str(p.relative_to(ROOT)),'sourcecode.swift' if p.suffix=='.swift' else 'sourcecode.c.objc') for p in swift+objc]
header_refs=[file(str(p.relative_to(ROOT)),'sourcecode.c.h') for p in sorted(ROOT.glob('App/**/*.h'))]
source_phase=phase('app sources','PBXSourcesBuildPhase',[build('source:'+r,r) for r in source_refs])
resources=[file('App/Resources/Assets.xcassets','folder.assetcatalog'),file('SharedPython','folder'),file('App/Resources/PrivacyInfo.xcprivacy','text.xml'),file('App/Resources/FontLicenses','folder')]
resources += [file(str(p.relative_to(ROOT)),'file') for p in sorted(ROOT.glob('App/Resources/Fonts/*.ttf'))]
resource_phase=phase('app resources','PBXResourcesBuildPhase',[build('resource:'+r,r) for r in resources])
python=file('Vendor/Python/Python.xcframework','wrapper.xcframework')
framework_phase=phase('app frameworks','PBXFrameworksBuildPhase',[build('link python',python)])
embed_phase=obj('embed python','PBXCopyFilesBuildPhase',buildActionMask=2147483647,dstPath='',dstSubfolderSpec=10,name='Embed Python framework',files=[build('embed python lib',python,settings={'ATTRIBUTES':['CodeSignOnCopy','RemoveHeadersOnCopy']})],runOnlyForDeploymentPostprocessing=0)
python_phase=obj('install python','PBXShellScriptBuildPhase',name='Install bundled Python and sign extensions',buildActionMask=2147483647,files=[],inputPaths=[],outputPaths=[],runOnlyForDeploymentPostprocessing=0,shellPath='/bin/bash',shellScript='set -euo pipefail\n/usr/bin/python3 "$PROJECT_DIR/scripts/install_python.py"\n',alwaysOutOfDate=1)
product=obj('app product','PBXFileReference',explicitFileType='wrapper.application',includeInIndex=0,path='Mousike.app',sourceTree='BUILT_PRODUCTS_DIR')
project_settings={'IPHONEOS_DEPLOYMENT_TARGET':'15.0','SDKROOT':'iphoneos','SWIFT_VERSION':'5.0','CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','ENABLE_USER_SCRIPT_SANDBOXING':'NO','TARGETED_DEVICE_FAMILY':'1','ONLY_ACTIVE_ARCH':'YES','GCC_C_LANGUAGE_STANDARD':'gnu11','ENABLE_TESTABILITY':'YES','SWIFT_STRICT_CONCURRENCY':'minimal'}
app_settings={'PRODUCT_BUNDLE_IDENTIFIER':'com.sujalwagholikar.mousike.ios','PRODUCT_NAME':'Mousike','INFOPLIST_FILE':'App/Info.plist','SWIFT_OBJC_BRIDGING_HEADER':'App/Bridge/Bridging.h','HEADER_SEARCH_PATHS':['$(inherited)','$(BUILT_PRODUCTS_DIR)/Python.framework/Headers'],'FRAMEWORK_SEARCH_PATHS':['$(inherited)','$(PROJECT_DIR)/Vendor/Python'],'LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks'],'ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','CODE_SIGN_STYLE':'Automatic','CURRENT_PROJECT_VERSION':'1','MARKETING_VERSION':'3.9.1','GENERATE_INFOPLIST_FILE':'NO','ENABLE_BITCODE':'NO'}
app_target=obj('app target','PBXNativeTarget',name='Mousike',productName='Mousike',productReference=product,productType='com.apple.product-type.application',buildConfigurationList=config_list('app',app_settings),buildPhases=[source_phase,framework_phase,resource_phase,python_phase,embed_phase],buildRules=[],dependencies=[])
test_refs=[]; test_products=[]; targets=[app_target]
for label,path,kind in [('MousikeTests','Tests/MousikeTests.swift','com.apple.product-type.bundle.unit-test'),('MousikeUITests','Tests/MousikeUITests.swift','com.apple.product-type.bundle.ui-testing')]:
    ref=file(path,'sourcecode.swift');test_refs.append(ref)
    test_product=obj(label+' product','PBXFileReference',explicitFileType='wrapper.cfbundle',includeInIndex=0,path=label+'.xctest',sourceTree='BUILT_PRODUCTS_DIR'); test_products.append(test_product)
    proxy=obj(label+' proxy','PBXContainerItemProxy',containerPortal=uid('project'),proxyType=1,remoteGlobalIDString=app_target,remoteInfo='Mousike')
    dep=obj(label+' dependency','PBXTargetDependency',target=app_target,targetProxy=proxy)
    settings={'PRODUCT_BUNDLE_IDENTIFIER':'com.sujalwagholikar.mousike.ios.'+label,'PRODUCT_NAME':label,'GENERATE_INFOPLIST_FILE':'YES','CODE_SIGN_STYLE':'Automatic'}
    if label=='MousikeTests':settings.update(TEST_HOST='$(BUILT_PRODUCTS_DIR)/Mousike.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/Mousike',BUNDLE_LOADER='$(TEST_HOST)')
    else:settings['TEST_TARGET_NAME']='Mousike'
    target=obj(label+' target','PBXNativeTarget',name=label,productName=label,productReference=test_product,productType=kind,buildConfigurationList=config_list(label,settings),buildPhases=[phase(label+' sources','PBXSourcesBuildPhase',[build(label+' swift',ref)]),phase(label+' frameworks','PBXFrameworksBuildPhase',[])],buildRules=[],dependencies=[dep]); targets.append(target)
main=obj('main group','PBXGroup',children=[obj('source group','PBXGroup',name='App',children=source_refs+header_refs,sourceTree='<group>'),obj('resource group','PBXGroup',name='Resources',children=resources+[file('App/Info.plist','text.plist.xml')],sourceTree='<group>'),python,obj('test group','PBXGroup',name='Tests',children=test_refs,sourceTree='<group>'),obj('products group','PBXGroup',name='Products',children=[product]+test_products,sourceTree='<group>')],sourceTree='<group>')
obj('project','PBXProject',attributes={'LastUpgradeCheck':'1600','BuildIndependentTargetsInParallel':'YES'},buildConfigurationList=config_list('project',project_settings),compatibilityVersion='Xcode 14.0',developmentRegion='en',hasScannedForEncodings=0,knownRegions=['en','Base'],mainGroup=main,productRefGroup=uid('products group'),projectDirPath='',projectRoot='',targets=targets)
def serialize(value,indent=0):
    tab='\t'*indent
    if isinstance(value,dict):return '{\n'+''.join('\t'*(indent+1)+json.dumps(k)+' = '+serialize(v,indent+1)+';\n' for k,v in value.items())+tab+'}'
    if isinstance(value,list):return '(\n'+''.join('\t'*(indent+1)+serialize(v,indent+1)+',\n' for v in value)+tab+')'
    if isinstance(value,int):return str(value)
    return json.dumps(str(value),ensure_ascii=False)
project=ROOT/'Mousike.xcodeproj';project.mkdir(exist_ok=True)
(project/'project.pbxproj').write_text('// !$*UTF8*$!\n'+serialize({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':objects,'rootObject':uid('project')})+'\n')
scheme=ET.Element('Scheme',LastUpgradeVersion='1600',version='1.3')
def reference(parent, target, buildable, name):
    return ET.SubElement(parent,'BuildableReference',BuildableIdentifier='primary',BlueprintIdentifier=target,BuildableName=buildable,BlueprintName=name,ReferencedContainer='container:Mousike.xcodeproj')
action=ET.SubElement(scheme,'BuildAction',parallelizeBuildables='YES',buildImplicitDependencies='YES'); entries=ET.SubElement(action,'BuildActionEntries')
entry=ET.SubElement(entries,'BuildActionEntry',buildForTesting='YES',buildForRunning='YES',buildForProfiling='YES',buildForArchiving='YES',buildForAnalyzing='YES');reference(entry,app_target,'Mousike.app','Mousike')
test=ET.SubElement(scheme,'TestAction',buildConfiguration='Debug',selectedDebuggerIdentifier='Xcode.DebuggerFoundation.Debugger.LLDB',selectedLauncherIdentifier='Xcode.IDEFoundation.Launcher.LLDB',shouldUseLaunchSchemeArgsEnv='YES'); testables=ET.SubElement(test,'Testables')
for target,label in zip(targets[1:],['MousikeTests','MousikeUITests']):reference(ET.SubElement(testables,'TestableReference',skipped='NO'),target,label+'.xctest',label)
launch=ET.SubElement(scheme,'LaunchAction',buildConfiguration='Debug',selectedDebuggerIdentifier='Xcode.DebuggerFoundation.Debugger.LLDB',selectedLauncherIdentifier='Xcode.IDEFoundation.Launcher.LLDB',launchStyle='0',useCustomWorkingDirectory='NO',ignoresPersistentStateOnLaunch='NO',debugDocumentVersioning='YES',debugServiceExtension='internal',allowLocationSimulation='YES');reference(ET.SubElement(launch,'BuildableProductRunnable',runnableDebuggingMode='0'),app_target,'Mousike.app','Mousike')
ET.SubElement(scheme,'ProfileAction',buildConfiguration='Release',shouldUseLaunchSchemeArgsEnv='YES',savedToolIdentifier='',useCustomWorkingDirectory='NO',debugDocumentVersioning='YES')
ET.SubElement(scheme,'AnalyzeAction',buildConfiguration='Debug'); ET.SubElement(scheme,'ArchiveAction',buildConfiguration='Release',revealArchiveInOrganizer='YES')
scheme_path=project/'xcshareddata/xcschemes';scheme_path.mkdir(parents=True,exist_ok=True);ET.indent(scheme)
ET.ElementTree(scheme).write(scheme_path/'Mousike.xcscheme',encoding='UTF-8',xml_declaration=True)
# Machine-readable graph for validation without pretending to run xcodebuild.
(ROOT/'docs/project-graph.json').write_text(json.dumps(objects,indent=2))
print('Generated Mousike.xcodeproj with app, unit and UI test targets')
