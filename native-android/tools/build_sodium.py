#!/usr/bin/env python3
"""Build pinned upstream libsodium for Android. No precompiled third-party wrapper.
Requires Python 3.12+, make and an Android NDK. Run before the Gradle build.
"""
import argparse, hashlib, os, pathlib, subprocess, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = '1.0.22'
SHA256 = 'adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349'
p = argparse.ArgumentParser(); p.add_argument('--ndk', required=True); p.add_argument('--abis', nargs='+', default=['arm64-v8a', 'x86_64']); args = p.parse_args()
base = ROOT / '.sodium'; base.mkdir(exist_ok=True)
archive = base / 'source.tar.gz'
if not archive.exists(): urllib.request.urlretrieve(f'https://download.libsodium.org/libsodium/releases/libsodium-{VERSION}.tar.gz', archive)
if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256: raise SystemExit('libsodium source checksum mismatch')
ndk = pathlib.Path(args.ndk).resolve()
hosts = list((ndk / 'toolchains/llvm/prebuilt').iterdir())
if len(hosts) != 1: raise SystemExit('Expected one host toolchain')
binpath = hosts[0] / 'bin'
for abi in args.abis:
    triple = {'arm64-v8a':'aarch64-linux-android','x86_64':'x86_64-linux-android'}[abi]
    dest = base / abi
    if (dest / 'lib/libsodium.a').exists(): continue
    work = base / ('source-' + abi); work.mkdir(exist_ok=True)
    with tarfile.open(archive) as t: t.extractall(work, filter='data')
    source = work / ('libsodium-' + VERSION)
    env = dict(os.environ, CC=str(binpath / (triple + '26-clang')), AR=str(binpath / 'llvm-ar'), RANLIB=str(binpath / 'llvm-ranlib'), CFLAGS='-O2 -fPIC', LDFLAGS='-Wl,-z,max-page-size=16384')
    subprocess.run(['sh','./configure','--host='+triple,'--prefix='+str(dest),'--disable-shared','--enable-static','--with-pic','--enable-minimal'], cwd=source, env=env, check=True)
    subprocess.run(['make','-j4','install'], cwd=source, env=env, check=True)
print('Pinned libsodium Android builds are ready.')
