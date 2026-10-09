# Asset tools

## Verify an APK

`verify_package.py` compares source files and packaged assets against the SHA-256 hashes in `assets-manifest.json`. It checks the expected audio cue count and flags bundled HTML, CSS or JavaScript.

After building the debug APK, run from the project root:

```powershell
python .\qa\verify_package.py
```

Use `--apk` to select another APK or `--reference` to compare images and fonts with the original design assets:

```powershell
python .\qa\verify_package.py --apk PATH_TO_APK
python .\qa\verify_package.py --reference PATH_TO_DESIGN_ASSETS
```

The report is written to `qa/package-verification.json`. Audio recordings are outside the manifest.

## Generate dashboard cues

`generate_cues.py` creates the short PCM warning and transition sounds in `app/src/main/assets/audio/cues/`:

```powershell
python .\qa\generate_cues.py
```

The generated WAV files are included in the project. Regeneration is required only when changing the cue definitions.
