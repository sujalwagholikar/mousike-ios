import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('installer',ROOT/'scripts/install_python.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)

class PackagingTests(unittest.TestCase):
    def test_device_and_simulator_runtime_slices_present(self):
        root=ROOT/'Vendor/Python/Python.xcframework'
        for slice_name in ['ios-arm64','ios-arm64_x86_64-simulator']:
            self.assertTrue((root/slice_name/'Python.framework/Python').is_file())
        with (root/'Info.plist').open('rb') as f:info=plistlib.load(f)
        self.assertEqual(len(info['AvailableLibraries']),2)
    def test_backend_dependencies_are_pure_python(self):
        self.assertFalse(list((ROOT/'SharedPython/packages').rglob('*.so')))
    def test_native_background_capability_without_auth_gate(self):
        with (ROOT/'App/Info.plist').open('rb') as f:info=plistlib.load(f)
        self.assertEqual(info['CFBundleDisplayName'],'Mousiké');self.assertIn('audio',info['UIBackgroundModes'])
        app='\n'.join(p.read_text() for p in (ROOT/'App').rglob('*.swift'))
        self.assertNotIn('MOUSIKEPRO25',app);self.assertNotIn('PremierGate',app)
    def test_registered_font_files_exist(self):
        with (ROOT/'App/Info.plist').open('rb') as f:info=plistlib.load(f)
        for name in info['UIAppFonts']:self.assertTrue((ROOT/'App/Resources/Fonts'/name).is_file())
        self.assertEqual(len(info['UIAppFonts']),6)
    def test_all_project_source_references_exist(self):
        graph=json.loads((ROOT/'docs/project-graph.json').read_text())
        for node in graph.values():
            if node.get('isa')=='PBXFileReference' and node.get('sourceTree')=='SOURCE_ROOT':
                self.assertTrue((ROOT/node['path']).exists(),node['path'])
    def test_all_project_object_relationships_resolve(self):
        graph=json.loads((ROOT/'docs/project-graph.json').read_text())
        for node in graph.values():
            for key in ['fileRef','buildConfigurationList','mainGroup','productRefGroup','productReference','target','targetProxy','containerPortal']:
                if key in node:self.assertIn(node[key],graph)
            for key in ['children','files','buildConfigurations','targets','buildPhases','dependencies']:
                for ref in node.get(key,[]):self.assertIn(ref,graph)
    def test_selected_stdlib_and_signed_framework_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);vendor=root/'Vendor/Python/Python.xcframework'
            (vendor/'lib/python3.13/encodings').mkdir(parents=True)
            (vendor/'lib/python3.13/encodings/__init__.py').write_text('# common')
            for arch in ['arm64','x86_64']:
                lib=vendor/'ios-arm64_x86_64-simulator'/('lib-'+arch)/'python3.13/lib-dynload';lib.mkdir(parents=True)
                (lib/'math.cpython-313-iphonesimulator.so').write_bytes(arch.encode())
            app=root/'Test.app';app.mkdir()
            env={'PROJECT_DIR':str(root),'CODESIGNING_FOLDER_PATH':str(app),'PLATFORM_NAME':'iphonesimulator','CURRENT_ARCH':'x86_64','PRODUCT_BUNDLE_IDENTIFIER':'com.test.mousike','IPHONEOS_DEPLOYMENT_TARGET':'15.0'}
            with patch.object(installer.subprocess,'run') as sign:
                installer.install(env)
                self.assertTrue(sign.called)
                self.assertEqual((app/'Frameworks/math.framework/math').read_bytes(),b'x86_64')
                marker=app/'python/lib/python3.13/lib-dynload/math.cpython-313-iphonesimulator.fwork'
                self.assertEqual(marker.read_text().strip(),'Frameworks/math.framework/math')
                self.assertTrue((app/'python/lib/python3.13/encodings/__init__.py').exists())
                self.assertFalse(list((app/'python').rglob('*.so')))
    def test_1024_icon_is_opaque_rgb_and_correct_dimensions(self):
        import struct
        path=ROOT/'App/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024-1.png'
        data=path.read_bytes();self.assertEqual(data[:8],b'\x89PNG\r\n\x1a\n')
        width,height,depth,kind=struct.unpack('>IIBB',data[16:26])
        self.assertEqual((width,height,kind),(1024,1024,2))

if __name__=='__main__':unittest.main()
