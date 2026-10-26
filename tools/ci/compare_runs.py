#!/usr/bin/env python3
"""Prints a compact comparison of two consolidated three-lane reports (difficulty-baseline-3lane artifacts) to the job log.

    compare_runs.py <baseline dir> <new dir>
"""
import csv, json, os, sys
from collections import Counter, defaultdict

MODES = ["NORMAL", "PUNISHER", "NGH"]


def rows(d, f):
    p = os.path.join(d, f)
    return list(csv.DictReader(open(p))) if os.path.exists(p) else []


def mode(run):
    return run.split("-s")[0]


def summary(d):
    out = {}
    cast = defaultdict(list)
    for r in rows(d, "castles.csv"):
        if r["mean"]:
            cast[(r["mode"], r["lane"], int(r["castle"]))].append(float(r["mean"]))
    out["castles"] = {k: sum(v) / len(v) for k, v in cast.items()}
    inv = Counter()
    for r in rows(d, "inventory.csv"):
        inv[(r["mode"], r["element_type"])] += int(r["placed"])
    out["inv"] = inv
    mods = defaultdict(lambda: [0, 0])
    for r in rows(d, "modules.csv"):
        m = mods[(mode(r["run"]), r["kind"])]; m[0] += 1; m[1] += r["accepted"] == "true" and r["final_attempt"] == "true"
    out["mods"] = mods
    out["rules"] = Counter((r["mode"], r["rule"]) for r in rows(d, "rule_checks.csv"))
    sv = defaultdict(lambda: [0, 0])
    for r in rows(d, "solver.csv"):
        s = sv[mode(r["run"])]; s[0] += 1; s[1] += r["completed"] == "true"
    out["solver"] = sv
    out["fails"] = rows(d, "solver_failures.csv")
    gen = defaultdict(float); worst = []
    for r in rows(d, "chunks.csv"):
        g = float(r["gen_wall_s"] or 0); gen[mode(r["run"])] += g
        worst.append((g, r["run"], r["y0"], r["slice_attempts"], r["failed_attempts"], r["refused_or_discarded_s"], r["accepted_modules_s"],
                      r.get("phase_extend_s", "-"), r.get("phase_closing_rest_s", "-"), r.get("phase_decoys_forks_s", "-"), r.get("phase_rooms_s", "-")))
    out["gen"] = gen; out["worst"] = sorted(worst, reverse=True)[:8]
    rf = os.path.join(d, "report_full.json")
    out["full"] = json.load(open(rf)) if os.path.exists(rf) else {}
    return out


B, N = summary(sys.argv[1]), summary(sys.argv[2])
f = lambda v: "-" if v is None else f"{v:5.1f}"
print("=== castle means per lane (baseline -> new), mean over seeds ===")
for m in MODES:
    for lane in ["TERRAIN", "HAZARDS", "ENEMIES", "TOTAL_REFERENCE"]:
        b = [B["castles"].get((m, lane, c)) for c in range(1, 11)]
        n = [N["castles"].get((m, lane, c)) for c in range(1, 11)]
        print(f"{m:8s} {lane:15s} base " + " ".join(f(x) for x in b))
        print(f"{'':8s} {'':15s} new  " + " ".join(f(x) for x in n))
print("=== mode comparison (new) ===")
print(json.dumps(N["full"].get("mode_comparison", {})))
print("=== placements per mode (base -> new) ===")
for m in MODES:
    print(m, {t: (B["inv"][(m, t)], N["inv"][(m, t)]) for t in ["SKULL", "BLOB", "CRAB", "BEE", "SPIKE_DROP", "MORTAR", "SAW_H", "SAW_V", "CANNON", "AIMED", "SPIKE_BLOCK", "SPIKE_TRAP", "PENDULUM"]})
print("=== module accepted/tried (base -> new) ===")
for m in MODES:
    print(m, {k: (f"{B['mods'][(m, k)][1]}/{B['mods'][(m, k)][0]}", f"{N['mods'][(m, k)][1]}/{N['mods'][(m, k)][0]}") for k in ["DROPS", "CREATURE", "MORTAR", "HAZ", "TRAP"]})
print("=== owner rule checks (base -> new) ===")
for m in MODES:
    print(m, {r: (B["rules"][(m, r)], N["rules"][(m, r)]) for r in ["R1-intermediate-enemy", "R2-pursuit-into-hazard", "R3-protected-structure"]})
print("=== owner rule summary (new) ===")
print(json.dumps(N["full"].get("owner_rule_checks", {})))
print("=== solver completed/slices (base -> new) ===")
for m in MODES:
    print(m, B["solver"][m], "->", N["solver"][m])
print("=== new solver failures ===")
for r in N["fails"]:
    print(r["run"], "castle", r["castle"], r["from_type"], "->", r["to_type"], r["y0"], r["fail_info"][:150])
print("=== generation seconds per mode, all seeds (base -> new) ===")
for m in MODES:
    print(m, round(B["gen"][m]), "->", round(N["gen"][m]))
print("=== worst new chunks: gen_s run y0 attempts failed_attempts refused_s accepted_s extend_s closing_rest_s decoys_forks_s rooms_s ===")
for w in N["worst"]:
    print(w)
print("=== new progression ===")
for k, v in N["full"].get("progression", {}).items():
    print(k, "slope", v.get("slope_per_castle"), "first->last %", v.get("first_to_last_pct"), "declining", v.get("declining_steps"))
