#!/usr/bin/env python3
"""Select the newest installed Xcode for the current macOS runner."""

import os
from pathlib import Path
import re
import subprocess


def version(developer: Path) -> tuple[tuple[int, ...], int]:
    env = os.environ.copy()
    env['DEVELOPER_DIR'] = str(developer)
    result = subprocess.check_output(
        [str(developer / 'usr/bin/xcodebuild'), '-version'], env=env, text=True
    )
    match = re.search(r'^Xcode\s+(\d+(?:\.\d+)*)(.*)$', result, re.MULTILINE)
    if not match:
        raise ValueError(f'Cannot determine Xcode version: {developer}')
    numbers = tuple(int(part) for part in match.group(1).split('.'))
    numbers += (0,) * (4 - len(numbers))
    label = (match.group(2) + ' ' + developer.parent.parent.name).lower()
    stage = 0 if 'beta' in label else 1 if re.search(r'(?:^|[^a-z0-9])rc(?:[^a-z0-9]|$)|release candidate', label) else 2
    return numbers, stage


candidates = [app / 'Contents/Developer' for app in Path('/Applications').glob('Xcode*.app')
              if (app / 'Contents/Developer/usr/bin/xcodebuild').is_file()]
if not candidates:
    raise SystemExit('No installed Xcode found in /Applications')
selected = max(candidates, key=version)
print(f'DEVELOPER_DIR={selected}')
