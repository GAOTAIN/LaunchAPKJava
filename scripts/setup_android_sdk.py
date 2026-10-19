"""Install an official, project-local Android SDK; never change global environment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SDK = ROOT / ".android-sdk"
WORK = ROOT / ".tmp" / "sdk-setup"
REPOSITORY = "https://dl.google.com/android/repository/repository2-1.xml"
TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
TOOLS_SHA1 = "54a582f3bf73e04253602f2d1c80bd5868aac115"
PACKAGES = ["platforms;android-35", "build-tools;35.0.0", "platform-tools"]


def check_local(path):
    resolved = path.resolve()
    if not resolved.is_relative_to(ROOT) or resolved == ROOT:
        raise RuntimeError(f"Refusing path outside repository: {path}")
    return resolved


def ready():
    return all((SDK / relative).is_file() for relative in [
        "platforms/android-35/android.jar", "build-tools/35.0.0/aapt2.exe",
        "build-tools/35.0.0/apksigner.bat", "platform-tools/adb.exe",
        "cmdline-tools/19.0/bin/sdkmanager.bat",
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-licenses", action="store_true",
                        help="Accept the Android SDK license required by the selected stable packages")
    args = parser.parse_args()
    if ready():
        print(f"SDK already installed: {SDK}", flush=True)
        return
    if not args.accept_licenses:
        parser.error("Review https://developer.android.com/studio/terms and pass --accept-licenses to install")
    if SDK.exists():
        raise RuntimeError(f"Incomplete SDK already exists; inspect it before retrying: {SDK}")
    check_local(WORK)
    if WORK.exists():
        raise RuntimeError(f"Previous setup files exist; inspect before retrying: {WORK}")
    WORK.mkdir(parents=True)
    stage = WORK / "android-sdk"
    archive = WORK / "commandline-tools.zip"
    try:
        print("Downloading official command-line tools 19.0...", flush=True)
        digest = hashlib.sha1()
        sha256 = hashlib.sha256()
        with urllib.request.urlopen(TOOLS_URL, timeout=60) as response, archive.open("wb") as output:
            while data := response.read(1024 * 1024):
                output.write(data)
                digest.update(data)
                sha256.update(data)
        if digest.hexdigest() != TOOLS_SHA1:
            raise RuntimeError("Official command-line tools checksum mismatch")
        print(f"Verified SHA-1; recorded SHA-256: {sha256.hexdigest()}", flush=True)
        tools = stage / "cmdline-tools" / "19.0"
        with zipfile.ZipFile(archive) as zipped:
            for item in zipped.infolist():
                relative = Path(item.filename)
                if relative.parts[0] != "cmdline-tools":
                    raise RuntimeError(f"Unexpected ZIP entry: {item.filename}")
                target = tools.joinpath(*relative.parts[1:])
                check_local(target)
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with zipped.open(item) as source, target.open("wb") as output:
                        shutil.copyfileobj(source, output)
        # Record the exact required license from official repository metadata.
        with urllib.request.urlopen(REPOSITORY, timeout=40) as response:
            repository = ET.fromstring(response.read())
        sdk_license = next(item for item in repository.findall("license")
                           if item.attrib.get("id") == "android-sdk-license")
        license_text = sdk_license.text or ""
        if not license_text.strip():
            raise RuntimeError("Official repository license text was empty")
        (stage / "ANDROID-SDK-LICENSE.txt").write_text(license_text, encoding="utf-8")
        java_home = ROOT / ".build-home"
        java_home.mkdir(exist_ok=True)
        android_home = ROOT / ".android"
        android_home.mkdir(exist_ok=True)
        env = os.environ.copy()
        env.update({"ANDROID_HOME": str(stage), "ANDROID_USER_HOME": str(android_home),
                    "TEMP": str(WORK), "TMP": str(WORK)})
        # sdkmanager and its child JVMs must not fall back to the OS user profile/temp.
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") +
            f' -XX:-UsePerfData "-Duser.home={java_home}" "-Djava.io.tmpdir={WORK}"')
        manager = tools / "bin" / "sdkmanager.bat"
        print("Installing only the selected stable packages; accepting their required SDK license...", flush=True)
        # Shell script is the official sdkmanager; all package names are fixed, not user input.
        process = subprocess.run([str(manager), f"--sdk_root={stage}", "--channel=0", *PACKAGES],
                                 input="y\n" * 8, text=True, env=env)
        if process.returncode != 0:
            raise RuntimeError(f"sdkmanager failed: exit {process.returncode}")
        for relative in ["platforms/android-35/android.jar", "build-tools/35.0.0/aapt2.exe", "platform-tools/adb.exe"]:
            if not (stage / relative).is_file():
                raise RuntimeError(f"SDK component missing after installation: {relative}")
        receipt = {"command_line_tools": "19.0", "source": TOOLS_URL,
                   "official_sha1": TOOLS_SHA1, "download_sha256": sha256.hexdigest(),
                   "packages": PACKAGES, "license": "android-sdk-license",
                   "license_source": REPOSITORY}
        (stage / "installation-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        check_local(stage)
        check_local(SDK)
        stage.rename(SDK)
        print(f"SDK installed: {SDK}", flush=True)
    finally:
        check_local(WORK)
        if WORK.exists():
            shutil.rmtree(WORK)
        if WORK.parent.exists() and not any(WORK.parent.iterdir()):
            WORK.parent.rmdir()


if __name__ == "__main__":
    main()
