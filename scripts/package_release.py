#!/usr/bin/env python3
"""Package Ink2TeX source and already-built APKs for download. Does not build the app."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile

EXCLUDED_DIRECTORIES = {'.git', '.gradle', '.cxx', '.idea', '.kotlin', '__pycache__'}


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def project_files(root: Path):
    for directory, subdirectories, filenames in os.walk(root, followlinks=False):
        current = Path(directory)
        relative = current.relative_to(root)
        subdirectories[:] = sorted(
            name for name in subdirectories
            if name not in EXCLUDED_DIRECTORIES
            and not (current / name).is_symlink()
            and not (relative == Path('.') and name in {'build', 'downloads'})
            and not (relative == Path('app') and name == 'build')
        )
        for name in sorted(filenames):
            path = current / name
            if path.is_symlink() or name.endswith(('.iml', '.pyc')):
                continue
            if path.relative_to(root) == Path('local.properties'):
                continue
            yield path


def atomic_zip(destination: Path, write_archive) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=destination.stem + '-', suffix='.tmp', dir=destination.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        with ZipFile(temporary, 'w', ZIP_DEFLATED, compresslevel=6) as archive:
            write_archive(archive)
        with ZipFile(temporary) as archive:
            damaged = archive.testzip()
            if damaged is not None:
                raise RuntimeError(f'Archive integrity failure: {damaged}')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=root.parent / 'Ink2TeX-deliverables')
    args = parser.parse_args()
    output = args.output.resolve()
    if output == root or root in output.parents:
        parser.error('Output must be outside the project directory to prevent recursive archives.')
    output.mkdir(parents=True, exist_ok=True)

    built_apks = [
        (root / 'app/build/outputs/apk/debug/app-debug.apk', 'Ink2TeX-debug-arm64.apk'),
        (root / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 'Ink2TeX-device-tests.apk'),
    ]
    for source, name in built_apks:
        if not source.is_file():
            raise SystemExit(f'Missing built APK: {source}. Build before packaging.')
        shutil.copyfile(source, output / name)

    files = list(project_files(root))
    source_zip = output / 'Ink2TeX-Android-Studio-Project.zip'
    atomic_zip(source_zip, lambda archive: [archive.write(path, Path('Ink2TeX') / path.relative_to(root)) for path in files])

    bundle = output / 'Ink2TeX-All-Files.zip'
    checksums = ''.join(f'{sha256(output / name)}  apks/{name}\n' for _, name in built_apks)
    summary = root / 'docs/testing/summary.json'
    evidence = json.loads(summary.read_text()) if summary.exists() else {}
    bundle_note = (
        'Ink2TeX complete delivery bundle\n\n'
        'Ink2TeX/ contains the Android Studio project, Kotlin and native sources,\n'
        'Gradle wrapper, model catalogue, documentation, test code and evidence.\n'
        'apks/ contains the most recently built application and device-test APKs.\n'
        'SHA256SUMS.txt verifies those APK files.\n\n'
        'Open Ink2TeX/README.md for installation/build instructions and limitations.\n'
        'Read Ink2TeX/docs/TESTING.md and FEATURE_STATUS.md for verification scope.\n'
        'Model weights are downloaded through the app. Machine-specific SDK files,\n'
        'build caches and private local settings are excluded.\n\n'
        'Packaging does not build the app or rerun tests. Last recorded evidence:\n'
        + json.dumps(evidence, indent=2) + '\n'
    )

    def write_bundle(archive: ZipFile) -> None:
        for path in files:
            archive.write(path, Path('Ink2TeX') / path.relative_to(root))
        for _, name in built_apks:
            archive.write(output / name, Path('apks') / name)
        archive.writestr('SHA256SUMS.txt', checksums)
        archive.writestr('START-HERE.txt', bundle_note)

    atomic_zip(bundle, write_bundle)
    artifacts = [source_zip, bundle] + [output / name for _, name in built_apks]
    (output / 'SHA256SUMS.txt').write_text(''.join(f'{sha256(path)}  {path.name}\n' for path in artifacts))
    print(f'Packaged and verified {len(files)} project files.')
    for path in artifacts:
        print(f'{path.name}: {path.stat().st_size:,} bytes')


if __name__ == '__main__':
    main()
