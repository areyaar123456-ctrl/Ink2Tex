#!/usr/bin/env python3
"""Audit registry invariants and optionally verify already downloaded local artifacts."""
import argparse, hashlib, json, pathlib, re, sys
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--files',type=pathlib.Path);args=parser.parse_args()
catalog=json.loads((root/'app/src/main/assets/models.json').read_text())
assert re.fullmatch('[0-9a-f]{40}',catalog['runtimeCommit'])
for model in catalog['models']:
    components=model['components']
    if not model['runtime']:
        assert not components,model['id']
        continue
    assert len(components)==2 and components[1]['name'].startswith('mmproj-')
    for item in components:
        assert re.fullmatch('[A-Za-z0-9._-]+',item['name'])
        assert re.fullmatch('[0-9a-f]{64}',item['sha256'])
        assert re.search(r'https://huggingface.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/',item['url'])
        assert item['size']>0
        if args.files:
            file=args.files/item['name']
            if not file.exists():
                print('Not downloaded:',file.name);continue
            digest=hashlib.file_digest(file.open('rb'),'sha256').hexdigest()
            assert file.stat().st_size==item['size'] and digest==item['sha256'],file
            print('Verified:',file.name,digest)
print('Registry invariants passed:',len(catalog['models']),'entries')
