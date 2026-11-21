#!/usr/bin/env python3
"""update.json d'un APK, lu par l'appli pour se mettre à jour (update/AppUpdateFeed.kt).

Usage : update_json.py <aapt2> <apk> <tag vX.Y.Z> <package attendu>
versionName et versionCode sont lus dans l'APK lui-même : ils ne peuvent pas diverger.
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

aapt2, apk, tag, expected = sys.argv[1:5]
badging = subprocess.run([aapt2, "dump", "badging", apk], check=True, capture_output=True, text=True).stdout
m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not m or m.group(1) != expected:
    sys.exit(f"{apk} : pas un APK de release {expected}")
if "+" in m.group(3):
    sys.exit(f"{apk} : versionName {m.group(3)} porte un commit (build hors tag ou modifié), refusé pour une release")

print(json.dumps({
    "tag": tag,
    "versionName": m.group(3),
    "versionCode": int(m.group(2)),
    "apk": Path(apk).name,
    "sha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest(),
}, indent=2))
