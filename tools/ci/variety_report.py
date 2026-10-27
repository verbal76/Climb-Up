#!/usr/bin/env python3
"""Gameplay variety, encounters and repetition across a difficulty campaign (owner amendments 2026-10-27). Companion to lane_report.py.

    variety_report.py <folder with one sub-folder per job> <out dir> [<baseline folder with one sub-folder per job>]

Reads every job's links.csv (all runs: the like-for-like measure, family level), its variety_*.csv / variety_run.json (the per-course detail
LaneReport writes: variants, Enforcer tiers), encounters.csv, traversal.csv and windows.csv. A baseline folder (an earlier campaign's job
artifacts, e.g. before the Enforcer and avalanche) is measured from its links.csv with the same formulas, so the comparison does not depend on
files the baseline does not have.

Variety (family level, from links.csv): each route link is classified by how the move is made (rope, cable, trampoline, spring, swing,
moving, crumbling, seesaw, ramp, long jump, jump), its hazard families (saw, pendulum, projectile, spike mat, spiked block, crusher,
avalanche) and its enemy families (crab, bee, skull, blob, enforcer); its signature is those three together. The variety score of a rolling
100 m block (four 25 m windows) is the mean of evenness (signature entropy / log2(links)), novelty (share of links whose signature is not in
the previous 100 m) and run break-up (1 - (longest single-signature run - 1) / (links - 1)); a castle's score is the mean of the blocks that
start in it. The same definitions as core/src/test/.../sim/VarietyReport.java. Difficulty is the lane report's window total (terrain + hazards
+ enemies + interaction - mitigation), summed per castle. Nothing here feeds the game, and no target values are set.

Writes variety_report.pdf, variety_castles_all.csv, variety_compare.csv, encounters_all.csv, ropes_all.csv, variety_summary.json, VARIETY.md.
"""
import csv, json, math, os, sys
from collections import defaultdict, Counter

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
BASE = sys.argv[3] if len(sys.argv) > 3 and os.path.isdir(sys.argv[3]) else None
os.makedirs(OUT, exist_ok=True)
MODES = ["NORMAL", "PUNISHER", "NGH"]
MODE_COL = {"NORMAL": "#2e86c1", "PUNISHER": "#cb4335", "NGH": "#17202a"}
HAZ = {"SAW_H": "saw", "SAW_V": "saw", "PENDULUM": "pendulum", "CANNON": "projectile", "AIMED": "projectile", "MORTAR": "projectile",
       "SPIKE_TRAP": "spike_mat", "SPIKE_BLOCK": "spiked_block", "SPIKE_DROP": "crusher", "AVALANCHE": "avalanche"}
ENE = {"CRAB": "crab", "BEE": "bee", "SKULL": "skull", "BLOB": "blob", "ENFORCER": "enforcer"}
TERRAIN = ["rope", "cable", "trampoline", "spring", "swing", "moving", "crumbling", "seesaw", "ramp", "long_jump", "jump"]


def num(x):
    try:
        v = float(x)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def read(folder, name):
    rows = []
    if not folder:
        return rows
    for job in sorted(os.listdir(folder)):
        p = os.path.join(folder, job, name)
        if os.path.exists(p):
            with open(p, newline="") as fh:
                rows.extend(csv.DictReader(fh))
    return rows


def types(cell):
    cell = (cell or "").strip().strip("[]")
    return [t.strip() for t in cell.split(",") if t.strip()]


def terrain(a, b, ratio):
    if a in ("ROPE", "CABLE", "PAD", "SPRING", "SWING"):
        return {"ROPE": "rope", "CABLE": "cable", "PAD": "trampoline", "SPRING": "spring", "SWING": "swing"}[a]
    if b in ("MOVE_H", "MOVE_V", "MOVE_Z", "SWING") or a in ("MOVE_H", "MOVE_V", "MOVE_Z"):
        return "moving"
    if "CRUMBLE" in (a, b):
        return "crumbling"
    if "SEESAW" in (a, b):
        return "seesaw"
    if "RAMP" in (a, b):
        return "ramp"
    return "long_jump" if (ratio or 0) > 0.85 else "jump"


def links_of(folder):
    """run -> [link dicts in climbing order] (family level)."""
    out = defaultdict(list)
    for r in read(folder, "links.csv"):
        y0 = num(r["y0"])
        if y0 is None:
            continue
        hz = sorted({HAZ[t] for t in types(r["hazards"]) if t in HAZ})
        en = sorted({ENE[t] for t in types(r["enemies"]) if t in ENE})
        t = terrain(r["from_type"], r["to_type"], num(r["gap_ratio"]))
        out[r["run"]].append({"y0": y0, "y1": num(r["y1"]) or y0, "terrain": t, "haz": hz, "en": en,
                              "challenge": bool(hz or en or (num(r["terrain"]) or 0) >= 1.0),
                              "sig": t + "|" + "+".join(hz) + "|" + "+".join(en)})
    for run in out:
        out[run].sort(key=lambda l: l["y0"])
    return out


def entropy(xs):
    n = len(xs)
    if not n:
        return 0.0
    return -sum(c / n * math.log2(c / n) for c in Counter(xs).values())


def longest_run(xs):
    best = run = 0
    prev = None
    for x in xs:
        run = run + 1 if x == prev else 1
        best = max(best, run)
        prev = x
    return best


def block_score(sigs, before):
    n = len(sigs)
    if n < 2:
        return None
    even = entropy(sigs) / math.log2(n)
    runs = 1 - (longest_run(sigs) - 1) / (n - 1)
    if not before:
        return (even + runs) / 2
    novel = sum(1 for s in sigs if s not in before) / n
    return (even + novel + runs) / 3


def castle_of(y):
    return min(10, 1 + int(y // 500))


def variety(links):
    """castle -> {score, unique, entropy per lane, longest run, rope share, families}"""
    byw = defaultdict(list)
    for l in links:
        byw[int(l["y0"] // 25)].append(l)
    mw = max(byw) if byw else -1
    blocks = defaultdict(list)
    for w in range(0, mw - 2):
        sigs = [l["sig"] for q in range(w, w + 4) for l in byw.get(q, [])]
        before = {l["sig"] for q in range(w - 4, w) for l in byw.get(q, [])}
        s = block_score(sigs, before)
        if s is not None:
            blocks[castle_of(w * 25)].append(s)
    out = {}
    byc = defaultdict(list)
    for l in links:
        byc[castle_of(l["y0"])].append(l)
    for c, ls in byc.items():
        sigs = [l["sig"] for l in ls]
        mech = {"T:" + l["terrain"] for l in ls} | {"H:" + h for l in ls for h in l["haz"]} | {"E:" + e for l in ls for e in l["en"]}
        un, best, start = 0.0, 0.0, None
        for l in ls:
            if l["challenge"]:
                start = None
            else:
                start = l["y0"] if start is None else start
                best = max(best, l["y1"] - start)
        out[c] = {"score": float(np.mean(blocks[c])) if blocks.get(c) else None, "unique": len(mech), "links": len(ls),
                  "terrain_entropy": entropy([l["terrain"] for l in ls]), "hazard_entropy": entropy([h for l in ls for h in l["haz"]]),
                  "enemy_entropy": entropy([e for l in ls for e in l["en"]]), "signature_entropy": entropy(sigs),
                  "longest_signature_run": longest_run(sigs), "longest_terrain_run": longest_run([l["terrain"] for l in ls]),
                  "rope_share": sum(1 for l in ls if l["terrain"] == "rope") / len(ls), "longest_unchallenged_m": best,
                  "challenge_share": sum(1 for l in ls if l["challenge"]) / len(ls),
                  "terrain": Counter(l["terrain"] for l in ls), "hazards": Counter(h for l in ls for h in l["haz"]), "enemies": Counter(e for l in ls for e in l["en"]),
                  "mech": mech}
    return out


def ropes(links):
    gaps, last, run, best, n = [], None, 0, 0, 0
    for l in links:
        if l["terrain"] == "rope":
            n += 1; run += 1; best = max(best, run)
            if last is not None:
                gaps.append(l["y0"] - last)
            last = l["y1"]
        else:
            run = 0
    return {"rope_links": n, "share": n / len(links) if links else 0, "mean_spacing_m": float(np.mean(gaps)) if gaps else None,
            "min_spacing_m": min(gaps) if gaps else None, "spacings_under_10m": sum(1 for g in gaps if g < 10), "longest_consecutive": best}


def difficulty(folder):
    d = defaultdict(float)
    for w in read(folder, "windows.csv"):
        v = sum(num(w[k]) or 0 for k in ("terrain_adj", "hazard_adj", "enemy_adj", "interaction")) - (num(w["mitigation"]) or 0)
        d[(w["run"], int(w["castle"]))] += v
    return d


def mode_of(run):
    return run.split("-s")[0]


new_links, base_links = links_of(SRC), links_of(BASE)
new_var = {r: variety(ls) for r, ls in new_links.items()}
base_var = {r: variety(ls) for r, ls in base_links.items()}
new_diff, base_diff = difficulty(SRC), difficulty(BASE)
RUNS = sorted(new_var, key=lambda r: (MODES.index(mode_of(r)) if mode_of(r) in MODES else 9, r))

# ------------------------------------------------------------------ tables
rows = []
for r in RUNS:
    for c in sorted(new_var[r]):
        v = new_var[r][c]
        b = base_var.get(r, {}).get(c)
        rows.append([r, mode_of(r), c, v["links"], f"{v['score']:.3f}" if v["score"] is not None else "", v["unique"], f"{v['signature_entropy']:.3f}",
                     v["longest_signature_run"], v["longest_terrain_run"], f"{v['rope_share']:.3f}", f"{v['challenge_share']:.3f}", f"{v['longest_unchallenged_m']:.1f}",
                     f"{new_diff.get((r, c), 0):.1f}", f"{b['score']:.3f}" if b and b["score"] is not None else "", b["unique"] if b else "",
                     f"{b['rope_share']:.3f}" if b else "", f"{base_diff.get((r, c), 0):.1f}" if b else ""])
with open(os.path.join(OUT, "variety_compare.csv"), "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["run", "mode", "castle", "links", "variety_score", "unique_mechanics", "signature_entropy", "longest_signature_run", "longest_terrain_run",
                "rope_share", "challenge_share", "longest_unchallenged_m", "difficulty", "baseline_variety_score", "baseline_unique_mechanics", "baseline_rope_share", "baseline_difficulty"])
    w.writerows(rows)

for name, src in [("variety_castles_all.csv", "variety_castles.csv"), ("encounters_all.csv", "encounters.csv"), ("variety_rolling_all.csv", "variety_rolling.csv"), ("variety_windows_all.csv", "variety_windows.csv")]:
    t = read(SRC, src)
    if t:
        with open(os.path.join(OUT, name), "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(t[0].keys())); w.writeheader(); w.writerows(t)

trav = read(SRC, "traversal.csv")
rope_rows = []
for r in RUNS:
    rp = ropes(new_links[r]); bp = ropes(base_links[r]) if r in base_links else None
    picks = sum(int(t["rope_picks"] or 0) for t in trav if t["run"] == r); fbs = sum(int(t["rope_fallbacks"] or 0) for t in trav if t["run"] == r)
    rope_rows.append([r, rp["rope_links"], f"{rp['share']:.3f}", "" if rp["mean_spacing_m"] is None else f"{rp['mean_spacing_m']:.1f}",
                      "" if rp["min_spacing_m"] is None else f"{rp['min_spacing_m']:.1f}", rp["spacings_under_10m"], rp["longest_consecutive"], picks, fbs,
                      "" if not bp else bp["rope_links"], "" if not bp else f"{bp['share']:.3f}", "" if not bp or bp["mean_spacing_m"] is None else f"{bp['mean_spacing_m']:.1f}",
                      "" if not bp else bp["spacings_under_10m"], "" if not bp else bp["longest_consecutive"]])
with open(os.path.join(OUT, "ropes_all.csv"), "w", newline="") as fh:
    w = csv.writer(fh)
    w.writerow(["run", "rope_links", "rope_share", "mean_spacing_m", "min_spacing_m", "spacings_under_10m", "longest_consecutive_rope_links", "rope_module_picks", "rope_module_fallbacks",
                "baseline_rope_links", "baseline_rope_share", "baseline_mean_spacing_m", "baseline_spacings_under_10m", "baseline_longest_consecutive"])
    w.writerows(rope_rows)

# ------------------------------------------------------------------ findings (measured, not predictions)
enc = read(SRC, "encounters.csv")
summary = {"runs": {}, "flags": [], "seed_similarity": {}, "definition": __doc__.split("Variety (family level")[1].split("Writes")[0].strip()}
for r in RUNS:
    cs = new_var[r]
    seq = [(c, cs[c]["score"], new_diff.get((r, c), 0)) for c in sorted(cs)]
    for (c0, v0, d0), (c1, v1, d1) in zip(seq, seq[1:]):
        if v0 is not None and v1 is not None and d1 > d0 and v1 < v0 - 0.05:
            summary["flags"].append(f"{r} castle {c1}: difficulty up ({d0:.0f} -> {d1:.0f}) while variety fell ({v0:.3f} -> {v1:.3f})")
    e = [x for x in enc if x["run"] == r]
    tot = lambda k: sum(int(x[k] or 0) for x in e)
    summary["runs"][r] = {"castles": {c: {k: (v[k] if not isinstance(v[k], (Counter, set)) else (sorted(v[k]) if isinstance(v[k], set) else dict(v[k]))) for k in v} for c, v in cs.items()},
                          "ropes": ropes(new_links[r]),
                          "enforcers": {"yellow": tot("enforcers_yellow"), "orange": tot("enforcers_orange"), "red": tot("enforcers_red"), "kick_tries": tot("enf_kick_tries"), "kick_hits": tot("enf_kick_hits"),
                                        "swat_tries": tot("enf_swat_tries"), "swat_hits": tot("enf_swat_hits"), "retreats": tot("enf_retreats"), "falls": tot("enf_falls"), "unfair_refused": tot("enforcer_unfair"), "dropped": tot("enforcer_dropped")} if e else None,
                          "avalanches": {"placed": tot("avalanches"), "blocks_placed": tot("avalanche_blocks"), "fired": tot("avl_fired"), "skipped_cooldown": tot("avl_skipped_cooldown"), "blocks_released": tot("avl_blocks_released"),
                                         "blocks_near_player": tot("avl_blocks_near_player"), "blocks_in_traversal_area": tot("avl_blocks_in_traversal_area") if e and "avl_blocks_in_traversal_area" in e[0] else None,
                                         "block_knocks": tot("avl_block_knocks"), "hits": tot("avl_hits"), "unfair_refused": tot("avalanche_unfair"), "dropped": tot("avalanche_dropped")} if e else None}
for m in MODES:
    rs = [r for r in RUNS if mode_of(r) == m]
    for i in range(len(rs)):
        for j in range(i + 1, len(rs)):
            a = {l["sig"] for l in new_links[rs[i]]}; b = {l["sig"] for l in new_links[rs[j]]}
            summary["seed_similarity"][f"{rs[i]} vs {rs[j]}"] = round(len(a & b) / len(a | b), 3) if a | b else None
# Enforcer encounter composition (owner 2026-10-27): ordinary enemies (crab, bee, skull, blob) per 100 m within +-40 m of each Enforcer encounter,
# against the run's overall rate. Lower near the robot = the encounter has room (and replaces them locally); higher = it is added to a crowded stretch.
els = read(SRC, "elements.csv")
for r in RUNS:
    rows_ = [e for e in els if e["run"] == r]
    ys_enf = [num(e["y"]) for e in rows_ if e["type"] == "ENFORCER"]
    ys_ord = [num(e["y"]) for e in rows_ if e["type"] in ("CRAB", "BEE", "SKULL", "BLOB")]
    top = max([num(e["y"]) or 0 for e in rows_] or [0])
    if not ys_enf or top <= 0:
        continue
    near_m = len(ys_enf) * 80.0
    near = sum(1 for y in ys_ord if any(abs(y - q) <= 40 for q in ys_enf))
    summary["runs"][r]["enforcer_composition"] = {"ordinary_enemies_per_100m_near_enforcer": round(100 * near / near_m, 2),
                                                  "ordinary_enemies_per_100m_overall": round(100 * len(ys_ord) / top, 2), "encounters": len(ys_enf)}
json.dump(summary, open(os.path.join(OUT, "variety_summary.json"), "w"), indent=1, default=str)

# ------------------------------------------------------------------ graphs
xs = list(range(1, 11))


def mean_over(runs, fn):
    return [np.nanmean([fn(r, c) if fn(r, c) is not None else np.nan for r in runs] or [np.nan]) if runs else np.nan for c in xs]


with PdfPages(os.path.join(OUT, "variety_report.pdf")) as pdf:
    fig = plt.figure(figsize=(11.69, 8.27)); fig.text(0.05, 0.95, "Gameplay variety, repetition and encounters", fontsize=14, va="top")
    lines = ["Measured facts only; how it feels to play is not measured here.", "", summary["definition"][:1800], "",
             f"Runs: {', '.join(RUNS)}", f"Baseline: {'none' if not BASE else ', '.join(sorted(base_links))}", "",
             "Rising difficulty with falling variety (castle to castle, variety drop > 0.05):"] + (summary["flags"] or ["  none"])
    fig.text(0.05, 0.9, "\n".join(lines), fontsize=6.5, va="top", family="monospace", wrap=True); pdf.savefig(fig); plt.close(fig)
    # variety and difficulty per castle, by mode
    fig, axs = plt.subplots(len(MODES), 1, figsize=(11.69, 8.27), sharex=True)
    for ax, m in zip(axs, MODES):
        rs = [r for r in RUNS if mode_of(r) == m]
        for r in rs:
            ax.plot(xs, [new_var[r].get(c, {}).get("score") or np.nan for c in xs], color=MODE_COL[m], alpha=0.35, lw=0.8)
        ax.plot(xs, mean_over(rs, lambda r, c: new_var[r].get(c, {}).get("score")), color=MODE_COL[m], marker="o", label=f"{m} variety (mean of seeds)")
        brs = [r for r in base_var if mode_of(r) == m]
        if brs:
            ax.plot(xs, mean_over(brs, lambda r, c: base_var[r].get(c, {}).get("score")), color="#999999", ls="--", marker=".", label="baseline variety")
        ax.set_ylabel("variety 0-1", fontsize=7); ax.set_ylim(0, 1)
        ax2 = ax.twinx(); ax2.bar(xs, mean_over(rs, lambda r, c: new_diff.get((r, c))), alpha=0.15, color=MODE_COL[m]); ax2.set_ylabel("difficulty (bars)", fontsize=7)
        ax.legend(fontsize=6, loc="lower left"); ax.set_title(m, fontsize=9)
    axs[-1].set_xticks(xs); axs[-1].set_xlabel("castle"); fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    # unique mechanics and repetition
    fig, axs = plt.subplots(2, 2, figsize=(11.69, 8.27))
    for m in MODES:
        rs = [r for r in RUNS if mode_of(r) == m]
        if not rs:
            continue
        axs[0, 0].plot(xs, mean_over(rs, lambda r, c: new_var[r].get(c, {}).get("unique")), marker="o", color=MODE_COL[m], label=m)
        axs[0, 1].plot(xs, mean_over(rs, lambda r, c: new_var[r].get(c, {}).get("longest_signature_run")), marker="o", color=MODE_COL[m], label=m)
        axs[1, 0].plot(xs, mean_over(rs, lambda r, c: new_var[r].get(c, {}).get("rope_share")), marker="o", color=MODE_COL[m], label=m)
        axs[1, 1].plot(xs, mean_over(rs, lambda r, c: new_var[r].get(c, {}).get("longest_unchallenged_m")), marker="o", color=MODE_COL[m], label=m)
        brs = [r for r in base_var if mode_of(r) == m]
        if brs:
            axs[1, 0].plot(xs, mean_over(brs, lambda r, c: base_var[r].get(c, {}).get("rope_share")), ls="--", color=MODE_COL[m], alpha=0.5, label=m + " baseline")
    for ax, t in zip(axs.flat, ["unique mechanics per castle", "longest run of one arrangement (links)", "rope share of route links", "longest unchallenged stretch (m)"]):
        ax.set_title(t, fontsize=8); ax.legend(fontsize=6); ax.set_xticks(xs)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    # family distributions, new vs baseline
    for lane in ["terrain", "hazards", "enemies"]:
        fig, axs = plt.subplots(1, len(MODES), figsize=(11.69, 5))
        for ax, m in zip(axs, MODES):
            tot, btot = Counter(), Counter()
            for r in RUNS:
                if mode_of(r) == m:
                    for c in new_var[r].values(): tot.update(c[lane])
            for r in base_var:
                if mode_of(r) == m:
                    for c in base_var[r].values(): btot.update(c[lane])
            keys = sorted(set(tot) | set(btot))
            n, bn = sum(tot.values()) or 1, sum(btot.values()) or 1
            yy = np.arange(len(keys))
            ax.barh(yy - 0.2, [tot[k] / n for k in keys], 0.4, color=MODE_COL[m], label="this campaign")
            if btot:
                ax.barh(yy + 0.2, [btot[k] / bn for k in keys], 0.4, color="#aaaaaa", label="baseline")
            ax.set_yticks(yy); ax.set_yticklabels(keys, fontsize=6); ax.set_title(f"{m}: {lane} share", fontsize=8); ax.legend(fontsize=6)
        fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    # encounters
    if enc:
        fig, axs = plt.subplots(2, 2, figsize=(11.69, 8.27))
        for m in MODES:
            rs = [r for r in RUNS if mode_of(r) == m]
            if not rs:
                continue
            per = lambda col: [np.mean([sum(int(x[col] or 0) for x in enc if x["run"] == r and int(x["castle"]) == c) for r in rs]) for c in xs]
            axs[0, 0].plot(xs, [a + b + c for a, b, c in zip(per("enforcers_yellow"), per("enforcers_orange"), per("enforcers_red"))], marker="o", color=MODE_COL[m], label=m)
            axs[0, 1].plot(xs, per("avalanches"), marker="o", color=MODE_COL[m], label=m)
            axs[1, 1].plot(xs, per("avl_blocks_released"), marker="o", color=MODE_COL[m], label=m + " released")
            axs[1, 1].plot(xs, per("avl_blocks_near_player"), marker="x", ls=":", color=MODE_COL[m], label=m + " near player")
        pr = [r for r in RUNS if mode_of(r) == "PUNISHER"]
        if pr:
            bottom = np.zeros(10)
            for col, colr in [("enforcers_yellow", "#f1c40f"), ("enforcers_orange", "#e67e22"), ("enforcers_red", "#c0392b")]:
                v = np.array([np.mean([sum(int(x[col] or 0) for x in enc if x["run"] == r and int(x["castle"]) == c) for r in pr]) for c in xs])
                axs[1, 0].bar(xs, v, bottom=bottom, color=colr, label=col.split("_")[1]); bottom += v
        for ax, t in zip(axs.flat, ["Enforcer encounters per castle (mean of seeds)", "avalanches per castle (mean of seeds)", "PUNISHER Enforcers by tier", "avalanche blocks per castle"]):
            ax.set_title(t, fontsize=8); ax.legend(fontsize=6); ax.set_xticks(xs)
        fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

# ------------------------------------------------------------------ index
md = ["", "## Gameplay variety and encounters (variety_report.py)", "",
      "| file | contents |", "|---|---|",
      "| variety_report.pdf | variety vs difficulty per castle and mode (with baseline), unique mechanics, repetition, ropes, family shares new vs baseline, Enforcer and avalanche encounters |",
      "| variety_compare.csv | per run and castle: variety score, unique mechanics, entropy, repetition, rope share, challenge share, difficulty, and the baseline's where it exists |",
      "| variety_castles_all.csv / variety_rolling_all.csv / variety_windows_all.csv | the per-course detail (LaneReport/VarietyReport: variants, Enforcer tiers) |",
      "| encounters_all.csv | per slice: Enforcers by tier, avalanches, generator refusals, solver-run outcomes (kicks, swats, blocks released / near / in the traversal area, hits) |",
      "| ropes_all.csv | rope links, spacing, longest consecutive run, rope module picks vs fallbacks, and the baseline's |",
      "| variety_summary.json | everything above per run, seed-to-seed similarity, and castles where difficulty rose while variety fell |", "",
      "Rising difficulty with falling variety: " + ("; ".join(summary["flags"]) if summary["flags"] else "none found")]
with open(os.path.join(OUT, "VARIETY.md"), "w") as fh:
    fh.write("\n".join(md) + "\n")
p = os.path.join(OUT, "INDEX.md")
if os.path.exists(p):
    with open(p, "a") as fh:
        fh.write("\n".join(md) + "\n")
print(f"variety: {len(RUNS)} runs, baseline {len(base_links)}; flags {len(summary['flags'])}")
