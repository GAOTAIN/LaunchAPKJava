"""Fetch pinned, separately licensed runtime dependencies inside this repository only."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
assert (ROOT / 'settings.gradle.kts').is_file(), 'Run from the original project scripts directory'
receipts = []
lock = ROOT / 'nativebridge/third_party/game-runtime-lock.json'
previous = {item['url']: item['sha256'] for item in json.loads(lock.read_text())} if lock.exists() else {}

def fetch(url, expected=None):
    data = urllib.request.urlopen(url, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    expected = expected or previous.get(url)
    if expected and digest != expected:
        raise ValueError("Dependency SHA-256 mismatch: " + url)
    receipts.append(dict(url=url, sha256=digest, size=len(data)))
    return data

def write(relative, data):
    target = ROOT / relative
    assert target.resolve().is_relative_to(ROOT)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)

fcl = 'https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/cd14d179ce22a806c9eda6353836e5abfa93cf30/'
aar = fetch(fcl + 'FCL/libs/lwjgl-3.3.3-natives-release.aar', 'ec26f2008e5c4b73605d5f401e8f3df8f4e0eb74d2e66fbba7d349a1f530552f')
with zipfile.ZipFile(io.BytesIO(aar)) as archive:
    for abi in ['arm64-v8a', 'x86_64']:
        for name in ['liblwjgl.so', 'liblwjgl_opengl.so', 'liblwjgl_stb.so', 'liblwjgl_tinyfd.so']:
            entry = 'assets/app_runtime/lwjgl/3.3.3/natives/' + abi + '/' + name
            write('nativebridge/src/main/jniLibs/' + abi + '/' + name, archive.read(entry))
    for entry in archive.namelist():
        if entry.startswith('assets/licenses/') and not entry.endswith('/'):
            write('nativebridge/src/main/' + entry, archive.read(entry))

for module in ['lwjgl', 'lwjgl-glfw', 'lwjgl-opengl', 'lwjgl-openal', 'lwjgl-stb', 'lwjgl-tinyfd']:
    url = 'https://repo.maven.apache.org/maven2/org/lwjgl/{0}/3.3.3/{0}-3.3.3.jar'.format(module)
    data = fetch(url)
    # Verify the repository's published digest in addition to recording SHA-256.
    published = urllib.request.urlopen(url + '.sha1').read().decode().strip()
    assert hashlib.sha1(data).hexdigest() == published
    write('launcher/src/main/assets/game-runtime/' + module + '-3.3.3.jar', data)

# JNA's desktop Linux libjnidispatch is glibc, not Android. Use the official Android AAR.
jna = fetch('https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar')
with zipfile.ZipFile(io.BytesIO(jna)) as archive:
    write('launcher/src/main/assets/game-runtime/jna-5.13.0.jar', archive.read('classes.jar'))
    for abi in ['arm64-v8a', 'x86_64']:
        write('nativebridge/src/main/jniLibs/' + abi + '/libjnidispatch.so', archive.read('jni/' + abi + '/libjnidispatch.so'))
write('nativebridge/src/main/assets/licenses/jna-LICENSE', fetch('https://raw.githubusercontent.com/java-native-access/jna/5.13.0/LICENSE'))
for name in ['AL2.0', 'native/libffi/LICENSE', 'native/libffi/LICENSE-BUILDTOOLS']:
    write('nativebridge/src/main/assets/licenses/jna-' + name.replace('/', '-'), fetch('https://raw.githubusercontent.com/java-native-access/jna/5.13.0/' + name))
write('nativebridge/src/main/assets/licenses/stb-image-notice.h', fetch('https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.3/modules/lwjgl/stb/src/main/c/stb_image.h'))

glfw = 'https://raw.githubusercontent.com/glfw/glfw/7482de6071d21db77a7236155da44c172a7f6c9e/'
write('nativebridge/third_party/glfw/include/GLFW/glfw3.h', fetch(glfw + 'include/GLFW/glfw3.h'))
write('nativebridge/src/main/assets/licenses/glfw-header-LICENSE.md', fetch(glfw + 'LICENSE.md'))

openal = fetch('https://codeload.github.com/kcat/openal-soft/zip/d3875f333fb6abe2f39d82caca329414871ae53b')
with zipfile.ZipFile(io.BytesIO(openal)) as archive:
    for entry in archive.infolist():
        if not entry.is_dir():
            relative = entry.filename.split('/', 1)[1]
            write('nativebridge/third_party/openal-soft/' + relative, archive.read(entry))
    write('nativebridge/src/main/assets/licenses/openal-soft-COPYING', archive.read(archive.namelist()[0] + 'COPYING'))
    write('nativebridge/src/main/assets/licenses/openal-soft-BSD-3Clause', archive.read(archive.namelist()[0] + 'BSD-3Clause'))

write('nativebridge/third_party/game-runtime-lock.json', (json.dumps(receipts, indent=2) + '\n').encode())
directory = ROOT / 'launcher/src/main/assets/game-runtime'
write('launcher/src/main/assets/game-runtime/hashes.properties', ('\n'.join(file.name + '=' + hashlib.sha256(file.read_bytes()).hexdigest()
    for file in sorted(directory.glob('*.jar'))) + '\n').encode('ascii'))
print('Pinned runtime dependencies ready:', len(receipts))
