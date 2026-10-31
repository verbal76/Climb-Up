#!/usr/bin/env python3
"""Rule 21 A/B report (owner Rule 21 directive 2026-10-31, section 12).

usage: rule21_report.py OUT_DIR COURSE_DIR...

Each COURSE_DIR is one LaneReport output folder (castle_control.csv, modules.csv, chunks.csv, gate.json). Everything printed comes from those
measured files; the Rule 20 baseline is the measured campaign 41201387807 (three-seed lane means, consolidate job P3 tables), embedded below.
Targets are printed as targets, never as measurements. The machine-readable rows (prefixed R21CSV) are also written to OUT_DIR as CSV, with the
graphs (PNG) when matplotlib is available. Reporting only: nothing here rejects a castle or changes generation.
"""
import csv, json, math, os, sys
from collections import OrderedDict, defaultdict

MODES = ["NORMAL", "PUNISHER", "NGH"]
# Rule 20 campaign 41201387807, consolidate job P3 (mean of 3 courses): terrain, hazards, enemies, interaction, total per castle
R20 = {
    "NORMAL": [[412.9, 76.5, 42.33, 13.9, 541.0], [425.5, 143.9, 25.08, 12.8, 604.4], [444.1, 102.2, 32.0, 18.7, 594.0], [461.9, 121.9, 46.08, 24.5, 649.8],
               [400.3, 111.5, 50.42, 20.8, 578.4], [430.9, 135.5, 52.79, 24.6, 638.1], [452.3, 129.7, 59.58, 26.1, 662.0], [481.4, 159.8, 54.25, 31.3, 720.8],
               [468.8, 150.2, 70.25, 33.5, 717.1], [467.0, 168.9, 80.58, 38.2, 748.0]],
    "PUNISHER": [[411.5, 144.4, 73.0, 25.7, 646.9], [438.5, 146.0, 66.71, 25.7, 672.6], [497.0, 134.5, 53.25, 28.1, 710.5], [473.3, 151.7, 68.75, 30.5, 720.9],
                 [461.3, 153.3, 81.83, 34.3, 727.2], [467.3, 148.4, 94.17, 33.5, 735.4], [480.5, 163.4, 87.25, 36.4, 759.6], [481.1, 154.3, 92.83, 38.3, 759.6],
                 [508.8, 166.0, 96.83, 38.5, 804.2], [495.1, 185.3, 94.83, 38.7, 809.9]],
    "NGH": [[492.5, 179.8, 118.83, 37.1, 822.6], [457.5, 188.8, 103.81, 35.3, 780.7], [538.5, 166.9, 73.58, 34.6, 810.9], [532.2, 185.3, 97.83, 37.8, 850.8],
            [478.8, 196.3, 130.33, 37.8, 840.0], [500.2, 211.8, 112.92, 39.3, 860.2], [511.2, 195.1, 125.92, 38.7, 867.7], [516.2, 218.8, 119.17, 40.0, 888.8],
            [556.0, 206.8, 132.42, 39.7, 930.5], [537.2, 230.9, 131.71, 39.7, 935.8]],
}
LEVEL = {"NORMAL": 1.0, "PUNISHER": 1.15, "NGH": 1.3225}
def target(mode, n): return 560.0 * LEVEL[mode] * 1.033 ** (min(10, n) - 1)

def pacing(g):
    if g is None or (isinstance(g, float) and math.isnan(g)): return ""
    p = 100 * g
    if 3.0 <= p <= 3.6: return "IDEAL"
    if 2.5 <= p < 3.0: return "MINOR_LOW"
    if 3.6 < p <= 4.5: return "MINOR_HIGH"
    if 1.5 <= p < 2.5 or 4.5 < p <= 6.0: return "MODERATE"
    return "SERIOUS"

def num(v, d=float("nan")):
    try: return float(v)
    except (TypeError, ValueError): return d

def rows(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p): return []
    with open(p, newline="") as f: return list(csv.DictReader(f))

# module kind classes (CourseGenerator.kindClass) and refusal counters -> rejection classes (directive section 9)
TERRAIN_KINDS = {"CRUMBLE", "MOVER_H", "MOVER_V", "PAD", "CABLE", "SWING", "GRAB", "SPRING", "SEESAW", "BRIDGE", "RAMP", "MOVER_Z", "ELEV_FALL", "SLIDE_FALL"}
HAZARD_KINDS = {"HAZ", "TRAP", "MORTAR", "DROPS"}
PLAIN_KINDS = {"HOP", "STAIRS", "ROPE"}
REJECT = [("refused_layer", "GEOMETRY"), ("refused_hazard", "FAIRNESS"), ("refused_plan", "REACHABILITY"), ("refused_window", "FAIRNESS"),
          ("refused_conflict", "ENCOUNTER_CONFLICT"), ("refused_threat", "FAIRNESS")]
CLASSES = ["GEOMETRY", "REACHABILITY", "SOLVER", "FAIRNESS", "ENCOUNTER_CONFLICT", "VARIETY", "PACING_CORRECTION", "CAPACITY", "COMPUTATIONAL_LIMIT", "OTHER"]

def course(d):
    g = json.load(open(os.path.join(d, "gate.json"))) if os.path.exists(os.path.join(d, "gate.json")) else {}
    cc = [r for r in rows(d, "castle_control.csv") if r.get("status", "").startswith("comp") or r.get("status") == "in_progress"]
    if not cc: return None
    run = cc[0]["run"]; mode = run.split("-s")[0]; seed = run.split("-s")[-1]
    mods = rows(d, "modules.csv"); chunks = rows(d, "chunks.csv")
    per = defaultdict(lambda: defaultdict(float))
    for m in mods:
        if m.get("final_attempt") != "true" or m.get("role") == "STRUCTURE": continue
        n = max(1, 1 + int(num(m["y"], 0) // 500)); c = per[n]; k = m["kind"]; ok = m["accepted"] == "true"
        c["attempted"] += 1
        if ok:
            c["accepted"] += 1; c["dem_acc"] += num(m["difficulty"], 0); c["n_acc"] += 1
            c["acc_terrain" if k in TERRAIN_KINDS else "acc_hazard" if k in HAZARD_KINDS else "acc_plain" if k in PLAIN_KINDS else "acc_other"] += 1
            if m["role"] == "FALLBACK": c["fallback_accepted"] += 1
        else:
            c["refused"] += 1; c["dem_ref"] += num(m["difficulty"], 0)
            cls = None
            for col, name in REJECT:
                if num(m.get(col), 0) > 0: cls = name; break
            c["rej_" + (cls or "OTHER")] += 1
        c["dem_all"] += num(m["difficulty"], 0)
    for ch in chunks:
        n = int(num(ch.get("castle"), 1)); per[n]["gen_s"] += num(ch.get("gen_wall_s"), 0); per[n]["solve_s"] += num(ch.get("solve_wall_s"), 0)
        per[n]["slice_failures"] += num(ch.get("failed_attempts"), 0)
    out = []
    for r in cc:
        n = int(r["castle"])
        if n < 1 or n > 10: continue
        c = per[n]
        out.append(OrderedDict([
            ("run", run), ("mode", mode), ("seed", seed), ("castle", n), ("status", r["status"]),
            ("terrain", num(r["lane_terrain"])), ("hazards", num(r["lane_hazards"])), ("enemies", num(r["lane_enemies"])), ("interaction", num(r["lane_interaction"])),
            ("mitigation", num(r["lane_mitigation"])), ("total", num(r["lane_total"])), ("est_total", num(r["est_total"])), ("target", target(mode, n)),
            ("planned", num(r.get("planned_total"))),
            ("base_T", num(r["factor_terrain"])), ("base_H", num(r["factor_hazards"])), ("base_E", num(r["factor_enemies"])), ("base_X", num(r["factor_interaction"])),
            ("req_T", num(r.get("reqbase_terrain"))), ("req_H", num(r.get("reqbase_hazards"))), ("req_E", num(r.get("reqbase_enemies"))), ("req_X", num(r.get("reqbase_interaction"))),
            ("last_T", num(r.get("last_terrain"))), ("last_H", num(r.get("last_hazards"))), ("last_E", num(r.get("last_enemies"))), ("last_X", num(r.get("last_interaction"))),
            ("saturated", r.get("saturated", "")), ("action", r.get("controller_action", "")), ("reversals", r.get("reversals", "")), ("ineffective", r.get("ineffective_at_bound", "")),
            ("hazard_constraint", r.get("hazard_constraint", "")), ("hazard_acceptance", num(r.get("hazard_acceptance"))),
            ("attempted", c["attempted"]), ("accepted", c["accepted"]), ("refused", c["refused"]), ("fallback_accepted", c["fallback_accepted"]),
            *[("rej_" + k, c["rej_" + k]) for k in CLASSES],
            ("demand_accepted", c["dem_acc"] / c["n_acc"] if c["n_acc"] else float("nan")), ("demand_refused", c["dem_ref"] / c["refused"] if c["refused"] else float("nan")),
            ("acc_terrain", c["acc_terrain"]), ("acc_hazard", c["acc_hazard"]), ("acc_plain", c["acc_plain"]), ("acc_other", c["acc_other"]),
            ("slice_failures", c["slice_failures"]), ("gen_s", c["gen_s"]), ("solve_s", c["solve_s"]),
            ("gate_passes", g.get("passes", ""))]))
    # growth and pacing per course (castle-to-castle, against the immediately preceding castle)
    by = {o["castle"]: o for o in out}
    for o in out:
        p = by.get(o["castle"] - 1)
        o["growth"] = (o["total"] / p["total"] - 1) if p and p["total"] > 0 else float("nan")
        o["pacing"] = pacing(o["growth"])
        # NGH capacity: CAPACITY / COMPUTATIONAL_LIMIT from the hazard control of the castle
        if o["hazard_constraint"] == "low_acceptance": o["rej_CAPACITY"] = 1
        if o["hazard_constraint"] == "computational_bound": o["rej_COMPUTATIONAL_LIMIT"] = 1
    return out

def f(v, nd=1):
    if isinstance(v, float):
        return "" if math.isnan(v) else f"{v:.{nd}f}"
    return str(v)

def main():
    out_dir, dirs = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    allr = []
    for d in sorted(dirs):
        try:
            c = course(d)
            if c: allr += c
        except Exception as e:
            print(f"rule21_report: {d}: {e}")
    if not allr: print("rule21_report: no castle_control.csv found"); return
    keys = list(allr[0].keys())
    with open(os.path.join(out_dir, "rule21_castles.csv"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(keys)
        for r in allr: w.writerow([f(r[k], 4) for k in keys])
    print("# Rule 21 A/B report (measured; Rule 20 = campaign 41201387807 means)\n")
    print("R21CSV " + ",".join(keys))
    for r in allr: print("R21CSV " + ",".join(f(r[k], 3) for k in keys))
    mean = lambda xs: sum(xs) / len(xs) if xs else float("nan")
    M = {}
    for m in MODES:
        rs = [r for r in allr if r["mode"] == m and r["status"].startswith("comp")]
        if not rs: continue
        M[m] = {n: [r for r in rs if r["castle"] == n] for n in range(1, 11)}
    # A. castle difficulty
    print("\n## A. Castle difficulty: Rule 20 mean / Rule 21 mean / Rule 21 target / difference R21 - R20 (points, %)\n")
    for m, by in M.items():
        print(f"### {m}\n| castle | R20 | R21 | target | R21 vs target | R21 - R20 | % |\n|---|---|---|---|---|---|---|")
        for n in range(1, 11):
            v = [r["total"] for r in by[n]]; a = mean(v); b = R20[m][n - 1][4]; t = target(m, n)
            print(f"| {n} | {b:.1f} | {a:.1f} ({len(v)}) | {t:.1f} | {100 * (a / t - 1):+.1f}% | {a - b:+.1f} | {100 * (a / b - 1):+.1f}% |")
        print()
    # B. castle-to-castle progression
    print("## B. Castle-to-castle growth (target +3.3 %): Rule 20 mean, Rule 21 mean, Rule 21 per seed, classification\n")
    for m, by in M.items():
        seeds = sorted({r["seed"] for c in by.values() for r in c})
        print(f"### {m}\n| step | R20 | R21 mean | class | " + " | ".join(f"seed {s}" for s in seeds) + " |\n|---|---|---|---|" + "---|" * len(seeds))
        for n in range(2, 11):
            a0, a1 = mean([r["total"] for r in by[n - 1]]), mean([r["total"] for r in by[n]]); g = a1 / a0 - 1
            g20 = R20[m][n - 1][4] / R20[m][n - 2][4] - 1
            ps = []
            for s in seeds:
                x = [r for r in by[n] if r["seed"] == s]; ps.append(f"{100 * x[0]['growth']:+.1f}% {x[0]['pacing']}" if x and not math.isnan(x[0]["growth"]) else "")
            print(f"| {n - 1}->{n} | {100 * g20:+.1f}% | {100 * g:+.1f}% | {pacing(g)} | " + " | ".join(ps) + " |")
        print()
    # C. separation
    print("## C. Mode separation per castle (target about +15 %): Rule 20 / Rule 21\n")
    for lo, hi in (("NORMAL", "PUNISHER"), ("PUNISHER", "NGH")):
        if lo in M and hi in M:
            print(f"### {hi} over {lo}\n| castle | R20 | R21 |\n|---|---|---|")
            s21 = []
            for n in range(1, 11):
                a = mean([r["total"] for r in M[hi][n]]) / mean([r["total"] for r in M[lo][n]]) - 1; s21.append(a)
                print(f"| {n} | {100 * (R20[hi][n - 1][4] / R20[lo][n - 1][4] - 1):+.1f}% | {100 * a:+.1f}% |")
            print(f"R21 mean {100 * mean(s21):+.1f}%, min {100 * min(s21):+.1f}%, late (8-10) {100 * mean(s21[7:]):+.1f}%\n")
    # D. categories
    print("## D. Category breakdown per castle, Rule 21 mean (Rule 20 mean): terrain, hazards, enemies, interaction, mitigation, total\n")
    for m, by in M.items():
        print(f"### {m}\n| castle | terrain | hazards | enemies | interaction | mitigation | total |\n|---|---|---|---|---|---|---|")
        for n in range(1, 11):
            c = by[n]; q = [mean([r[k] for r in c]) for k in ("terrain", "hazards", "enemies", "interaction", "mitigation", "total")]; b = R20[m][n - 1]
            print(f"| {n} | {q[0]:.0f} ({b[0]:.0f}) | {q[1]:.0f} ({b[1]:.0f}) | {q[2]:.0f} ({b[2]:.0f}) | {q[3]:.0f} ({b[3]:.0f}) | {q[4]:.1f} | {q[5]:.0f} ({b[4]:.0f}) |")
        print()
    # E. controller behaviour
    print("## E. Controller behaviour per mode (sums / means over courses and castles)\n")
    print("| mode | castles | saturated castles | reversals | ineffective at bound | eased (any -) | raised (any +) | hazard constraint not none | mean base T/H/E/X | fallback accepted | refused / attempted |\n|---|---|---|---|---|---|---|---|---|---|---|")
    for m, by in M.items():
        rs = [r for c in by.values() for r in c]
        print(f"| {m} | {len(rs)} | {sum(1 for r in rs if r['saturated'])} | {sum(len([x for x in r['reversals'].split(';') if x]) for r in rs)} | "
              f"{sum(len([x for x in r['ineffective'].split(';') if x]) for r in rs)} | {sum(1 for r in rs if '-' in r['action'])} | {sum(1 for r in rs if '+' in r['action'])} | "
              f"{sum(1 for r in rs if r['hazard_constraint'] not in ('', 'none'))} | {' / '.join(f'{mean([r[k] for r in rs]):.2f}' for k in ('base_T', 'base_H', 'base_E', 'base_X'))} | "
              f"{sum(r['fallback_accepted'] for r in rs):.0f} | {sum(r['refused'] for r in rs):.0f} / {sum(r['attempted'] for r in rs):.0f} |")
    print("\n### Rejections by class (module attempts of the accepted slice attempts; a module counts once, by its first refusal)\n")
    print("| mode | " + " | ".join(CLASSES) + " |\n|---|" + "---|" * len(CLASSES))
    for m, by in M.items():
        rs = [r for c in by.values() for r in c]
        print(f"| {m} | " + " | ".join(f"{sum(r['rej_' + k] for r in rs):.0f}" for k in CLASSES) + " |")
    # pacing class counts
    print("\n## Pacing classes (per course step)\n\n| mode | IDEAL | MINOR_LOW | MINOR_HIGH | MODERATE | SERIOUS |\n|---|---|---|---|---|---|")
    for m, by in M.items():
        rs = [r for c in by.values() for r in c if r["pacing"]]
        print(f"| {m} | " + " | ".join(str(sum(1 for r in rs if r["pacing"] == k)) for k in ("IDEAL", "MINOR_LOW", "MINOR_HIGH", "MODERATE", "SERIOUS")) + " |")
    print("\n## Generation cost per mode (sum over courses)\n\n| mode | gen s | solve s | slice attempt failures |\n|---|---|---|---|")
    for m, by in M.items():
        rs = [r for c in by.values() for r in c]
        print(f"| {m} | {sum(r['gen_s'] for r in rs):.0f} | {sum(r['solve_s'] for r in rs):.0f} | {sum(r['slice_failures'] for r in rs):.0f} |")
    charts(M, out_dir)

def charts(M, out_dir):
    try:
        import matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
    except Exception as e:
        print(f"(charts skipped: {e})"); return
    mean = lambda xs: sum(xs) / len(xs) if xs else float("nan")
    cs = list(range(1, 11)); col = {"NORMAL": "#2a6fdb", "PUNISHER": "#d9822b", "NGH": "#b8336a"}
    def save(fig, name): fig.tight_layout(); fig.savefig(os.path.join(out_dir, name), dpi=110); plt.close(fig)
    fig, ax = plt.subplots(figsize=(9, 5))
    for m, by in M.items():
        ax.plot(cs, [R20[m][n - 1][4] for n in cs], "--", color=col[m], label=f"{m} Rule 20 (measured)")
        ax.plot(cs, [mean([r["total"] for r in by[n]]) for n in cs], "-o", color=col[m], label=f"{m} Rule 21 (measured)")
    ax.set_xlabel("castle"); ax.set_ylabel("difficulty (lane total, mean of seeds)"); ax.set_title("1. Castle difficulty, Rule 20 vs Rule 21 (measured)"); ax.legend(fontsize=7); save(fig, "r21_1_difficulty.png")
    fig, ax = plt.subplots(figsize=(9, 5)); w = 0.13; i = 0
    for m, by in M.items():
        g20 = [100 * (R20[m][n - 1][4] / R20[m][n - 2][4] - 1) for n in cs[1:]]
        g21 = [100 * (mean([r["total"] for r in by[n]]) / mean([r["total"] for r in by[n - 1]]) - 1) for n in cs[1:]]
        ax.bar([x + (i - 2.5) * w for x in cs[1:]], g20, w, color=col[m], alpha=0.35, label=f"{m} Rule 20")
        ax.bar([x + (i - 1.5) * w for x in cs[1:]], g21, w, color=col[m], label=f"{m} Rule 21"); i += 2
    ax.axhspan(3.0, 3.6, color="#8c8", alpha=0.25, label="IDEAL 3.0-3.6 %"); ax.axhline(0, color="#555", lw=0.8)
    ax.set_xlabel("castle (growth from the castle before)"); ax.set_ylabel("%"); ax.set_title("2. Castle-to-castle growth (measured)"); ax.legend(fontsize=7, ncol=2); save(fig, "r21_2_growth.png")
    fig, ax = plt.subplots(figsize=(9, 5))
    for lo, hi, c in (("NORMAL", "PUNISHER", "#d9822b"), ("PUNISHER", "NGH", "#b8336a")):
        if lo in M and hi in M:
            ax.plot(cs, [100 * (R20[hi][n - 1][4] / R20[lo][n - 1][4] - 1) for n in cs], "--", color=c, label=f"{hi}/{lo} Rule 20")
            ax.plot(cs, [100 * (mean([r["total"] for r in M[hi][n]]) / mean([r["total"] for r in M[lo][n]]) - 1) for n in cs], "-o", color=c, label=f"{hi}/{lo} Rule 21")
    ax.axhline(15, color="#888", ls=":", label="design objective 15 %"); ax.set_xlabel("castle"); ax.set_ylabel("%"); ax.set_title("3. Mode separation (measured)"); ax.legend(fontsize=7); save(fig, "r21_3_separation.png")
    fig, axs = plt.subplots(1, len(M), figsize=(12, 4), sharey=True)
    for ax, (m, by) in zip(axs if len(M) > 1 else [axs], M.items()):
        bottom = [0] * 10
        for k, c in (("terrain", "#7a9e7e"), ("hazards", "#c0504d"), ("enemies", "#8064a2"), ("interaction", "#f79646")):
            v = [mean([r[k] for r in by[n]]) for n in cs]; ax.bar(cs, v, bottom=bottom, color=c, label=k); bottom = [a + b for a, b in zip(bottom, v)]
        ax.plot(cs, [R20[m][n - 1][4] for n in cs], "k--", lw=1, label="Rule 20 total"); ax.set_title(f"4. {m} categories (Rule 21)"); ax.set_xlabel("castle")
    axs[0].legend(fontsize=7); save(fig, "r21_4_categories.png")
    fig, axs = plt.subplots(1, len(M), figsize=(12, 4), sharey=True)
    for ax, (m, by) in zip(axs if len(M) > 1 else [axs], M.items()):
        for k, c in (("base_T", "#7a9e7e"), ("base_H", "#c0504d"), ("base_E", "#8064a2"), ("base_X", "#f79646")):
            ax.plot(cs, [mean([r[k] for r in by[n]]) for n in cs], "-o", color=c, label=k)
        ax.axhline(1, color="#888", lw=0.8); ax.set_title(f"5. {m} base factors (measured state)"); ax.set_xlabel("castle")
    axs[0].legend(fontsize=7); save(fig, "r21_5_factors.png")
    fig, axs = plt.subplots(1, len(M), figsize=(12, 4), sharey=True)
    for ax, (m, by) in zip(axs if len(M) > 1 else [axs], M.items()):
        att = [sum(r["attempted"] for r in by[n]) or 1 for n in cs]
        for k, c in (("rej_GEOMETRY", "#4f81bd"), ("rej_FAIRNESS", "#c0504d"), ("rej_REACHABILITY", "#9bbb59"), ("rej_ENCOUNTER_CONFLICT", "#8064a2"), ("fallback_accepted", "#f79646")):
            ax.plot(cs, [100 * sum(r[k] for r in by[n]) / a for n, a in zip(cs, att)], "-o", color=c, label=k.replace("rej_", ""))
        ax.set_title(f"6. {m} rejections and fallbacks (% of module attempts)"); ax.set_xlabel("castle")
    axs[0].legend(fontsize=7); save(fig, "r21_6_rejections.png")
    print("\n(charts: r21_1_difficulty.png ... r21_6_rejections.png, from measured courses only)")

if __name__ == "__main__":
    main()
