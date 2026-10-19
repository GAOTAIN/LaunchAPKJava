"""Convert a user-supplied Android JRE tar.xz into an importable ZIP, preserving licenses.
Both input and output must stay inside this project. No download or JRE redistribution occurs.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, posixpath, shutil, tarfile, zipfile
ROOT=Path(__file__).resolve().parent.parent

def local(value):
    path=Path(value).resolve()
    if not path.is_relative_to(ROOT.resolve()): raise ValueError("All files must stay inside the workspace")
    return path

def safe_name(value):
    if not value or value.startswith("/") or "\\" in value or ":" in value: raise ValueError("Unsafe archive path")
    normalized=posixpath.normpath(value)
    if normalized==".." or normalized.startswith("../"): raise ValueError("Unsafe archive path")
    return normalized

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source");parser.add_argument("output")
    args=parser.parse_args();source=local(args.source);output=local(args.output)
    if output.exists(): raise ValueError("Output already exists; choose a new filename")
    output.parent.mkdir(parents=True,exist_ok=True)
    try:
        with tarfile.open(source,"r:*") as tar, zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as zipped:
            members={safe_name(item.name):item for item in tar.getmembers() if item.name not in (".","./")}
            for name,item in members.items():
                if item.isdir(): continue
                target=item;visited=set()
                while target.issym() or target.islnk():
                    if target.name in visited: raise ValueError("Cyclic JRE archive link")
                    visited.add(target.name)
                    link=target.linkname
                    if target.issym(): link=posixpath.join(str(PurePosixPath(target.name).parent),link)
                    target=members[safe_name(link)]
                if not target.isfile(): raise ValueError("Unsupported JRE link/device entry: "+name)
                with tar.extractfile(target) as data, zipped.open(name,"w") as destination:
                    shutil.copyfileobj(data,destination,1024*1024)
        digest=hashlib.sha256()
        with output.open("rb") as data:
            while block:=data.read(1024*1024): digest.update(block)
        print("ZIP:",output);print("SHA-256:",digest.hexdigest())
    except BaseException:
        if output.exists(): output.unlink()
        raise
if __name__=="__main__":main()
