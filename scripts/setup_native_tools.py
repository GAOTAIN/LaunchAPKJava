"""Install pinned official NDK/CMake tools inside the existing project-local SDK."""
from pathlib import Path
import hashlib, json, shutil, urllib.request, zipfile
ROOT = Path(__file__).resolve().parent.parent
SDK = ROOT / ".android-sdk"
WORK = ROOT / ".tmp/native-setup"
PACKAGES = [
    ("ndk/27.2.12479018", "android-ndk-r27c-windows.zip", "ac5f7762764b1f15341094e148ad4f847d050c38", True),
    ("cmake/3.22.1", "cmake-3.22.1-windows.zip", "292778f32a7d5183e1c49c7897b870653f2d2c1b", False),
]
def checked(path):
    if not path.resolve().is_relative_to(ROOT.resolve()): raise RuntimeError("Path outside workspace")
    return path

def main():
    WORK.mkdir(parents=True,exist_ok=True)
    receipt_file=ROOT/"build-reports/native-tools-installation.json"
    receipts=json.loads(receipt_file.read_text(encoding="utf-8")) if receipt_file.exists() else []
    try:
        for destination, filename, expected, strip_root in PACKAGES:
            target=checked(SDK/destination)
            required = "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe" if strip_root else "bin/cmake.exe"
            if (target/"source.properties").is_file() and (target/required).is_file():
                print("Already installed:",destination,flush=True); continue
            if target.exists(): raise RuntimeError("Inspect incomplete destination: "+str(target))
            archive=checked(WORK/filename)
            url="https://dl.google.com/android/repository/"+filename
            if not archive.exists():
                print("Downloading",url,flush=True)
                with urllib.request.urlopen(url,timeout=120) as source, archive.open("wb") as output:
                    shutil.copyfileobj(source,output,1024*1024)
            sha1=hashlib.sha1(); sha256=hashlib.sha256()
            with archive.open("rb") as source:
                while block:=source.read(1024*1024): sha1.update(block); sha256.update(block)
            if sha1.hexdigest()!=expected: raise RuntimeError("Official checksum mismatch: "+filename)
            print("Verified",filename,"; extracting",flush=True)
            stage=checked(WORK/destination.replace("/","-")); stage.mkdir()
            with zipfile.ZipFile(archive) as zipped:
                for item in zipped.infolist():
                    parts=Path(item.filename).parts[1:] if strip_root else Path(item.filename).parts
                    if not parts: continue
                    path=checked(stage.joinpath(*parts))
                    if not path.resolve().is_relative_to(stage.resolve()): raise RuntimeError("Unsafe archive entry")
                    if item.is_dir(): path.mkdir(parents=True,exist_ok=True)
                    else:
                        path.parent.mkdir(parents=True,exist_ok=True)
                        with zipped.open(item) as source,path.open("wb") as output: shutil.copyfileobj(source,output)
            if not (stage/"source.properties").is_file(): raise RuntimeError("Missing SDK metadata")
            target.parent.mkdir(parents=True,exist_ok=True)
            try:
                stage.rename(target)
            except PermissionError:
                # Windows scanners can hold open a freshly extracted directory; copy into the new fixed destination.
                shutil.copytree(stage,target)
            receipts.append(dict(package=destination,url=url,sha1=expected,sha256=sha256.hexdigest()))
            print("Installed",destination,flush=True)
        (ROOT/"build-reports/native-tools-installation.json").write_text(json.dumps(receipts,indent=2)+"\n",encoding="utf-8")
    finally:
        checked(WORK)
        if WORK.exists() and not WORK.is_symlink(): shutil.rmtree(WORK)
        if WORK.parent.exists() and not any(WORK.parent.iterdir()): WORK.parent.rmdir()
if __name__=="__main__": main()
