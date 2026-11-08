"""Check complete Android translations and numbered formatting placeholders (offline)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
PLACEHOLDER = re.compile(r'(?<!%)%(\d+)\$[sdf]')
LANGUAGES = ('values-en', 'values-ja', 'values-b+zh+Hant', 'values-ru', 'values-ko')


def strings(directory):
    result = {}
    for path in directory.glob('*.xml'):
        for node in ET.parse(path).getroot().findall('string'):
            if node.get('translatable') == 'false':
                continue
            name = node.get('name')
            assert name not in result, f'Duplicate {directory.name}/{name}'
            result[name] = ''.join(node.itertext())
    return result


if __name__ == '__main__':
    source = strings(RES / 'values')
    assert source, 'No source strings'
    for directory in LANGUAGES:
        translated = strings(RES / directory)
        assert source.keys() == translated.keys(), (directory, 'Resource keys differ',
            sorted(source.keys()-translated.keys()), sorted(translated.keys()-source.keys()))
        for name, text in translated.items():
            assert text.strip().strip('"'), (directory, name, 'Empty translation')
            assert sorted(PLACEHOLDER.findall(source[name])) == sorted(PLACEHOLDER.findall(text)), (
                directory, name, 'Format parameters differ')
        print(f'{directory}: {len(translated)} strings, complete keys and format parameters')
