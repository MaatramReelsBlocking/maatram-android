#!/usr/bin/env python3
"""
Maatram — Android resource sanity check.

Run from the folder that contains android/ :
    python check-res.py

Catches the two errors that broke earlier builds:
  1. "Duplicate resources"        -> same name defined twice
  2. "resource color/x not found" -> a reference with no definition
"""
import os
import re
import sys
import glob

RES = os.path.join("android", "app", "src", "main", "res")
MANIFEST = os.path.join("android", "app", "src", "main", "AndroidManifest.xml")

if not os.path.isdir(RES):
    sys.exit("ERROR: run this from the folder that contains android/")

VALUE_TAGS = ("color", "string", "style", "dimen", "bool", "integer", "array")
FILE_DIRS = ("drawable", "mipmap", "xml", "layout", "anim", "raw", "menu")

# ---------- collect definitions ----------
defs = {}                 # tag -> {name: [files it was defined in]}
for path in glob.glob(os.path.join(RES, "values*", "*.xml")):
    text = open(path, encoding="utf-8").read()
    for tag, name in re.findall(r'<(%s)\s+name="([^"]+)"' % "|".join(VALUE_TAGS), text):
        defs.setdefault(tag, {}).setdefault(name, []).append(path)

for entry in os.listdir(RES):
    base = entry.split("-")[0]
    if base in FILE_DIRS:
        for fn in os.listdir(os.path.join(RES, entry)):
            name = os.path.splitext(fn)[0]
            defs.setdefault(base, {}).setdefault(name, []).append(os.path.join(RES, entry, fn))

# ---------- duplicates ----------
dupes = []
for tag, names in defs.items():
    if tag in FILE_DIRS:          # same name across densities is normal
        continue
    for name, files in names.items():
        if len(files) > 1:
            dupes.append((tag, name, files))

# ---------- references ----------
sources = glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True)
if os.path.isfile(MANIFEST):
    sources.append(MANIFEST)

missing = set()
for path in sources:
    text = open(path, encoding="utf-8", errors="ignore").read()
    for tag, name in re.findall(r'@(color|drawable|mipmap|string|style|xml|layout)/([A-Za-z0-9_.]+)', text):
        if name not in defs.get(tag, {}):
            missing.add((tag, name, path))

# ---------- report ----------
ok = True

if dupes:
    ok = False
    print("DUPLICATE RESOURCES  (delete one of each pair)")
    for tag, name, files in dupes:
        print("  %s/%s" % (tag, name))
        for f in files:
            print("      %s" % f)
    print()
else:
    print("duplicates .......... none")

if missing:
    ok = False
    print("UNRESOLVED REFERENCES  (define these, or fix the typo)")
    for tag, name, path in sorted(missing):
        print("  @%s/%s   used in %s" % (tag, name, path))
    print()
else:
    print("unresolved refs ..... none")

print()
print("defined colors: %s" % ", ".join(sorted(defs.get("color", {}))))
print("defined styles: %s" % ", ".join(sorted(defs.get("style", {}))))

sys.exit(0 if ok else 1)
