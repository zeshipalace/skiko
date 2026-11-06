#!/usr/bin/env python3
"""Verify that the iOS Maven publications contain the native Skia libraries."""

from pathlib import Path
import sys
import subprocess
import tempfile
import zipfile


def verify(repository: Path, version: str, module: str, suffix: str, target: str, native_target: str) -> None:
    artifact = f'{module}-{suffix}'
    directory = repository / 'org' / 'jetbrains' / 'skiko' / artifact / version
    archive = directory / f'{artifact}-{version}.klib'
    if not archive.is_file():
        raise SystemExit(f'{artifact}: missing main KLIB {archive}')
    with zipfile.ZipFile(archive) as klib:
        prefix = f'default/targets/{target}/included/'
        libraries = ['libskia_static.a', f'{module}-native-bridges-{native_target}-arm64.a']
        with tempfile.TemporaryDirectory(prefix='skiko-ios-verify-') as tmp:
            for name in libraries:
                try:
                    contents = klib.read(prefix + name)
                except KeyError:
                    raise SystemExit(f'{archive.name}: missing {name}') from None
                if not contents:
                    raise SystemExit(f'{archive.name}: empty {name}')
                library = Path(tmp) / name
                library.write_bytes(contents)
                architectures = subprocess.check_output(
                    ['xcrun', 'lipo', '-archs', str(library)], text=True
                ).split()
                if 'arm64' not in architectures:
                    raise SystemExit(f'{archive.name}: {name} is missing arm64')
    print(f'{artifact}: verified native libraries in {archive.name}')


if __name__ == '__main__':
    if len(sys.argv) != 3 or not sys.argv[2] or '/' in sys.argv[2]:
        raise SystemExit('usage: verify-ios-klibs.py MAVEN_REPOSITORY VERSION')
    repository = Path(sys.argv[1])
    for module in ('skiko', 'skiko-graphite', 'skiko-skottie'):
        verify(repository, sys.argv[2], module, 'iosarm64', 'ios_arm64', 'ios')
        verify(repository, sys.argv[2], module, 'iossimulatorarm64', 'ios_simulator_arm64', 'iosSim')
