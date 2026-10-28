from pathlib import Path
import json,os,subprocess,sys
root=Path(__file__).resolve().parents[2]
os.chdir(root)
if sys.version_info < (3,10): raise SystemExit("Select Python 3.13 in the CI environment; host dependency checks need Python >=3.10")
for rel,target in json.loads((root/'docs/runtime-symlinks.json').read_text()).items():
    p=root/rel
    resolved=(p.parent/target).resolve()
    if not resolved.is_relative_to(root.resolve()): raise SystemExit('Unsafe runtime link')
    if p.is_symlink(): continue
    if p.exists():
        if not p.is_file() or p.read_text().strip()!=target: raise SystemExit('Unexpected content at '+rel)
        p.unlink()
    p.symlink_to(target)
subprocess.run([sys.executable,'scripts/generate_project.py'],check=True)
subprocess.run([sys.executable,'-m','unittest','discover','-s','Tests','-p','test_*.py','-v'],check=True)
