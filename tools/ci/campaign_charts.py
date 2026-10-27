#!/usr/bin/env python3
"""Campaign charts from LaneReport course folders (owner directive 2026-10-27: Campaign A = NORMAL + PUNISHER, Campaign B = NOT GONNA HAPPEN, kept apart).

Usage: campaign_charts.py PREFIX TITLE OUT_DIR COURSE_DIR [COURSE_DIR ...]
  PREFIX  "A" or "B" (charts are named PREFIX1 ... PREFIX9)
  each COURSE_DIR is one LaneReport output folder (castle_control.csv, fallbacks.csv, encounters.csv, variety_castles.csv, traversal.csv, solver.csv)

Every chart is drawn from the CSV rows only; the rows behind each chart are written beside it (PREFIXn_<name>.csv) so the numbers can be checked.
Charts: 1 castle totals vs target, 2 category mix, 3 deviation from target, 4 controller factors, 5 continuation fallbacks (at castle demand vs
the 0.15 safety demand) and challenge fallbacks, 6 variety, 7 traversal (challenge density, ropes), 8 encounters (Enforcers and avalanches planned
vs placed; for NGH block counts, ricochets, peak active blocks), 9 solver reliability (simulated climb time, setbacks per castle).
"""
import csv, os, sys, collections
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
plt.rcParams.update({"figure.facecolor": SURF, "axes.facecolor": SURF, "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2,
                     "ytick.color": INK2, "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.spines.top": False,
                     "axes.spines.right": False, "font.size": 10, "lines.linewidth": 2})


def rows(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p): return []
    with open(p, newline="") as f: return list(csv.DictReader(f))


def num(v, default=0.0):
    try: return float(v)
    except (TypeError, ValueError): return default


def run_name(d):
    r = rows(d, "castle_control.csv")
    return r[0]["run"] if r else os.path.basename(d.rstrip("/"))


def mode_of(name): return name.split("-s")[0]


def save(fig, out, tag, name, table, header):
    fig.tight_layout(); fig.savefig(os.path.join(out, f"{tag}_{name}.png"), dpi=130); plt.close(fig)
    with open(os.path.join(out, f"{tag}_{name}.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(header); w.writerows(table)


def per_castle(d, name, keys, castle_key="castle", agg=sum):
    out = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in rows(d, name):
        c = int(num(r.get(castle_key), 0))
        if c < 1: continue
        for k in keys: out[c][k].append(num(r.get(k)))
    return {c: {k: agg(v) for k, v in kv.items()} for c, kv in out.items()}


def main():
    tag, title, out, dirs = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    os.makedirs(out, exist_ok=True)
    runs = [(run_name(d), d) for d in dirs]
    modes = sorted({mode_of(n) for n, _ in runs}, key=lambda m: ["NORMAL", "PUNISHER", "NGH"].index(m) if m in ("NORMAL", "PUNISHER", "NGH") else 9)
    col = {n: SERIES[i % len(SERIES)] for i, (n, _) in enumerate(runs)}
    ls = {m: s for m, s in zip(modes, ["-", "--", ":"])}

    # 1 totals vs target
    fig, ax = plt.subplots(figsize=(9, 5)); tab = []
    for n, d in runs:
        cc = [r for r in rows(d, "castle_control.csv") if r["status"].startswith("comp")]
        xs = [int(r["castle"]) for r in cc]; ys = [num(r["lane_total"]) for r in cc]
        ax.plot(xs, ys, ls=ls[mode_of(n)], color=col[n], marker="o", ms=4, label=n)
        for r in cc: tab.append([n, r["castle"], r["target_total"], r["lane_total"], r["deviation_from_target"]])
    for m in modes:
        d0 = next(d for n, d in runs if mode_of(n) == m)
        cc = [r for r in rows(d0, "castle_control.csv") if r["status"].startswith("comp")]
        ax.plot([int(r["castle"]) for r in cc], [num(r["target_total"]) for r in cc], color=INK2, lw=1.2, ls=ls[m], alpha=0.9, label=f"{m} target")
    ax.set(title=f"{title} - {tag}1 castle difficulty (lane units) vs intended curve", xlabel="castle", ylabel="lane total"); ax.legend(fontsize=8, ncol=2)
    save(fig, out, f"{tag}1", "totals_vs_target", tab, ["run", "castle", "target_total", "lane_total", "deviation"])

    # 2 category mix (mean per mode)
    cats = [("lane_terrain", "terrain"), ("lane_hazards", "hazards"), ("lane_enemies", "enemies"), ("lane_interaction", "interaction")]
    fig, axs = plt.subplots(1, len(modes), figsize=(5 * len(modes), 4.5), squeeze=False); tab = []
    for i, m in enumerate(modes):
        acc = collections.defaultdict(lambda: collections.defaultdict(list))
        for n, d in runs:
            if mode_of(n) != m: continue
            for r in rows(d, "castle_control.csv"):
                if r["status"].startswith("comp"):
                    for k, _ in cats: acc[int(r["castle"])][k].append(num(r[k]))
        xs = sorted(acc); bottom = [0.0] * len(xs); ax = axs[0][i]
        for j, (k, lab) in enumerate(cats):
            v = [sum(acc[c][k]) / len(acc[c][k]) for c in xs]
            ax.bar(xs, v, bottom=bottom, color=SERIES[j], label=lab, edgecolor=SURF, linewidth=1)
            for c, vv in zip(xs, v): tab.append([m, c, lab, f"{vv:.1f}"])
            bottom = [a + b for a, b in zip(bottom, v)]
        ax.set(title=f"{m}: category mix (seed mean)", xlabel="castle", ylabel="lane units"); ax.legend(fontsize=8)
    save(fig, out, f"{tag}2", "category_mix", tab, ["mode", "castle", "category", "mean"])

    # 3 deviation
    fig, ax = plt.subplots(figsize=(9, 4.5)); tab = []
    for n, d in runs:
        cc = [r for r in rows(d, "castle_control.csv") if r["status"].startswith("comp")]
        ax.plot([int(r["castle"]) for r in cc], [100 * num(r["deviation_from_target"]) for r in cc], ls=ls[mode_of(n)], color=col[n], marker="o", ms=4, label=n)
        for r in cc: tab.append([n, r["castle"], f"{100 * num(r['deviation_from_target']):.1f}"])
    ax.axhline(0, color=INK2, lw=1); ax.axhspan(-10, 10, color=GRID, alpha=0.5, zorder=0)
    ax.set(title=f"{title} - {tag}3 deviation from the intended castle total (%, band = +/-10%)", xlabel="castle", ylabel="%"); ax.legend(fontsize=8, ncol=2)
    save(fig, out, f"{tag}3", "deviation", tab, ["run", "castle", "deviation_pct"])

    # 4 factors
    fk = [("factor_terrain", "terrain"), ("factor_hazards", "hazards"), ("factor_enemies", "enemies"), ("factor_interaction", "interaction")]
    fig, axs = plt.subplots(1, len(modes), figsize=(5 * len(modes), 4.5), squeeze=False); tab = []
    for i, m in enumerate(modes):
        ax = axs[0][i]
        for j, (k, lab) in enumerate(fk):
            acc = collections.defaultdict(list)
            for n, d in runs:
                if mode_of(n) == m:
                    for r in rows(d, "castle_control.csv"): acc[int(r["castle"])].append(num(r[k], 1.0))
            xs = sorted(acc); v = [sum(acc[c]) / len(acc[c]) for c in xs]
            ax.plot(xs, v, color=SERIES[j], marker="o", ms=4, label=lab)
            for c, vv in zip(xs, v): tab.append([m, c, lab, f"{vv:.3f}"])
        ax.axhline(1, color=INK2, lw=1); ax.set(title=f"{m}: controller factors (seed mean)", xlabel="castle", ylabel="factor"); ax.legend(fontsize=8)
    save(fig, out, f"{tag}4", "factors", tab, ["mode", "castle", "factor", "mean"])

    # 5 fallbacks
    keys = ["fallback_at_demand_ok", "fallback_at_demand_failed", "challenge_fallback_ok", "challenge_fallback_failed", "threat_fallback_ok", "threat_fallback_failed", "progression_hot_window"]
    fig, axs = plt.subplots(1, len(modes), figsize=(5 * len(modes), 4.5), squeeze=False); tab = []
    for i, m in enumerate(modes):
        acc = collections.defaultdict(lambda: collections.Counter()); ns = 0
        for n, d in runs:
            if mode_of(n) != m: continue
            ns += 1
            for c, kv in per_castle(d, "fallbacks.csv", keys).items(): acc[c].update(kv)
        xs = sorted(acc); ax = axs[0][i]; w = 0.27
        for j, (k, lab) in enumerate([("fallback_at_demand_ok", "continuation at castle demand"), ("fallback_at_demand_failed", "continuation at 0.15 (safety)"), ("challenge_fallback_ok", "challenge fallback accepted")]):
            ax.bar([x + (j - 1) * w for x in xs], [acc[c][k] / max(1, ns) for c in xs], width=w, color=SERIES[j], label=lab)
        for c in xs: tab.append([m, c] + [f"{acc[c][k] / max(1, ns):.1f}" for k in keys])
        ax.set(title=f"{m}: fallbacks per castle (seed mean)", xlabel="castle", ylabel="count"); ax.legend(fontsize=8)
    save(fig, out, f"{tag}5", "fallbacks", tab, ["mode", "castle"] + keys)

    # 6 variety
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.5)); tab = []
    for n, d in runs:
        vc = rows(d, "variety_castles.csv")
        xs = [int(r["castle"]) for r in vc]
        axs[0].plot(xs, [num(r["signature_entropy"]) for r in vc], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        axs[1].plot(xs, [num(r["longest_signature_run"]) for r in vc], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        for r in vc: tab.append([n, r["castle"], r["signature_entropy"], r["unique_mechanics"], r["longest_signature_run"], r["repeated_trigrams"]])
    axs[0].set(title=f"{tag}6 variety: signature entropy (higher = more varied)", xlabel="castle"); axs[1].set(title="longest run of one link signature", xlabel="castle")
    axs[0].legend(fontsize=7, ncol=2)
    save(fig, out, f"{tag}6", "variety", tab, ["run", "castle", "signature_entropy", "unique_mechanics", "longest_signature_run", "repeated_trigrams"])

    # 7 traversal
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.5)); tab = []
    for n, d in runs:
        tv = rows(d, "traversal.csv"); xs = [int(r["castle"]) for r in tv]
        axs[0].plot(xs, [num(r["challenge_density"]) for r in tv], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        axs[1].plot(xs, [num(r["rope_modules"]) for r in tv], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        for r in tv: tab.append([n, r["castle"], r["challenge_density"], r["rope_modules"], r["fallbacks_accepted"], r["longest_unchallenged_climb_m"]])
    axs[0].set(title=f"{tag}7 challenge density (challenge links / route links)", xlabel="castle"); axs[1].set(title="rope modules per castle", xlabel="castle")
    axs[0].legend(fontsize=7, ncol=2)
    save(fig, out, f"{tag}7", "traversal", tab, ["run", "castle", "challenge_density", "rope_modules", "fallbacks_accepted", "longest_unchallenged_climb_m"])

    # 8 encounters
    ek = ["enforcers_yellow", "enforcers_orange", "enforcers_red", "enforcers_planned", "avalanches", "avalanches_planned", "avalanche_blocks", "avl_ricochets", "avl_peak_active",
          "avl_blocks_5", "avl_blocks_6", "avl_blocks_7", "avl_hits", "enf_kick_hits", "enf_swat_hits"]
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.5)); tab = []
    for i, m in enumerate(modes):
        acc = collections.defaultdict(collections.Counter); ns = 0
        for n, d in runs:
            if mode_of(n) != m: continue
            ns += 1
            for c, kv in per_castle(d, "encounters.csv", ek).items(): acc[c].update(kv)
        xs = sorted(acc)
        placed = [(acc[c]["enforcers_yellow"] + acc[c]["enforcers_orange"] + acc[c]["enforcers_red"]) / max(1, ns) for c in xs]
        axs[0].plot(xs, placed, color=SERIES[2 * i], ls=ls[m], marker="o", ms=4, label=f"{m} Enforcers placed")
        axs[0].plot(xs, [acc[c]["enforcers_planned"] / max(1, ns) for c in xs], color=SERIES[2 * i], ls=ls[m], lw=1, alpha=0.6, label=f"{m} planned")
        axs[1].plot(xs, [acc[c]["avalanches"] / max(1, ns) for c in xs], color=SERIES[2 * i + 1], ls=ls[m], marker="o", ms=4, label=f"{m} avalanches placed")
        axs[1].plot(xs, [acc[c]["avalanches_planned"] / max(1, ns) for c in xs], color=SERIES[2 * i + 1], ls=ls[m], lw=1, alpha=0.6, label=f"{m} planned")
        for c in xs: tab.append([m, c] + [f"{acc[c][k] / max(1, ns):.2f}" for k in ek])
    axs[0].set(title=f"{tag}8 Enforcer encounters per castle (seed mean)", xlabel="castle"); axs[1].set(title="avalanches per castle (seed mean)", xlabel="castle")
    axs[0].legend(fontsize=7); axs[1].legend(fontsize=7)
    save(fig, out, f"{tag}8", "encounters", tab, ["mode", "castle"] + ek)

    # 9 solver reliability
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.5)); tab = []
    for n, d in runs:
        sv = rows(d, "solver.csv"); en = rows(d, "encounters.csv")
        cast = {r["slice"]: int(num(r["castle"])) for r in en}
        t = collections.Counter(); sb = collections.Counter(); ok = collections.Counter(); tot = collections.Counter()
        for r in sv:
            c = cast.get(r["slice"], 0); t[c] += num(r["sim_time_s"]) / 60.0; tot[c] += 1; ok[c] += r["completed"] == "true"
        for r in en: sb[int(num(r["castle"]))] += num(r["setbacks"])
        xs = sorted(c for c in t if c > 0)
        axs[0].plot(xs, [t[c] for c in xs], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        axs[1].plot(xs, [sb[c] for c in xs], ls=ls[mode_of(n)], color=col[n], marker="o", ms=3, label=n)
        for c in xs: tab.append([n, c, f"{t[c]:.2f}", int(sb[c]), int(ok[c]), int(tot[c])])
    axs[0].set(title=f"{tag}9 solver: simulated climb minutes per castle", xlabel="castle"); axs[1].set(title="setbacks (hits, falls) in the solver's climb", xlabel="castle")
    axs[0].legend(fontsize=7, ncol=2)
    save(fig, out, f"{tag}9", "solver", tab, ["run", "castle", "sim_minutes", "setbacks", "slices_solved", "slices"])


if __name__ == "__main__":
    main()
