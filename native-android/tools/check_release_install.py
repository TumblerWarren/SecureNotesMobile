#!/usr/bin/env python3
"""Prove side-by-side installation with a differently signed, disposable legacy app."""
import os
import pathlib
import subprocess
import sys
import tempfile
import time

LEGACY = 'com.anonymous.SecureNotesMobile'
VAULT = LEGACY + '.vault'
apk = pathlib.Path(sys.argv[1]).resolve()
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
build_tools = sdk / 'build-tools/35.0.0'
keytool = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/keytool'


def run(*args):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def installed(package):
    result = subprocess.run(['adb', 'shell', 'pm', 'path', package], text=True, capture_output=True)
    return result.returncode == 0 and result.stdout.strip().startswith('package:')


assert run('adb', 'shell', 'getprop', 'ro.kernel.qemu') == '1', 'Only run on a disposable emulator'
assert not installed(LEGACY) and not installed(VAULT), 'Fresh emulator required; refusing to modify existing installs'
legacy_created = False
vault_created = False
try:
    with tempfile.TemporaryDirectory(prefix='vault-coexistence-', dir=os.environ['RUNNER_TEMP']) as directory:
        root = pathlib.Path(directory)
        manifest = root / 'AndroidManifest.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{LEGACY}" android:versionCode="1" android:versionName="legacy-fixture">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="Synthetic legacy fixture" android:hasCode="false" android:debuggable="true" android:allowBackup="false"/>
</manifest>''')
        run(build_tools / 'aapt', 'package', '-f', '-M', manifest, '-I', sdk / 'platforms/android-36/android.jar', '-F', root / 'unsigned.apk')
        run(build_tools / 'zipalign', '-f', '4', root / 'unsigned.apk', root / 'legacy.apk')
        # This throwaway certificate is deliberately different from the private production key.
        run(keytool, '-genkeypair', '-keystore', root / 'fixture.p12', '-storetype', 'PKCS12', '-alias', 'fixture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '7', '-dname', 'CN=Synthetic legacy install fixture', '-storepass', 'synthetic-fixture-only', '-keypass', 'synthetic-fixture-only', '-noprompt')
        run(build_tools / 'apksigner', 'sign', '--ks', root / 'fixture.p12', '--ks-key-alias', 'fixture', '--ks-pass', 'pass:synthetic-fixture-only', '--key-pass', 'pass:synthetic-fixture-only', root / 'legacy.apk')
        print(run('adb', 'install', root / 'legacy.apk'))
        legacy_created = True
        run('adb', 'shell', 'run-as', LEGACY, 'mkdir', '-p', 'files')
        run('adb', 'shell', 'run-as', LEGACY, 'touch', 'files/legacy-vault-sentinel')
        print(run('adb', 'install', apk))
        vault_created = True
        assert installed(LEGACY) and installed(VAULT), 'Both package identities must remain installed'
        run('adb', 'shell', 'run-as', LEGACY, 'test', '-f', 'files/legacy-vault-sentinel')
        launch = run('adb', 'shell', 'am', 'start', '-W', '-n', VAULT + '/app.securenote.vault.MainActivity')
        assert 'Status: ok' in launch, launch
        time.sleep(2)
        assert run('adb', 'shell', 'pidof', VAULT), 'Signed vault must stay running after launch'
        print('Verified: differently signed apps coexist, legacy data is intact, and the signed vault launches.')
finally:
    # These installs were created by this script only, on a fresh test emulator.
    if vault_created:
        run('adb', 'uninstall', VAULT)
    if legacy_created:
        run('adb', 'uninstall', LEGACY)
