"""Download pinned runtime parts inside .tmp for the explicit JVM integration check; never bundle them."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import urllib.request

root = Path(__file__).resolve().parent.parent
catalog = json.loads((root / 'launcher/src/main/assets/runtime-catalog.json').read_text())
directory = root / '.tmp/runtime-catalog'
directory.mkdir(parents=True, exist_ok=True)
parts = {(spec['major'], part['url']): part for spec in catalog['packages'] for part in spec['archives']}

def verify(item):
    (major, url), part = item
    filename = str(major) + '-' + url.rsplit('/', 1)[1]
    target = directory / filename
    assert target.resolve().is_relative_to(root)
    if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != part['sha256']:
        data = urllib.request.urlopen(url, timeout=90).read(part['size'] + 1)
        assert len(data) == part['size'] and hashlib.sha256(data).hexdigest() == part['sha256'], filename
        target.write_bytes(data)
    assert target.stat().st_size == part['size']
    print('Verified ' + filename, flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(verify, parts.items()))
