#!/usr/bin/env python3
"""Check the actual APK identity, rather than only its Gradle configuration."""
import os
import pathlib
import re
import subprocess
import sys

EXPECTED_PACKAGE = 'com.anonymous.SecureNotesMobile.vault'
apk = pathlib.Path(sys.argv[1])
aapt = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
assert match and match.group(1) == EXPECTED_PACKAGE, 'Release APK must use the separate native vault package'
print(f'Verified production package: {EXPECTED_PACKAGE}')
