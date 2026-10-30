#!/usr/bin/env python3
"""Geometry audit consolidation (owner directive 2026-10-30: master vertical geometry, active corridor and buildable dead-zone audit).

Usage:
  geometry_audit.py OUT_DIR LABEL=PARENT_DIR [LABEL=PARENT_DIR ...]
    each PARENT_DIR holds LaneReport course folders (lanes-mode-<m>-seed-<s>/) written with -Dclimb.geomAudit=true
    (geometry_samples.csv, geometry_summary.json) plus the usual report files (counts.csv, modules.csv, elements.csv, hazards.csv,
    castle_control.csv, chunks.csv, gate.json). The first LABEL is the baseline the others are compared with.

Writes OUT_DIR/GEOMETRY_AUDIT.md (printed to the log), candidates.csv, per_course.csv, mechanics.csv, refusals.csv, castle_dead_zone.csv,
summary.json and charts/*.png. Measurements only: nothing here judges a candidate acceptable; that is the owner's decision.
"""
import csv, glob, json, math, os, sys, collections
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

MODES = ["NORMAL", "PUNISHER", "NGH"]
COL = {"NORMAL": "#2a78d6", "PUNISHER": "#eb6834", "NGH": "#6250d6"}
CAND_COL = ["#3a3a3a", "#2a78d6", "#eb6834", "#1f9e6e", "#b0408f", "#c9a227"]
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
plt.rcParams.update({"figure.facecolor": SURF, "axes.facecolor": SURF, "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2,
                     "ytick.color": INK2, "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.spines.top": False,
                     "axes.spines.right": False, "font.size": 10, "lines.linewidth": 1.8})
MECH = ["PLAIN", "PAD", "SPRING", "MOVE_V", "SEESAW", "RAMP", "SWING", "MOVE_H", "MORTAR"]
METRICS = ["pitch", "deck_to_upper_deck", "phys_clear", "reach_gap", "hazard_gap", "dead_zone"]


def rows(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p): return []
    with open(p, newline="") as f: return list(csv.DictReader(f))


def num(v, default=float("nan")):
    try: return float(v)
    except (TypeError, ValueError): return default


def pct(v, q):
    v = sorted(x for x in v if not math.isnan(x))
    if not v: return float("nan")
    return v[min(len(v) - 1, max(0, int(round(q * (len(v) - 1)))))]


def stats(v):
    v = [x for x in v if not math.isnan(x)]
    if not v: return {k: float("nan") for k in ["n", "min", "p5", "p25", "median", "p75", "p95", "max", "mean", "sd"]}
    m = sum(v) / len(v)
    return {"n": len(v), "min": min(v), "p5": pct(v, .05), "p25": pct(v, .25), "median": pct(v, .5), "p75": pct(v, .75), "p95": pct(v, .95),
            "max": max(v), "mean": m, "sd": math.sqrt(max(0.0, sum((x - m) ** 2 for x in v) / len(v)))}


def course(d):
    gate = {}
    if os.path.exists(os.path.join(d, "gate.json")):
        with open(os.path.join(d, "gate.json")) as f: gate = json.load(f)
    summ = {}
    if os.path.exists(os.path.join(d, "geometry_summary.json")):
        with open(os.path.join(d, "geometry_summary.json")) as f: summ = json.load(f)
    run = gate.get("run") or summ.get("run") or os.path.basename(d.rstrip("/"))
    mode = run.split("-s")[0]
    smp = rows(d, "geometry_samples.csv")
    S = {k: [num(r.get(k)) for r in smp] for k in ["s", "castle", "route_y", "pitch", "deck_top", "occupancy_top", "reach_plain_top", "reach_launch_top",
                                                  "hazard_top", "upper_deck", "upper_low", "phys_clear", "reach_gap", "hazard_gap", "dead_zone"]}
    S["deck_to_upper_deck"] = [u - dk for u, dk in zip(S["upper_deck"], S["deck_top"])]
    mech = {m: [0, 0, 0] for m in MECH}
    for r in smp:
        for m in MECH:
            v = r.get("fit_" + m, "")
            if v != "": mech[m][int(v)] += 1
    tal = collections.Counter()
    for r in rows(d, "counts.csv"): tal[r.get("tally", "")] += int(num(r.get("count"), 0))
    mods = collections.defaultdict(lambda: [0, 0, 0])          # kind -> tried, accepted (final attempt), refused with a layer counter
    for r in rows(d, "modules.csv"):
        if r.get("role") == "STRUCTURE": continue
        k = r.get("kind", ""); mods[k][0] += 1
        if r.get("accepted") == "true" and r.get("final_attempt") == "true": mods[k][1] += 1
        if r.get("accepted") != "true" and num(r.get("refused_layer"), 0) > 0: mods[k][2] += 1
    els = rows(d, "elements.csv")
    route = [e for e in els if e.get("route") in ("true", "True", "1")]
    sarc = [num(e.get("s")) for e in route if not math.isnan(num(e.get("s")))]
    hz = rows(d, "hazards.csv")
    enemies = sum(1 for h in hz if h.get("type") in ("CRAB", "BEE", "SKULL", "BLOB", "ENFORCER"))
    hazards = sum(1 for h in hz if h.get("type") not in ("CRAB", "BEE", "SKULL", "BLOB", "ENFORCER", "KEY", "GATE", "CLUB"))
    cc = [r for r in rows(d, "castle_control.csv") if r.get("status", "").startswith("comp")]
    tot = [num(r.get("est_total")) for r in cc]
    growth = (tot[-1] - tot[0]) / tot[0] if len(tot) > 1 and tot[0] else float("nan")
    dips = sum(1 for a, b in zip(tot, tot[1:]) if b < a)
    gen = sum(num(r.get("gen_wall_s"), 0) for r in rows(d, "chunks.csv"))
    C = summ.get("circumference", float("nan"))
    arc = (max(sarc) - min(sarc)) if sarc else float("nan")
    return {"dir": d, "run": run, "mode": mode, "S": S, "mech": mech, "tal": tal, "mods": dict(mods), "route_elements": len(route), "arc": arc,
            "revolutions": arc / C if C and not math.isnan(arc) else float("nan"), "C": C, "radius": summ.get("radius"), "hazards": hazards,
            "enemies": enemies, "growth": growth, "dips": dips, "castles": len(cc), "gen_s": gen, "passes": gate.get("passes"),
            "generation": gate.get("generation", "MISSING"), "solver": gate.get("solver", "MISSING"), "integrity": gate.get("geometric_integrity", "MISSING"),
            "links": len(rows(d, "links.csv"))}


def load(parent):
    ds = sorted(os.path.dirname(p) for p in glob.glob(os.path.join(parent, "**", "geometry_samples.csv"), recursive=True))
    return [course(d) for d in ds]


def fmt(v, p=2):
    return "-" if v is None or (isinstance(v, float) and math.isnan(v)) else (f"{v:.{p}f}" if isinstance(v, float) else str(v))


def main():
    out = sys.argv[1]; cands = [a.split("=", 1) for a in sys.argv[2:]]
    os.makedirs(os.path.join(out, "charts"), exist_ok=True)
    data = {lab: load(par) for lab, par in cands}
    labels = [lab for lab, _ in cands]
    md = ["# Geometry audit (vertical geometry, active corridor, buildable dead zone)", "",
          "Measured with the generator's own geometry (GeometryAudit, -Dclimb.geomAudit=true). Samples are 1 m apart along the route's arc; the",
          "revolution above a sample is the arc one circumference further on. All heights in metres.", ""]
    # ---- per candidate x mode summary
    cand_rows = []; summary = {}
    for lab in labels:
        cs = data[lab]
        for m in MODES:
            mc = [c for c in cs if c["mode"] == m]
            if not mc: continue
            agg = {k: stats([x for c in mc for x in c["S"][k]]) for k in METRICS}
            mech = {k: [sum(c["mech"][k][i] for c in mc) for i in range(3)] for k in MECH}
            tal = collections.Counter(); [tal.update(c["tal"]) for c in mc]
            mods = collections.defaultdict(lambda: [0, 0, 0])
            for c in mc:
                for k, v in c["mods"].items():
                    for i in range(3): mods[k][i] += v[i]
            n = len(mc)
            r = {"candidate": lab, "mode": m, "courses": n, "radius": mc[0]["radius"], "circumference": mc[0]["C"],
                 "passes": sum(1 for c in mc if c["passes"]), "castles_mean": sum(c["castles"] for c in mc) / n,
                 "revolutions": sum(c["revolutions"] for c in mc) / n, "route_arc_m": sum(c["arc"] for c in mc) / n,
                 "route_elements": sum(c["route_elements"] for c in mc) / n, "links": sum(c["links"] for c in mc) / n,
                 "hazards": sum(c["hazards"] for c in mc) / n, "enemies": sum(c["enemies"] for c in mc) / n,
                 "growth_1_10": sum(c["growth"] for c in mc if not math.isnan(c["growth"])) / max(1, sum(1 for c in mc if not math.isnan(c["growth"]))),
                 "dips": sum(c["dips"] for c in mc) / n, "gen_s": sum(c["gen_s"] for c in mc) / n,
                 "layer_refusals": tal["geo_layer_refusal"] / n, "blanket_only_refusals": tal["geo_layer_refusal_blanket_only"] / n,
                 "modules_tried": sum(v[0] for v in mods.values()) / n, "modules_accepted": sum(v[1] for v in mods.values()) / n}
            for k in METRICS:
                for q in ("p5", "median", "p95"): r[f"{k}_{q}"] = agg[k][q]
            for k in MECH:
                t = max(1, sum(mech[k])); r[f"fit_{k}_now"] = mech[k][0] / t; r[f"fit_{k}_measured_only"] = mech[k][1] / t; r[f"fit_{k}_neither"] = mech[k][2] / t
            for k in ("HAZ", "TRAP", "MORTAR", "DROPS", "CREATURE", "PAD", "SPRING", "MOVER_V", "ELEV_FALL", "SEESAW", "RAMP", "MOVER_H", "SWING", "ROPE"):
                v = mods.get(k, [0, 0, 0]); r[f"accept_{k}"] = v[1] / v[0] if v[0] else float("nan"); r[f"accepted_{k}"] = v[1] / n
            cand_rows.append(r)
            summary.setdefault(lab, {})[m] = {"metrics": agg, "mechanics": mech, "refusals": {k: v for k, v in tal.items() if k.startswith("geo_")}}
    keys = list(cand_rows[0].keys()) if cand_rows else []
    with open(os.path.join(out, "candidates.csv"), "w", newline="") as f: w = csv.DictWriter(f, fieldnames=keys); w.writeheader(); w.writerows(cand_rows)
    with open(os.path.join(out, "summary.json"), "w") as f: json.dump(summary, f, indent=1, default=lambda o: None)
    # per course
    with open(os.path.join(out, "per_course.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(["candidate", "run", "passes", "generation", "integrity", "castles", "revolutions", "route_elements", "hazards", "enemies",
                                       "growth_1_10", "dips", "gen_s", "pitch_median", "dead_zone_median", "layer_refusals", "blanket_only"])
        for lab in labels:
            for c in data[lab]:
                w.writerow([lab, c["run"], c["passes"], c["generation"], c["integrity"], c["castles"], fmt(c["revolutions"]), c["route_elements"], c["hazards"],
                            c["enemies"], fmt(c["growth"], 3), c["dips"], fmt(c["gen_s"], 0), fmt(stats(c["S"]["pitch"])["median"]),
                            fmt(stats(c["S"]["dead_zone"])["median"]), c["tal"]["geo_layer_refusal"], c["tal"]["geo_layer_refusal_blanket_only"]])
    # refusals by lower launcher type (blanket only)
    with open(os.path.join(out, "refusals.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(["candidate", "mode", "tally", "per_course"])
        for lab in labels:
            for m in MODES:
                mc = [c for c in data[lab] if c["mode"] == m]
                if not mc: continue
                tal = collections.Counter(); [tal.update(c["tal"]) for c in mc]
                for k, v in sorted(tal.items()):
                    if k.startswith("geo_") or k.startswith("t4sp_lower_") or k.startswith("c1_reach_clash_"): w.writerow([lab, m, k, round(v / len(mc), 1)])
    # castle dead zone (baseline)
    base = labels[0]
    with open(os.path.join(out, "castle_dead_zone.csv"), "w", newline="") as f:
        w = csv.writer(f); w.writerow(["candidate", "mode", "castle", "samples", "pitch_median", "dead_zone_median", "dead_zone_p25", "dead_zone_p75", "phys_clear_median", "upper_covered_share"])
        for lab in labels:
            for m in MODES:
                mc = [c for c in data[lab] if c["mode"] == m]
                for k in range(1, 11):
                    pv, dz, pc, cov, n = [], [], [], 0, 0
                    for c in mc:
                        for i, ck in enumerate(c["S"]["castle"]):
                            if int(ck) != k if not math.isnan(ck) else True: continue
                            n += 1; pv.append(c["S"]["pitch"][i]); dz.append(c["S"]["dead_zone"][i]); pc.append(c["S"]["phys_clear"][i])
                            if not math.isnan(c["S"]["upper_low"][i]): cov += 1
                    if n: w.writerow([lab, m, k, n, fmt(stats(pv)["median"]), fmt(stats(dz)["median"]), fmt(stats(dz)["p25"]), fmt(stats(dz)["p75"]), fmt(stats(pc)["median"]), fmt(cov / n, 3)])
    # ---- markdown
    md += ["## Measured geometry per candidate and mode (median, with p5-p95)", "",
           "| candidate | mode | courses | radius | pitch | deck to upper deck | body clearance | reach gap | dead zone | revolutions | route elements |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in cand_rows:
        md.append(f"| {r['candidate']} | {r['mode']} | {r['courses']} | {fmt(r['radius'])} | " + " | ".join(
            f"{fmt(r[k + '_median'])} ({fmt(r[k + '_p5'])}-{fmt(r[k + '_p95'])})" for k in ["pitch", "deck_to_upper_deck", "phys_clear", "reach_gap", "dead_zone"]) +
            f" | {fmt(r['revolutions'], 1)} | {fmt(r['route_elements'], 0)} |")
    md += ["", "Pitch = route height one revolution further on minus here. Body clearance = lowest occupied point of the revolution above minus the",
           "active layer's occupancy top (deck + 2.8 m standing room, movers' travel). Reach gap = lowest deck above minus the highest point the player",
           "can reach from the decks within 12 m (measured C1 reach, ordinary and launcher). Dead zone = lowest occupied/hazard point above minus the",
           "highest of occupancy, reach and hazard influence of the active layer (defined only where the revolution above is occupied).", "",
           "## Mechanic availability at the route deck (share of route-deck samples): fits now / fits only without the blanket 9.5 m rule / neither", "",
           "| candidate | mode | " + " | ".join(MECH) + " |", "|---|---|" + "---|" * len(MECH)]
    for r in cand_rows:
        md.append(f"| {r['candidate']} | {r['mode']} | " + " | ".join(f"{100 * r['fit_' + k + '_now']:.0f}/{100 * r['fit_' + k + '_measured_only']:.0f}/{100 * r['fit_' + k + '_neither']:.0f}" for k in MECH) + " |")
    md += ["", "## Generator outcomes per candidate and mode (per course)", "",
           "| candidate | mode | passes | layer refusals | blanket-only | modules tried / accepted | hazards | enemies | MORTAR acc | DROPS acc | HAZ acc | TRAP acc | growth 1-10 | dips | gen s |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in cand_rows:
        md.append(f"| {r['candidate']} | {r['mode']} | {r['passes']}/{r['courses']} | {fmt(r['layer_refusals'], 0)} | {fmt(r['blanket_only_refusals'], 0)} | "
                  f"{fmt(r['modules_tried'], 0)} / {fmt(r['modules_accepted'], 0)} | {fmt(r['hazards'], 0)} | {fmt(r['enemies'], 0)} | "
                  + " | ".join(fmt(100 * r[f'accept_{k}'], 1) + "%" for k in ("MORTAR", "DROPS", "HAZ", "TRAP")) + f" | {100 * r['growth_1_10']:+.1f}% | {fmt(r['dips'], 1)} | {fmt(r['gen_s'], 0)} |")
    # ---- charts
    charts(out, data, labels, cand_rows)
    text = "\n".join(md) + "\n"
    with open(os.path.join(out, "GEOMETRY_AUDIT.md"), "w") as f: f.write(text)
    print(text)


def charts(out, data, labels, cand_rows):
    base = data[labels[0]]
    # 1. cross-section of real coordinates: one revolution in the middle of the climb per mode, the revolutions below and above
    for m in MODES:
        mc = [c for c in base if c["mode"] == m]
        if not mc: continue
        c = mc[0]; S = c["S"]; C = c["C"]; n = len(S["s"])
        if n < 10 or not C or math.isnan(C): continue
        i0 = n // 2; s0 = S["s"][i0]; idx = [i for i in range(n) if s0 <= S["s"][i] < s0 + C]
        x = [S["s"][i] - s0 for i in idx]
        fig, ax = plt.subplots(figsize=(11, 5.2))
        def line(k, **kw): ax.plot(x, [S[k][i] for i in idx], **kw)
        line("route_y", color=INK, lw=2.4, label="route (active revolution)")
        line("occupancy_top", color="#8a8a8a", lw=1.2, ls="-", label="occupancy top (deck + standing room)")
        line("reach_plain_top", color=COL["NORMAL"], lw=1.4, ls="--", label="ordinary reach top (measured)")
        line("reach_launch_top", color=COL["PUNISHER"], lw=1.4, ls="--", label="launcher reach top (measured)")
        ax.scatter(x, [S["hazard_top"][i] for i in idx], s=6, color="#c0392b", label="hazard influence top", zorder=3)
        line("upper_low", color=COL["NGH"], lw=2.0, label="revolution above: lowest occupied point")
        line("upper_deck", color=COL["NGH"], lw=1.0, ls=":", label="revolution above: deck")
        lo = [max(v for v in (S["occupancy_top"][i], S["reach_plain_top"][i], S["reach_launch_top"][i], S["hazard_top"][i]) if not math.isnan(v)) if any(not math.isnan(v) for v in (S["occupancy_top"][i], S["reach_plain_top"][i], S["reach_launch_top"][i], S["hazard_top"][i])) else float("nan") for i in idx]
        hi = [S["upper_low"][i] for i in idx]
        ax.fill_between(x, lo, hi, where=[(not math.isnan(a)) and (not math.isnan(b)) and b > a for a, b in zip(lo, hi)], color="#1f9e6e", alpha=0.18, label="dead zone (positive gap)")
        ax.set_xlabel(f"arc along one revolution (m), from route arc {s0:.0f} m"); ax.set_ylabel("height (m)")
        ax.set_title(f"{m} {c['run']}: measured cross-section of one revolution and the one above (radius {c['radius']})", loc="left")
        ax.legend(frameon=False, fontsize=7, ncol=2, loc="upper left"); fig.tight_layout(); fig.savefig(os.path.join(out, "charts", f"01_cross_section_{m}.png"), dpi=130); plt.close(fig)
    # 2-6. distributions per metric, baseline, by mode
    for j, k in enumerate(METRICS):
        fig, ax = plt.subplots(figsize=(8, 4))
        for m in MODES:
            v = [x for c in base if c["mode"] == m for x in c["S"][k] if not math.isnan(x)]
            if v: ax.hist(v, bins=60, range=(min(-2, pct(v, .01)), pct(v, .99) + 1), histtype="step", lw=1.8, color=COL[m], label=f"{m} (median {pct(v, .5):.2f} m)")
        ax.set_xlabel(f"{k.replace('_', ' ')} (m)"); ax.set_ylabel("samples (1 m of route arc)"); ax.set_title(f"{k.replace('_', ' ')}: {labels[0]}", loc="left")
        ax.legend(frameon=False, fontsize=8); fig.tight_layout(); fig.savefig(os.path.join(out, "charts", f"{2 + j:02d}_{k}.png"), dpi=130); plt.close(fig)
    # 8. dead-zone heatmap: castle x arc position within the revolution (median), baseline, per mode
    for m in MODES:
        mc = [c for c in base if c["mode"] == m]
        if not mc: continue
        C = mc[0]["C"]; nb = 24; grid = [[[] for _ in range(nb)] for _ in range(10)]
        for c in mc:
            for s, ck, dz in zip(c["S"]["s"], c["S"]["castle"], c["S"]["dead_zone"]):
                if math.isnan(ck) or math.isnan(dz): continue
                grid[min(9, int(ck) - 1)][int((s % C) / C * nb) % nb].append(dz)
        img = [[pct(cell, .5) if cell else float("nan") for cell in row] for row in grid]
        fig, ax = plt.subplots(figsize=(9, 4.2)); im = ax.imshow(img, aspect="auto", origin="lower", cmap="viridis", extent=(0, C, 0.5, 10.5))
        fig.colorbar(im, ax=ax, label="median dead zone (m)"); ax.set_xlabel("position around the tower (m of arc)"); ax.set_ylabel("castle")
        ax.set_title(f"Dead zone by castle and position: {m}, {labels[0]}", loc="left"); fig.tight_layout(); fig.savefig(os.path.join(out, "charts", f"08_dead_zone_heatmap_{m}.png"), dpi=130); plt.close(fig)
    # 10. mechanic availability, stacked, per candidate and mode
    fig, axs = plt.subplots(1, 3, figsize=(15, 4.6), sharey=True)
    for ax, m in zip(axs, MODES):
        rs = [r for r in cand_rows if r["mode"] == m]
        if not rs: continue
        w = 0.8 / len(rs)
        for ci, r in enumerate(rs):
            xs = [i + ci * w for i in range(len(MECH))]
            a = [100 * r[f"fit_{k}_now"] for k in MECH]; b = [100 * r[f"fit_{k}_measured_only"] for k in MECH]
            ax.bar(xs, a, w, color=CAND_COL[ci % len(CAND_COL)], label=f"{r['candidate']}: fits now")
            ax.bar(xs, b, w, bottom=a, color=CAND_COL[ci % len(CAND_COL)], alpha=0.35, hatch="//", label=f"{r['candidate']}: only without 9.5 m rule")
        ax.set_xticks([i + 0.4 - w / 2 for i in range(len(MECH))]); ax.set_xticklabels(MECH, rotation=45, fontsize=8); ax.set_title(m, loc="left")
        ax.set_ylim(0, 100)
    axs[0].set_ylabel("share of route-deck positions (%)"); axs[-1].legend(frameon=False, fontsize=7, loc="lower right")
    fig.suptitle("Where each mechanic could stand on the route deck (cross-layer rules only)", x=0.01, ha="left"); fig.tight_layout(); fig.savefig(os.path.join(out, "charts", "10_mechanic_availability.png"), dpi=130); plt.close(fig)
    # 13. refusal Pareto (baseline): layer-rule classification and blanket-only by lower launcher type
    for m in MODES:
        mc = [c for c in base if c["mode"] == m]
        if not mc: continue
        tal = collections.Counter(); [tal.update(c["tal"]) for c in mc]
        items = sorted(((k.replace("geo_layer_refusal_blanket_only_", "blanket-only, lower "), v / len(mc)) for k, v in tal.items() if k.startswith("geo_layer_refusal_blanket_only_")), key=lambda kv: -kv[1])
        items = [("all layer refusals", tal["geo_layer_refusal"] / len(mc)), ("blanket 9.5 m only", tal["geo_layer_refusal_blanket_only"] / len(mc))] + items
        fig, ax = plt.subplots(figsize=(9, 4.2)); ax.barh([k for k, _ in items][::-1], [v for _, v in items][::-1], color=COL[m])
        ax.set_xlabel("refusals per course (attempt-level)"); ax.set_title(f"Layer refusals by the rule that caused them: {m}, {labels[0]}", loc="left")
        fig.tight_layout(); fig.savefig(os.path.join(out, "charts", f"13_refusal_pareto_{m}.png"), dpi=130); plt.close(fig)
    # 15-20. candidate comparison
    if len(labels) > 1:
        comp = [("pitch_median", "median pitch (m)"), ("revolutions", "revolutions in the climb"), ("route_elements", "route elements per course"),
                ("layer_refusals", "layer refusals per course"), ("hazards", "hazards placed per course"), ("enemies", "enemies placed per course"),
                ("accept_MORTAR", "mortar acceptance"), ("growth_1_10", "castle 1->10 growth"), ("gen_s", "generation s per course")]
        fig, axs = plt.subplots(3, 3, figsize=(15, 10))
        for ax, (k, t) in zip(axs.flat, comp):
            for mi, m in enumerate(MODES):
                rs = [r for r in cand_rows if r["mode"] == m]
                ax.bar([mi + ci * (0.8 / len(rs)) for ci in range(len(rs))], [r[k] for r in rs], 0.8 / max(1, len(rs)), color=[CAND_COL[ci % len(CAND_COL)] for ci in range(len(rs))])
            ax.set_xticks([i + 0.3 for i in range(3)]); ax.set_xticklabels(MODES, fontsize=8); ax.set_title(t, loc="left", fontsize=9)
        handles = [plt.Rectangle((0, 0), 1, 1, color=CAND_COL[i % len(CAND_COL)]) for i in range(len(labels))]
        fig.legend(handles, labels, loc="lower center", ncol=len(labels), frameon=False); fig.suptitle("Candidates compared (mean per course)", x=0.01, ha="left")
        fig.tight_layout(rect=(0, 0.04, 1, 1)); fig.savefig(os.path.join(out, "charts", "15_candidates.png"), dpi=130); plt.close(fig)


if __name__ == "__main__":
    main()
