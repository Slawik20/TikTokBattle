#!/usr/bin/env python3
"""Validate all CustomModelData mappings and resource-pack dependencies."""
import json
from pathlib import Path
root=Path(__file__).resolve().parent
pack=root/'resourcepack'
assert json.loads((pack/'pack.mcmeta').read_text())['pack']['pack_format']==15
item=json.loads((pack/'assets/minecraft/models/item/carrot_on_a_stick.json').read_text())
expected=set(range(8101,8112))
seen=set()
for entry in item['overrides']:
    number=entry['predicate']['custom_model_data']
    assert number not in seen, f'Duplicate model ID {number}'
    seen.add(number)
    namespace,path=entry['model'].split(':',1)
    model_file=pack/'assets'/namespace/'models'/f'{path}.json'
    assert model_file.is_file(), f'Missing model {model_file}'
    model=json.loads(model_file.read_text())
    for texture in model.get('textures',{}).values():
        if texture.startswith('#'): continue
        ns,rel=texture.split(':',1)
        texture_file=pack/'assets'/ns/'textures'/f'{rel}.png'
        assert texture_file.is_file(), f'Missing texture {texture_file}'
assert seen==expected, f'Expected 8101..8111, got {sorted(seen)}'
print('PASS: Minecraft 1.20.1 pack format, 11 model IDs, JSON models and referenced textures')
