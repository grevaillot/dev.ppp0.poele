#!/usr/bin/env python3
"""Notes de release : section du CHANGELOG pour le tag, empreintes de l'APK et du certificat.

Usage : release_notes.py <tag> <CHANGELOG.md> <apk.sha256> <cert.txt>
"""
import re
import sys
from pathlib import Path

tag, changelog, sha_file, cert_file = sys.argv[1:5]
text = Path(changelog).read_text(encoding="utf-8")
m = re.search(rf"^## {re.escape(tag)}\b.*?$(.*?)(?=^## |\Z)", text, re.M | re.S)
if not m:
    sys.exit(f"CHANGELOG : pas de section {tag}")
sha = Path(sha_file).read_text().split()[0]
cert = re.search(r"SHA-256 digest: ([0-9a-f]+)", Path(cert_file).read_text())
print(m.group(1).strip())
print()
print(f"APK SHA-256 : `{sha}`")
if cert:
    print(f"Certificat de signature SHA-256 : `{cert.group(1)}`")
