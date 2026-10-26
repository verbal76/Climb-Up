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
                      r.get("phase_extend_s", "-"), r.get("phase_closing_rest_s", "-"), r.get("phase_decoys_forks_s", "-"), r.get("phase_rooms_s", "-"),
                      r.get("phase_forks_reserved_s", "-"), r.get("phase_forks_open_s", "-"), r.get("phase_key_rooms_s", "-")))
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
print("=== worst new chunks: gen_s run y0 attempts failed_attempts refused_s accepted_s extend_s closing_rest_s decoys_forks_s rooms_s (of decoys_forks: forks_reserved_s forks_open_s key_rooms_s) ===")
for w in N["worst"]:
    print(w)
print("=== new progression ===")
for k, v in N["full"].get("progression", {}).items():
    print(k, "slope", v.get("slope_per_castle"), "first->last %", v.get("first_to_last_pct"), "declining", v.get("declining_steps"))
print("=== every unsuccessful solver slice (new): run slice y0 y1 links failed_link fail_info ===")
for r in rows(sys.argv[2], "solver.csv"):
    if r["completed"] != "true":
        print(r["run"], r.get("slice"), r.get("y0"), r.get("y1"), r.get("plans"), r.get("failed_link"), (r.get("fail_info") or "")[:220])
print("=== unsuccessful solver slices by class (new; fail_class column, derived here when the run predates it) ===")
def cls(r):
    c = r.get("fail_class")
    if c: return c
    i = (r.get("fail_info") or "").replace('"', "")
    for pre, k in (("solver threw", "solver_exception"), ("setback while executing", "route_link_failure"), ("plan failed", "planning_failure"),
                   ("recover", "recovery_failure"), ("detour", "recovery_failure"), ("lost the route", "recovery_failure"), ("stuck on a spike slab", "recovery_failure"),
                   ("stuck at one link", "knockback_loop")):
        if i.startswith(pre): return k
    return "timeout" if "within 20 s" in i else "unknown"
tally = defaultdict(lambda: defaultdict(int)); tot = defaultdict(lambda: [0, 0])
for r in rows(sys.argv[2], "solver.csv"):
    m = mode(r["run"]); tot[m][0] += 1
    if r["completed"] != "true": tot[m][1] += 1; tally[m][cls(r)] += 1
for m in MODES:
    print(m, "slices", tot[m][0], "unsuccessful", tot[m][1], dict(tally[m]), "reconciled" if sum(tally[m].values()) == tot[m][1] else "MISMATCH")

# ---- safety and route-network telemetry (observe-only; owner 2026-10-26). Printed for the new run, and the baseline when it has the files.
def safety(d):
    """Per mode and castle: share of SAFE / TERRAIN / HAZARD / CONTESTED main-route platforms, threat chains (consecutive HAZARD or CONTESTED
    platforms, checkpoints break nothing), longest chain, overlapping-threat share (2+ families) and uncontested recovery (SAFE non-checkpoint)."""
    by = defaultdict(list)
    for r in rows(d, "platforms.csv"): by[r["run"]].append(r)
    agg = defaultdict(lambda: defaultdict(lambda: Counter()))
    chains = defaultdict(lambda: defaultdict(list))
    for run, rs in by.items():
        m = mode(run); cur = 0; cur_c = None
        for r in rs:
            c = int(r["castle"]); a = agg[m][c]; a[r["class"]] += 1; a["n"] += 1
            if int(r.get("threat_family_count") or r.get("threat_families") or 0) >= 2: a["overlap"] += 1          # (2d4388a wrote the count under "threat_families")
            if r["class"] == "SAFE" and r["checkpoint"] != "true": a["recovery"] += 1
            if r["class"] in ("HAZARD", "CONTESTED"): cur += 1; cur_c = c
            elif cur: chains[m][cur_c].append(cur); cur = 0
        if cur: chains[m][cur_c].append(cur)
    return agg, chains
for name, d in (("new", sys.argv[2]), ("base", sys.argv[1])):
    agg, chains = safety(d)
    if not agg: print("=== safety telemetry (" + name + "): not in this run ==="); continue
    print("=== safety per castle (" + name + "): safe% terrain% hazard% contested% overlap% recovery% | threat chains mean/max ===")
    for m in MODES:
        line = []
        for c in range(1, 11):
            a = agg[m][c]; n = max(1, a["n"]); ch = chains[m][c]
            line.append("C%d %2.0f/%2.0f/%2.0f/%2.0f ov%2.0f rec%2.0f ch%.1f/%d" % (c, 100*a["SAFE"]/n, 100*a["TERRAIN"]/n, 100*a["HAZARD"]/n, 100*a["CONTESTED"]/n,
                        100*a["overlap"]/n, 100*a["recovery"]/n, (sum(ch)/len(ch)) if ch else 0, max(ch) if ch else 0))
        print(m); [print("   " + x) for x in line]
fk = rows(sys.argv[2], "forks.csv")
if fk:
    print("=== forks per mode/castle (new): forks / sub-forks / max depth / rejoin on branch / branch longer than route (information) ===")
    t = defaultdict(lambda: defaultdict(lambda: [0, 0, 0, 0, 0, 0]))
    for r in fk:
        v = t[mode(r["run"])][int(r["castle"])]; v[0] += 1
        if r["takeoff_on"] == "branch": v[1] += 1
        v[2] = max(v[2], int(r["depth"])); v[3] += r["rejoin_on"] == "branch"
        if r["bypassed_route_length_m"]: v[5] += 1; v[4] += float(r["branch_length_m"]) > float(r["bypassed_route_length_m"])
    for m in MODES: print(m, " ".join("C%d %d/%d/d%d/%d/%d-of-%d" % (c, *t[m][c][:4], t[m][c][4], t[m][c][5]) for c in range(1, 11)))
fa = rows(sys.argv[2], "fork_attempts.csv")
if fa:
    print("=== fork attempts (new): fork tries/ok, pad-fork tries/ok, sub-fork eligible/tries/ok/refused; refusal reasons ===")
    for m in MODES:
        tot = Counter(); why = Counter()
        for r in fa:
            if mode(r["run"]) != m: continue
            for k in ("fork_try", "fork_ok", "padfork_try", "padfork_ok", "subfork_eligible", "subfork_try", "subfork_ok", "subfork_refused"): tot[k] += int(r[k] or 0)
            for kv in (r.get("reasons") or "").split():
                k, v = kv.split("="); why[k] += int(v)
        print(m, dict(tot)); print("   reasons", dict(why.most_common(12)))

# ---- traversal variety (owner 2026-10-26): ropes and ordinary traversal per castle, both runs (traversal.csv; absent in older runs)
for name, d in (("new", sys.argv[2]), ("base", sys.argv[1])):
    tv = rows(d, "traversal.csv")
    if not tv: print("=== traversal variety (" + name + "): not in this run ==="); continue
    print("=== traversal variety (" + name + "): challenge density | fallbacks (rope share) | ropes pick/fallback | max ropes in a row | max ordinary links in a row | longest unchallenged climb m | rope climb m | unchallenged horizontal m ===")
    for m in MODES:
        agg = defaultdict(lambda: Counter()); mx = defaultdict(lambda: Counter())
        for r in tv:
            if mode(r["run"]) != m: continue
            c = int(r["castle"]); a = agg[c]
            for k in ("route_links", "challenge_links", "fallbacks_accepted", "rope_picks", "rope_fallbacks"): a[k] += int(r[k])
            for k in ("rope_climb_m", "unchallenged_horizontal_m"): a[k] += float(r[k])
            for k in ("max_consecutive_rope_modules", "max_consecutive_ordinary_links"): mx[c][k] = max(mx[c][k], int(r[k]))
            mx[c]["longest"] = max(mx[c]["longest"], float(r["longest_unchallenged_climb_m"]))
        if not agg: continue
        print(m)
        for c in sorted(agg):
            a = agg[c]; fb = max(1, a["fallbacks_accepted"])
            print("   C%d %.2f | %d (%.0f%%) | %d/%d | %d | %d | %.1f | %.0f | %.0f" % (c, a["challenge_links"] / max(1, a["route_links"]), a["fallbacks_accepted"], 100 * a["rope_fallbacks"] / fb,
                  a["rope_picks"], a["rope_fallbacks"], mx[c]["max_consecutive_rope_modules"], mx[c]["max_consecutive_ordinary_links"], mx[c]["longest"], a["rope_climb_m"], a["unchallenged_horizontal_m"]))

# ---- generation failures (new run): per run, failed slice attempts, the stop reason, and the last failures with their refusal counters
gf = rows(sys.argv[2], "generation_failures.csv")
print("=== generation failures (new): failed slice attempts per run; last 6 per run ===")
per = defaultdict(list)
for r in gf: per[r["run"]].append(r)
for run, rs in sorted(per.items()):
    print(run, "failed slice attempts", len(rs))
    for r in rs[-6:]: print("   slice", r["slice"], (r["detail"] or "")[:300])
for p in sorted(os.listdir(sys.argv[2])) if os.path.isdir(sys.argv[2]) else []:
    if p.endswith(".json") and "run" in p: pass
