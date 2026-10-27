#!/usr/bin/env python3
"""Acceptance-batch summary across LaneReport course folders (owner authorisation 2026-10-28: 30 fresh courses, 10 per mode, 5,000 m).

Usage: batch_summary.py OUT_DIR COURSE_DIR [COURSE_DIR ...]
  each COURSE_DIR is one LaneReport output folder (gate.json, castle_control.csv, solver.csv, encounters.csv, chunks.csv)

Writes, and prints to the job log:
  reliability.csv    one row per course: gate verdicts, castles reached, solver verdict tally, witness setbacks, generation and solver time
  curves.csv         per mode and castle: mean, sd, min, max and course count of every lane (terrain, hazards, enemies, interaction, total)
                     and of hazard acceptance, Enforcers and avalanches
  curves_<lane>.png  the mode-average curve with its seed-to-seed min-max band, one chart per lane
  BATCH.md           the acceptance tally against the owner's standard and the tables above
A course whose folder is missing (named by EXPECT_MODES / EXPECT_SEEDS) or whose gate did not pass is kept in every table as a failure; nothing is
dropped or replaced.
"""
import csv, json, math, os, sys, collections
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

MODES = ["NORMAL", "PUNISHER", "NGH"]
COLORS = {"NORMAL": "#2a78d6", "PUNISHER": "#eb6834", "NGH": "#6250d6"}
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
plt.rcParams.update({"figure.facecolor": SURF, "axes.facecolor": SURF, "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2,
                     "ytick.color": INK2, "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.spines.top": False,
                     "axes.spines.right": False, "font.size": 10, "lines.linewidth": 2})
LANES = ["lane_terrain", "lane_hazards", "lane_enemies", "lane_interaction", "lane_total", "hazard_acceptance", "enforcers", "avalanches"]
VERDICTS = ["PROVEN_SOLVABLE", "PROVEN_UNSOLVABLE", "UNKNOWN", "FAILED_VALIDATION"]


def rows(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p): return []
    with open(p, newline="") as f: return list(csv.DictReader(f))


def num(v, default=0.0):
    try:
        x = float(v)
        return x if math.isfinite(x) else default
    except (TypeError, ValueError): return default


def mode_of(run): return run.split("-s")[0]


def stats(v):
    if not v: return (0.0, 0.0, 0.0, 0.0)
    m = sum(v) / len(v)
    sd = math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1)) if len(v) > 1 else 0.0
    return (m, sd, min(v), max(v))


def course(d):
    gate = {}
    p = os.path.join(d, "gate.json")
    if os.path.exists(p):
        with open(p) as f: gate = json.load(f)
    run = gate.get("run") or os.path.basename(d.rstrip("/"))
    sol = rows(d, "solver.csv")
    tally = collections.Counter(r.get("verdict", "") or "NONE" for r in sol)
    causes = collections.Counter(r.get("cause", "") for r in sol if r.get("verdict") != "PROVEN_SOLVABLE")
    setbacks = sum(int(num(r.get("witness_setbacks"))) for r in sol)
    cc = rows(d, "castle_control.csv")
    castles = sorted({int(num(r["castle"])) for r in cc if r.get("status", "").startswith("comp")})
    top = max((num(r.get("y1")) for r in sol), default=0.0)
    ch = rows(d, "chunks.csv")
    gen = sum(num(r.get("gen_wall_s")) for r in ch); solve = sum(num(r.get("solve_wall_s")) for r in ch)
    fails = sum(int(num(r.get("failed_attempts"))) for r in ch)
    heap = max((num(r.get("heap_used_bytes")) for r in ch), default=0.0) / 1e6
    enc = collections.defaultdict(lambda: [0.0, 0.0])
    for r in rows(d, "encounters.csv"):
        c = int(num(r.get("castle")))
        enc[c][0] += num(r.get("enforcers_yellow")) + num(r.get("enforcers_orange")) + num(r.get("enforcers_red"))
        enc[c][1] += num(r.get("avalanches"))
    per = {}
    for r in cc:
        c = int(num(r["castle"]))
        if c < 1: continue
        v = {k: num(r.get(k)) for k in LANES[:6]}
        v["enforcers"], v["avalanches"] = enc[c]
        per[c] = v
    return {"dir": d, "run": run, "mode": mode_of(run), "gate": gate, "tally": tally, "causes": causes, "setbacks": setbacks, "castles": castles,
            "top": top, "gen_s": gen, "solve_s": solve, "slices": len(ch), "failed_attempts": fails, "heap_mb": heap, "per": per}


def main():
    out, dirs = sys.argv[1], sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    cs = [course(d) for d in dirs]
    # a course job that died before writing its folder (timeout, crash) is still a course of the batch: EXPECT_MODES / EXPECT_SEEDS (the dispatch
    # inputs, JSON lists) name every course, and a missing one is kept as a failed row
    if os.environ.get("EXPECT_MODES") and os.environ.get("EXPECT_SEEDS"):
        have = {c["run"] for c in cs}
        for m in json.loads(os.environ["EXPECT_MODES"]):
            for sd in json.loads(os.environ["EXPECT_SEEDS"]):
                run = f"{MODES[int(m)]}-s{sd}"
                if run not in have: cs.append({**course("/nonexistent/" + run), "run": run, "mode": MODES[int(m)]})
    cs = sorted(cs, key=lambda c: (MODES.index(c["mode"]) if c["mode"] in MODES else 9, c["run"]))
    md = ["# Acceptance batch summary", ""]

    # reliability, one row per course
    hdr = ["run", "mode", "passes", "generation", "solver", "geometric_integrity", "castles_complete", "top_m", "slices"] + VERDICTS + \
          ["non_proven_causes", "witness_setbacks", "failed_gen_attempts", "gen_s", "solve_s", "peak_heap_mb"]
    tab = []
    for c in cs:
        g = c["gate"]
        tab.append([c["run"], c["mode"], g.get("passes", False), g.get("generation", "MISSING"), g.get("solver", "MISSING"),
                    g.get("geometric_integrity", "MISSING"), len(c["castles"]), round(c["top"]), c["slices"]] + [c["tally"].get(v, 0) for v in VERDICTS] +
                   [";".join(f"{k}:{n}" for k, n in sorted(c["causes"].items())), c["setbacks"], c["failed_attempts"], round(c["gen_s"]),
                    round(c["solve_s"]), round(c["heap_mb"])])
    with open(os.path.join(out, "reliability.csv"), "w", newline="") as f: w = csv.writer(f); w.writerow(hdr); w.writerows(tab)

    # the owner's acceptance standard
    n = len(cs)
    gen_fail = sum(1 for c in cs if c["gate"].get("generation") != "COMPLETE")
    unsolv = sum(c["tally"].get("PROVEN_UNSOLVABLE", 0) for c in cs)
    unknown = sum(c["tally"].get("UNKNOWN", 0) + c["tally"].get("FAILED_VALIDATION", 0) + c["tally"].get("NONE", 0) for c in cs)
    geo = sum(1 for c in cs if not str(c["gate"].get("geometric_integrity", "")).startswith("CLEAN"))
    castles10 = sum(1 for c in cs if len(c["castles"]) >= 10)
    setb = sum(1 for c in cs if c["setbacks"] > 0)
    passes = sum(1 for c in cs if c["gate"].get("passes") is True)
    md += ["## Acceptance standard", "", "| check | result |", "|---|---|",
           f"| courses reported | {n} |", f"| gate passes | {passes} of {n} |", f"| generation failures | {gen_fail} |",
           f"| PROVEN_UNSOLVABLE slices | {unsolv} |", f"| UNKNOWN / FAILED_VALIDATION / missing-verdict slices | {unknown} |",
           f"| courses with geometric-integrity violations | {geo} |", f"| courses with all 10 castles complete | {castles10} of {n} |",
           f"| courses whose witness took a setback | {setb} |", ""]
    md += ["## Per course", "", "| " + " | ".join(hdr[:9] + ["PROVEN_SOLVABLE", "non-proven", "gen s", "solve s"]) + " |", "|" + "---|" * 13]
    for t in tab: md.append("| " + " | ".join(str(x) for x in t[:10] + [sum(t[10:13]), t[16], t[17]]) + " |")
    md.append("")

    # curves: per mode and castle, mean / sd / min / max over the courses that completed that castle
    agg = collections.defaultdict(lambda: collections.defaultdict(list))
    for c in cs:
        for k, v in c["per"].items():
            if k in c["castles"]:
                for lane in LANES: agg[(c["mode"], k)][lane].append(v[lane])
    ch = ["mode", "castle", "courses"] + [f"{l}_{s}" for l in LANES for s in ("mean", "sd", "min", "max")]
    ct = []
    for (m, k) in sorted(agg, key=lambda x: (MODES.index(x[0]) if x[0] in MODES else 9, x[1])):
        a = agg[(m, k)]; row = [m, k, len(a["lane_total"])]
        for l in LANES: row += [round(x, 3) for x in stats(a[l])]
        ct.append(row)
    with open(os.path.join(out, "curves.csv"), "w", newline="") as f: w = csv.writer(f); w.writerow(ch); w.writerows(ct)
    for l in LANES:
        fig, ax = plt.subplots(figsize=(8, 4.2))
        for m in MODES:
            ks = sorted(k for (mm, k) in agg if mm == m)
            if not ks: continue
            s = [stats(agg[(m, k)][l]) for k in ks]
            ax.fill_between(ks, [x[2] for x in s], [x[3] for x in s], color=COLORS[m], alpha=0.15, linewidth=0)
            ax.plot(ks, [x[0] for x in s], color=COLORS[m], marker="o", markersize=5, label=f"{m} mean (band: min-max over seeds)")
        ax.set_xlabel("castle"); ax.set_ylabel(l.replace("lane_", "")); ax.set_title(f"{l.replace('lane_', '')} per castle, mode average", loc="left")
        ax.set_xticks(range(1, 11))
        if ax.lines: ax.legend(frameon=False, fontsize=8)
        fig.tight_layout(); fig.savefig(os.path.join(out, f"curves_{l}.png"), dpi=130); plt.close(fig)

    # ordering check: the mode averages of lane_total ascend castle to castle and stay separated mode to mode
    md += ["## Difficulty curves (lane_total mean ± sd per castle)", "", "| castle | " + " | ".join(MODES) + " |", "|---|" + "---|" * len(MODES)]
    for k in range(1, 11):
        cells = []
        for m in MODES:
            a = agg.get((m, k))
            cells.append("-" if not a else "{:.1f} ± {:.1f} ({:d})".format(*stats(a["lane_total"])[:2], len(a["lane_total"])))
        md.append(f"| {k} | " + " | ".join(cells) + " |")
    md.append("")
    mean = {m: [stats(agg[(m, k)]["lane_total"])[0] for k in range(1, 11) if (m, k) in agg] for m in MODES}
    for m in MODES:
        v = mean[m]
        if not v: md.append(f"- {m}: no completed castles"); continue
        drops = [i + 2 for i in range(len(v) - 1) if v[i + 1] < v[i]]
        md.append(f"- {m}: castle means " + ("ascend at every castle" if not drops else "drop at castle(s) " + ", ".join(map(str, drops))))
    for a, b in zip(MODES, MODES[1:]):
        ks = [k for k in range(1, 11) if (a, k) in agg and (b, k) in agg]
        if not ks: md.append(f"- {b} above {a}: no castle completed in both"); continue
        bad = [k for k in ks if stats(agg[(b, k)]["lane_total"])[0] <= stats(agg[(a, k)]["lane_total"])[0]]
        md.append(f"- {b} above {a}: " + ("at every castle" if not bad else "not at castle(s) " + ", ".join(map(str, bad))))
    md.append("")
    md += ["## Encounters and performance per mode", "", "| mode | Enforcers / course | avalanches / course | hazard acceptance | gen s / course | solve s / course | peak heap MB |",
           "|---|---|---|---|---|---|---|"]
    for m in MODES:
        mc = [c for c in cs if c["mode"] == m]
        if not mc: continue
        ef = [sum(v["enforcers"] for v in c["per"].values()) for c in mc]; av = [sum(v["avalanches"] for v in c["per"].values()) for c in mc]
        ha = [v["hazard_acceptance"] for c in mc for v in c["per"].values()]
        f = lambda x: "{:.1f} ± {:.1f}".format(*stats(x)[:2])
        md.append(f"| {m} | {f(ef)} | {f(av)} | {stats(ha)[0]:.2f} | {f([c['gen_s'] for c in mc])} | {f([c['solve_s'] for c in mc])} | {max(c['heap_mb'] for c in mc):.0f} |")
    md.append("")
    text = "\n".join(md)
    with open(os.path.join(out, "BATCH.md"), "w") as f: f.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
