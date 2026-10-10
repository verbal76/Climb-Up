#!/usr/bin/env python3
"""check_ownership.py [--owner E1|E2 --against BASE_SHA]
Without options: every tracked file must resolve to exactly one owner under docs/ota-full/ownership.json (first matching rule wins; an unmatched file is an error).
With --owner/--against: every file changed between BASE_SHA and the working tree (committed, staged, unstaged, untracked) must be owned by that engineer; anything owned by the other engineer or FROZEN fails.
Run it before every push. Exit 0 = clean."""
import argparse, fnmatch, json, os, re, subprocess, sys
ap = argparse.ArgumentParser(); ap.add_argument("--owner"); ap.add_argument("--against"); a = ap.parse_args()
root = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip(); os.chdir(root)
rules = json.load(open("docs/ota-full/ownership.json"))["rules"]
def rx(p):
    out = ""; i = 0
    while i < len(p):
        if p.startswith("**", i): out += ".*"; i += 2
        elif p[i] == "*": out += "[^/]*"; i += 1
        else: out += re.escape(p[i]); i += 1
    return re.compile("^" + out + "$")
compiled = [(rx(r["pattern"]), r["owner"], r["pattern"]) for r in rules]
def owner_of(path):
    for r, o, p in compiled:
        if r.match(path): return o, p
    return None, None
def lines(*cmd): return [l for l in subprocess.check_output(cmd, text=True).splitlines() if l]
if not a.owner:
    bad = []; counts = {}
    for f in lines("git", "ls-files"):
        o, p = owner_of(f)
        if o is None: bad.append(f)
        else: counts[o] = counts.get(o, 0) + 1
    print("ownership of %d tracked files: %s" % (sum(counts.values()) + len(bad), ", ".join("%s=%d" % kv for kv in sorted(counts.items()))))
    if bad: print("UNASSIGNED:\n  " + "\n  ".join(bad)); sys.exit(1)
    sys.exit(0)
changed = set(lines("git", "diff", "--name-only", a.against)) | set(lines("git", "ls-files", "--others", "--exclude-standard"))
bad = []
for f in sorted(changed):
    o, p = owner_of(f)
    if o != a.owner: bad.append("%s  (owner %s via %s)" % (f, o, p))
print("%d changed file(s) against %s; owner %s" % (len(changed), a.against[:10], a.owner))
if bad: print("NOT YOURS:\n  " + "\n  ".join(bad)); sys.exit(1)
print("all changed files belong to " + a.owner)
