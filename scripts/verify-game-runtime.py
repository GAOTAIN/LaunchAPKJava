"""Inspect the built APK and pinned libraries. Does not claim device/game success."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
REPORTS = ROOT / 'build-reports'
TOOLS = ROOT / '.android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
scratch = ROOT / '.tmp/game-runtime-audit'
assert scratch.resolve().is_relative_to(ROOT)
scratch.mkdir(parents=True, exist_ok=True)

def elf(data):
    assert data[:5] == b'\x7fELF\x02', 'Expected ELF64'
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    segments = [struct.unpack_from('<IIQQQQQQ', data, header[5] + index * header[9]) for index in range(header[10])]
    return dict(machine=header[2], loadAlignment=[segment[7] for segment in segments if segment[0] == 1])

result = dict(apk=str(APK), sha256=hashlib.sha256(APK.read_bytes()).hexdigest(),
              deviceVerified=False, mainMenuVerified=False, limitation='Pinned LWJGL/JNA JNI requires 4KB device pages', libraries={})
try:
    with zipfile.ZipFile(APK) as archive:
        for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
            for library in ['launchapk_bridge', 'glfw', 'gl4es', 'openal', 'lwjgl', 'lwjgl_opengl', 'lwjgl_stb', 'lwjgl_tinyfd', 'jnidispatch']:
                path = 'lib/' + abi + '/lib' + library + '.so'
                data = archive.read(path)
                info = elf(data)
                assert info['machine'] == machine
                if library in ['launchapk_bridge', 'glfw', 'gl4es', 'openal']:
                    assert min(info['loadAlignment']) >= 16384, path
                result['libraries'][path] = info
            glfw = scratch / ('libglfw-' + abi + '.so')
            glfw.write_bytes(archive.read('lib/' + abi + '/libglfw.so'))
            names = subprocess.check_output([str(TOOLS / 'llvm-nm.exe'), '--dynamic', '--defined-only', str(glfw)])
            exports = set(re.findall(rb'glfw[A-Za-z]+', names))
            with zipfile.ZipFile(ROOT / 'launcher/src/main/assets/game-runtime/lwjgl-glfw-3.3.3.jar') as java:
                required = set(re.findall(rb'glfw[A-Z][A-Za-z]+', java.read('org/lwjgl/glfw/GLFW$Functions.class')))
            assert not required - exports, sorted(required - exports)
            result[abi + '-glfwResolvedFunctions'] = len(required)
        for line in archive.read('assets/game-runtime/hashes.properties').decode().splitlines():
            name, expected = line.split('=', 1)
            data = archive.read('assets/game-runtime/' + name)
            assert hashlib.sha256(data).hexdigest() == expected
        bootstrap = scratch / 'bootstrap.jar'
        bootstrap.write_bytes(archive.read('assets/jvm/launchapk-smoke.jar'))
        with zipfile.ZipFile(bootstrap) as java:
            own = java.read('com/launchapk/java/launcher/game/GameMain.class')
            assert struct.unpack_from('>H', own, 6)[0] == 52
            assert not any(name.startswith('net/minecraft/') for name in java.namelist())
        assert 'assets/licenses/openal-soft-COPYING' in archive.namelist()
        assert 'assets/licenses/jna-AL2.0' in archive.namelist()
    aapt = ROOT / '.android-sdk/build-tools/35.0.0/aapt2.exe'
    manifest = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(APK), '--file', 'AndroidManifest.xml']).decode()
    assert 'com.launchapk.java' in manifest and 'launchchpk' not in manifest
    assert ':game' in manifest and 'GameActivity' in manifest
    assert 'SYSTEM_ALERT_WINDOW' not in manifest
    result['gameActivityProcess'] = ':game'
    REPORTS.mkdir(exist_ok=True)
    tests = []; skipped = 0
    for path in ROOT.glob('*/build/test-results/test*UnitTest/TEST-*.xml'):
        suite = ET.parse(path).getroot()
        assert int(suite.attrib.get('failures', 0)) == 0 and int(suite.attrib.get('errors', 0)) == 0
        skipped += int(suite.attrib.get('skipped', 0))
        tests.extend((case.attrib.get('classname'), case.attrib.get('name')) for case in suite.findall('testcase'))
    result['unitTests'] = dict(unique=len(set(tests)), variantRecords=len(tests), failures=0, errors=0, skipped=skipped)
    assert tests and skipped == 0
    # Accompany the LGPL library with the exact library and original application build material.
    # This archive does not choose a distribution license for the application's own code.
    paths = set()
    for name in ['README.md', 'THIRD_PARTY_NOTICES.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew.bat', '.gitignore']:
        paths.add(ROOT / name)
    for name in ['app', 'auth', 'core', 'input', 'launcher', 'nativebridge', 'renderer', 'ui']:
        paths.add(ROOT / name / 'build.gradle.kts')
        for child in ['src', 'third_party']:
            directory = ROOT / name / child
            if directory.is_dir(): paths.update(path for path in directory.rglob('*') if path.is_file())
    for name in ['docs', 'scripts', 'gradle']:
        paths.update(path for path in (ROOT / name).rglob('*') if path.is_file())
    bundle = REPORTS / 'game-runtime-sources.zip'
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(paths):
            if not path.is_file() or '__pycache__' in path.parts: continue
            assert path.resolve().is_relative_to(ROOT)
            name = path.relative_to(ROOT).as_posix()
            entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, path.read_bytes())
    result['sourceArchive'] = dict(path=str(bundle), sha256=hashlib.sha256(bundle.read_bytes()).hexdigest(), files=len(paths))
    (REPORTS / 'game-runtime-verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key != 'libraries'}, indent=2))
finally:
    assert scratch.resolve().is_relative_to(ROOT) and not scratch.is_symlink()
    shutil.rmtree(scratch)
    temporary = ROOT / '.tmp'
    if temporary.is_dir() and not any(temporary.iterdir()): temporary.rmdir()
