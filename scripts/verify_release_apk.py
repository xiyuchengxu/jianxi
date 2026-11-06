"""Verify distributable APK signatures, identity and integrity before publishing."""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import zipfile

PRODUCTION_CERT_SHA256 = "74c2680384903d371b0a8a2e0ccd17de687ec3ecd12b4266c41faae30e565770"


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise ValueError(result.stdout.strip() + "\n" + result.stderr.strip())
    return result.stdout


def verify(args):
    apk = args.apk.resolve(strict=True)
    tool_dir = args.build_tools.resolve(strict=True)
    apksigner = tool_dir / ("apksigner.bat" if sys.platform == "win32" else "apksigner")
    aapt = tool_dir / ("aapt.exe" if sys.platform == "win32" else "aapt")
    expected_cert = PRODUCTION_CERT_SHA256

    run(str(apksigner), "verify", str(apk))
    # Default verification can skip v1 when minSdk >= 24 even if the JAR signature exists.
    # Extend only the verification range to exercise all three signature schemes.
    output = run(str(apksigner), "verify", "--verbose", "--print-certs", "--min-sdk-version", "23", str(apk))
    for scheme in ("v1", "v2", "v3"):
        if not re.search(r"^Verified using " + scheme + r" scheme .*: true$", output, re.MULTILINE):
            raise ValueError(f"APK must verify with {scheme} signing enabled\n{output}")
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", output, re.MULTILINE)
    if certs != [expected_cert]:
        raise ValueError(f"APK signer does not match the original production certificate: {certs}")

    # Only the signature verification path is simulated; the app still requires API 26.
    run(str(apksigner), "verify", "--min-sdk-version", "23", "--max-sdk-version", "23", str(apk))
    with zipfile.ZipFile(apk) as archive:
        invalid = archive.testzip()
        if invalid:
            raise ValueError(f"APK ZIP integrity failure: {invalid}")
        names = [name.upper() for name in archive.namelist()]
        if "META-INF/MANIFEST.MF" not in names:
            raise ValueError("Missing v1 JAR manifest")
        if not any(name.startswith("META-INF/") and name.endswith(".SF") for name in names):
            raise ValueError("Missing v1 signature file")
        if not any(name.startswith("META-INF/") and name.endswith((".RSA", ".DSA", ".EC")) for name in names):
            raise ValueError("Missing v1 signing certificate")

    badging = run(str(aapt), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package.groups() != ("dev.rain.laundry", str(args.version_code), args.version):
        raise ValueError("APK package name or version does not match the release")
    if re.search(r"^application-debuggable", badging, re.MULTILINE):
        raise ValueError("Release APK must not be debuggable")
    return {
        "file": apk.name,
        "bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "certificate_sha256": expected_cert,
        "signatures": {"v1": True, "v2": True, "v3": True},
        "independent_v1_verification": True,
        "package": package.group(1),
        "version": args.version,
        "versionCode": args.version_code,
        "debuggable": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--build-tools", type=pathlib.Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--json", type=pathlib.Path)
    args = parser.parse_args()
    try:
        record = verify(args)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f"APK verification failed: {error}\n")
    output = json.dumps(record, ensure_ascii=False, indent=2) + "\n"
    if args.json:
        args.json.write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
