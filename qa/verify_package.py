"""Verify committed assets and the native APK, optionally against design sources."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path,
                        default=root / 'app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--reference', type=Path,
                        help='Optional original GUI_Designs/dist/assets directory')
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f'APK not found: {args.apk}. Build assembleDebug first.')

    assets = root / 'app/src/main/assets'
    manifest = json.loads((root / 'qa/assets-manifest.json').read_text(encoding='utf-8-sig'))
    report = {}
    with zipfile.ZipFile(args.apk) as archive:
        entries = archive.namelist()
        assert not any(n.endswith(('.html', '.js', '.css')) for n in entries), 'Web runtime in APK'
        assert not any('tire-skid' in n for n in entries), 'Tyre ambience in APK'
        for name, expected in manifest.items():
            data = (assets / name).read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            assert digest == expected, f'Asset differs from manifest: {name}'
            assert data == archive.read('assets/' + name), f'Stale APK asset: {name}'
            if args.reference and not name.startswith('audio/cues/'):
                assert data == (args.reference / name).read_bytes(), f'Reference differs: {name}'
            report[name] = digest
        cues = [n for n in entries if n.startswith('assets/audio/cues/') and n.endswith('.wav')]
        assert len(cues) == 13, f'Expected 13 cues, found {len(cues)}'
    report['apk_sha256'] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    report['apk_bytes'] = args.apk.stat().st_size
    report['generated_cues'] = len(cues)
    (root / 'qa/package-verification.json').write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'{len(manifest)} assets match manifest and APK; {len(cues)} cues; no web runtime or tyre ambience.')


if __name__ == '__main__':
    main()
