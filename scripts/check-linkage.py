#!/usr/bin/env python3
"""
Checks that the addon links against the REAL zAuctionHouse plugin jar, which ships sarah / FoliaLib /
currencies RELOCATED (fr.maxlego08.zauctionhouse.libs.*). Compiling against the raw API project hides that:
a method whose signature mentions a relocated type compiles fine and dies at runtime with NoSuchMethodError.

Usage: scripts/check-linkage.py <zAuctionHouse-plugin.jar> [addon-classes-dir]
Exit code 1 when something cannot link.
"""
import os, re, subprocess, sys, tempfile, zipfile

API = "fr/maxlego08/zauctionhouse/api/"
# Packages the plugin relocates: their ORIGINAL names do not exist at runtime.
INHERITED = {"ordinal", "name", "hashCode", "equals", "toString", "getClass", "compareTo", "getDeclaringClass"}  # Object / Enum / Record
RELOCATED = ("fr/maxlego08/sarah", "com/tcoded/folialib", "fr/traqueur/currencies", "dev/faststats")

plugin_jar = sys.argv[1]
classes_dir = sys.argv[2] if len(sys.argv) > 2 else "build/classes/java/main"

def javap(args):
    return subprocess.run(["javap"] + args, capture_output=True, text=True, check=True).stdout

# 1. Every (name, descriptor) the plugin really offers under the API package.
offered = set()
with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(plugin_jar) as jar:
    names = [n for n in jar.namelist() if n.startswith(API) and n.endswith(".class")]
    jar.extractall(tmp, names)
    for chunk in range(0, len(names), 200):
        out = javap(["-p", "-s", "-cp", tmp] + [n[:-6].replace("/", ".") for n in names[chunk:chunk + 200]])
        current = None
        for line in out.splitlines():
            m = re.match(r"\s+(?:[\w$<>\[\]., ?]+ )?(\w+|<init>)\(.*\);", line)
            if m:
                current = m.group(1)
            d = re.match(r"\s+descriptor: (.+)", line)
            if d and current:
                offered.add((current, d.group(1)))
                current = None

# 2. Every API call / relocated reference the addon makes.
class_files = [os.path.join(r, f) for r, _, fs in os.walk(classes_dir) for f in fs if f.endswith(".class")]
problems = []
for cf in class_files:
    for line in javap(["-c", "-p", cf]).splitlines():
        m = re.search(r"//\s*(?:Interface)?Method ([\w/$]+)\.([\w<>$]+):(\(.*\).+)$", line)
        if m and m.group(1).startswith(API) and m.group(2) not in INHERITED and (m.group(2), m.group(3)) not in offered:
            problems.append(f"{os.path.basename(cf)}: {m.group(1)}.{m.group(2)}{m.group(3)} does not exist in the plugin jar")
        for pkg in RELOCATED:
            if pkg in line:
                problems.append(f"{os.path.basename(cf)}: references {pkg}.* which the plugin relocates: {line.strip()[:140]}")

problems = sorted(set(problems))
if problems:
    print("LINKAGE PROBLEMS:")
    print("\n".join("  " + p for p in problems))
    sys.exit(1)
print(f"OK: {len(class_files)} addon classes link against {os.path.basename(plugin_jar)}")
