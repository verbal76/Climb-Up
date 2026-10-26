#!/usr/bin/env python3
"""Consolidates three-lane difficulty report jobs (LaneReport output, one folder per mode/seed) into the owner's deliverables.

    lane_report.py <folder with one sub-folder per job> <out dir>

Writes: executive_report.pdf, detailed_report.pdf, segments_<run>.pdf (one 25 m diagnostic record per window), merged CSVs, report_full.json,
validation.json and INDEX.md. Missing or partial jobs are reported, never fatal. Diagnostic only: nothing here feeds the game.
"""
import csv, json, math, os, sys
from collections import defaultdict, OrderedDict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
MODES = ["NORMAL", "PUNISHER", "NGH"]
LANES = ["TERRAIN", "HAZARDS", "ENEMIES"]
LANE_COL = {"TERRAIN": "terrain_adj", "HAZARDS": "hazard_adj", "ENEMIES": "enemy_adj"}
COLORS = {"TERRAIN": "#3b7dd8", "HAZARDS": "#d8743b", "ENEMIES": "#8e44ad", "INTERACTION": "#7f8c8d"}
MODE_COL = {"NORMAL": "#2e86c1", "PUNISHER": "#cb4335", "NGH": "#17202a"}
FILES = ["windows.csv", "ledger.csv", "elements.csv", "links.csv", "modules.csv", "solver.csv", "solver_failures.csv", "generation_failures.csv",
         "rule_checks.csv", "inventory.csv", "module_inventory.csv", "castles.csv", "windows_v2_reference.csv", "chunks.csv", "hazards.csv"]
FUTURE_INTRO = {"NORMAL": 8, "PUNISHER": 4, "NGH": 1}           # owner's approximate intermediate-platform enemy introduction castle (future rule)
PURSUIT_FROM = {"NORMAL": None, "PUNISHER": 3333.0, "NGH": 1667.0}  # owner's future pursuit-into-hazard-section permission heights

# ------------------------------------------------------------------ load
tables = {f: [] for f in FILES}
runs, notes = [], []
for job in sorted(os.listdir(SRC)):
    d = os.path.join(SRC, job)
    if not os.path.isdir(d):
        continue
    for f in FILES:
        p = os.path.join(d, f)
        if os.path.exists(p):
            with open(p, newline="") as fh:
                tables[f].extend(csv.DictReader(fh))
    p = os.path.join(d, "run.json")
    if os.path.exists(p):
        try:
            runs.append(json.load(open(p)))
        except Exception as e:
            notes.append(f"{job}: run.json unreadable ({e})")
    else:
        notes.append(f"{job}: no run.json (job failed or timed out before writing its report)")
for r in runs:
    if r.get("stopped"):
        notes.append(f"{r['run']}: {r['stopped']} at {r['reached_m']:.0f} m")
    if r["reached_m"] < r["top_m"] - 1:
        notes.append(f"{r['run']}: reached {r['reached_m']:.0f} m of {r['top_m']:.0f} m (partial)")


def num(x):
    try:
        v = float(x)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


for f in FILES:
    if tables[f]:
        with open(os.path.join(OUT, f), "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(tables[f][0].keys()))
            w.writeheader()
            w.writerows(tables[f])

W = tables["windows.csv"]
by_run = defaultdict(list)
for w in W:
    by_run[w["run"]].append(w)
RUNS = sorted(by_run, key=lambda r: (MODES.index(r.split("-s")[0]) if r.split("-s")[0] in MODES else 9, r))

# ------------------------------------------------------------------ validation: PDF/CSV/JSON totals agree, ledgers reconcile, coverage complete
validation = OrderedDict()
ledger_sum, ledger_n = defaultdict(float), defaultdict(int)
for row in tables["ledger.csv"]:
    ledger_sum[(row["run"], row["window"], row["lane"])] += float(row["value"])
    ledger_n[(row["run"], row["window"], row["lane"])] += 1
for r in runs:
    rid, ws = r["run"], by_run.get(r["run"], [])
    v = OrderedDict()
    idx = sorted(int(w["window"]) for w in ws)
    expect = int(math.ceil(min(r["top_m"], r["reached_m"]) / 25.0))
    v["windows_expected"], v["windows_found"] = expect, len(idx)
    v["windows_contiguous"] = idx == list(range(len(idx)))
    tot = {k: sum(num(w[c]) or 0 for w in ws) for k, c in [("terrain_adj", "terrain_adj"), ("hazard_adj", "hazard_adj"), ("enemy_adj", "enemy_adj"), ("interaction", "interaction"), ("mitigation", "mitigation")]}
    v["csv_vs_json_totals_agree"] = all(abs(tot[k] - float(r["totals"][k])) < 0.01 * max(1, len(ws)) for k in tot)
    bad = 0
    for w in ws:
        for lane, col in [("TERRAIN", "terrain_adj"), ("HAZARDS", "hazard_adj"), ("ENEMIES", "enemy_adj"), ("INTERACTION", "interaction"), ("MITIGATION", "mitigation")]:
            key = (rid, w["window"], lane)      # rows and windows are printed to 3 decimals: allow that rounding per row
            if abs(ledger_sum[key] - (num(w[col]) or 0)) > 0.0006 * ledger_n[key] + 0.001:
                bad += 1
    v["ledger_mismatches"] = bad
    v["windows_without_geometry_flagged"] = sum(1 for w in ws if "NO_ROUTE_GEOMETRY" in w["flags"])
    v["scoring_model"] = r["scoring_model"]
    v["passed"] = v["windows_contiguous"] and v["csv_vs_json_totals_agree"] and bad == 0
    validation[rid] = v
json.dump({"runs": validation, "notes": notes}, open(os.path.join(OUT, "validation.json"), "w"), indent=1)

# ------------------------------------------------------------------ castle aggregates and progression analysis
castle = defaultdict(lambda: defaultdict(list))        # (mode, lane) -> castle -> [run means]
castle_run = {}                                        # (run, lane, castle) -> mean
for row in tables["castles.csv"]:
    m = num(row["mean"])
    if m is None:
        continue
    castle_run[(row["run"], row["lane"], int(row["castle"]))] = m
    castle[(row["mode"], row["lane"])][int(row["castle"])].append(m)


def cmean(mode, lane):
    d = castle.get((mode, lane), {})
    xs = sorted(d)
    return xs, [float(np.mean(d[x])) for x in xs]


def pct_changes(ys):
    out = []
    for a, b in zip(ys, ys[1:]):
        out.append(None if a == 0 else 100.0 * (b - a) / a)
    return out


def slope(xs, ys):
    return float(np.polyfit(xs, ys, 1)[0]) if len(xs) >= 2 else None


progression = OrderedDict()
for mode in MODES:
    for lane in LANES + ["TOTAL_REFERENCE"]:
        xs, ys = cmean(mode, lane)
        if not xs:
            continue
        ch = pct_changes(ys)
        progression[f"{mode}/{lane}"] = OrderedDict(
            castles=xs, means=[round(y, 3) for y in ys],
            castle_to_castle_pct=[None if c is None else round(c, 1) for c in ch],
            not_calculable=[f"C{xs[i]}->C{xs[i+1]} (zero baseline)" for i, c in enumerate(ch) if c is None],
            slope_per_castle=None if slope(xs, ys) is None else round(slope(xs, ys), 4),
            first_to_last_pct=None if not ys or ys[0] == 0 else round(100 * (ys[-1] - ys[0]) / ys[0], 1),
            declining_steps=sum(1 for a, b in zip(ys, ys[1:]) if b < a),
            abrupt_jumps=[f"C{xs[i]}->C{xs[i+1]}" for i in range(len(ys) - 1) if abs(ys[i + 1] - ys[i]) > max(0.5, 0.5 * max(ys))])


def nearest_normal(mode, lane):
    xs, ys = cmean(mode, lane)
    nx, ny = cmean("NORMAL", lane)
    if not xs or not nx:
        return None
    c1 = ys[0]
    best = min(range(len(nx)), key=lambda i: abs(ny[i] - c1))
    return {"mode_castle1_mean": round(c1, 3), "nearest_normal_castle": nx[best], "normal_mean_there": round(ny[best], 3)}


mode_cmp = OrderedDict()
for lane in LANES + ["TOTAL_REFERENCE"]:
    mode_cmp[lane] = {"PUNISHER_C1_vs_NORMAL (target ~C5)": nearest_normal("PUNISHER", lane), "NGH_C1_vs_NORMAL (target ~C8)": nearest_normal("NGH", lane)}

# owner future-rule checks (report only)
rules = OrderedDict()
rc = tables["rule_checks.csv"]
for mode in MODES:
    r1 = [int(r["castle"]) for r in rc if r["mode"] == mode and r["rule"] == "R1-intermediate-enemy"]
    r2 = [r for r in rc if r["mode"] == mode and r["rule"] == "R2-pursuit-into-hazard"]
    lim = PURSUIT_FROM[mode]
    r2bad = [r for r in r2 if lim is None or float(r["y"]) < lim]
    r3 = [r for r in rc if r["mode"] == mode and r["rule"] == "R3-protected-structure"]
    first = {}
    for e in tables["elements.csv"]:
        if e["run"].startswith(mode + "-") and e["lane"] == "ENEMIES":
            y = float(e["y"])
            first[e["type"]] = min(first.get(e["type"], 1e9), y)
    rules[mode] = OrderedDict(
        R1_intermediate_enemy_first_castle=min(r1) if r1 else None, R1_target_castle=FUTURE_INTRO[mode], R1_count=len(r1),
        R2_pursuit_into_hazard_total=len(r2), R2_outside_future_permission=len(r2bad), R2_future_rule=("prohibited" if lim is None else f"allowed from ~{lim:.0f} m"),
        R3_enemy_on_protected_structure=len(r3),
        R4_first_height_by_enemy={k: (round(first[k], 1) if k in first else "NOT PLACED") for k in ["CRAB", "BEE", "SKULL", "BLOB"]},
        R5_declining_castle_steps={lane: progression.get(f"{mode}/{lane}", {}).get("declining_steps") for lane in LANES})

inv = defaultdict(lambda: defaultdict(int))
for row in tables["inventory.csv"]:
    inv[(row["mode"], row["element_type"])]["placed"] += int(row["placed"])
    inv[(row["mode"], row["element_type"])]["active"] += int(row["scored"])
    inv[(row["mode"], row["element_type"])]["lane"] = row["lane"]
missing = OrderedDict()
for mode in MODES:
    missing[mode] = [t for (m, t), v in inv.items() if m == mode and v["lane"] in ("HAZARDS", "ENEMIES") and v["placed"] == 0]

full = OrderedDict(scoring_model=runs[0]["scoring_model"] if runs else None, runs=runs, validation=validation, notes=notes, progression=progression,
                   mode_comparison=mode_cmp, owner_rule_checks=rules, missing_hazard_and_enemy_types=missing,
                   normalization="castle mean of window adjusted scores; percentage change = (next - this) / this; a zero baseline is reported as not calculable, with absolute values kept",
                   files=FILES)
json.dump(full, open(os.path.join(OUT, "report_full.json"), "w"), indent=1, default=str)

# ------------------------------------------------------------------ PDF helpers
def text_page(pdf, title, lines, size=7):
    fig = plt.figure(figsize=(8.27, 11.69))
    fig.text(0.05, 0.97, title, va="top", fontsize=12, weight="bold")
    fig.text(0.05, 0.94, "\n".join(lines), va="top", family="monospace", fontsize=size)
    pdf.savefig(fig)
    plt.close(fig)


def wseries(run, col):
    ws = sorted(by_run[run], key=lambda w: int(w["window"]))
    return [float(w["y0"]) for w in ws], [num(w[col]) for w in ws]


def trend(ys, k=8):
    v = np.array([np.nan if y is None else y for y in ys], dtype=float)
    out = np.full_like(v, np.nan)
    for i in range(len(v)):
        seg = v[max(0, i - k // 2): i + k // 2 + 1]
        seg = seg[~np.isnan(seg)]
        if len(seg):
            out[i] = seg.mean()
    return out


ymax_lane = {lane: max([num(w[LANE_COL[lane]]) or 0 for w in W] + [1]) for lane in LANES}

# ------------------------------------------------------------------ executive PDF
with PdfPages(os.path.join(OUT, "executive_report.pdf")) as pdf:
    lines = [f"Scoring model {full['scoring_model']} (three lanes + separate interaction and mitigation ledgers). Diagnostic only.", ""]
    for r in runs:
        sv = [s for s in tables["solver.csv"] if s["run"] == r["run"]]
        ok = sum(1 for s in sv if s["completed"] == "true")
        lines.append(f"{r['run']:13s} reached {r['reached_m']:6.0f}/{r['top_m']:.0f} m  slices {r['slices']:3d}  windows {r['totals']['windows']:3d}  "
                     f"solver {ok}/{len(sv)} slices  gen {float(r['generation_seconds']):6.0f}s  {'STOPPED' if r.get('stopped') else ''}")
        lines.append(f"{'':13s} commit {r.get('commit','?')[:12]}  run {r.get('actions_run_id','?')}  rules v{r['generator_rules_version']}  totals T {r['totals']['terrain_adj']} H {r['totals']['hazard_adj']} E {r['totals']['enemy_adj']} X {r['totals']['interaction']} M {r['totals']['mitigation']}")
    lines += ["", "Validation: " + ", ".join(f"{k} {'PASS' if v['passed'] else 'FAIL'}" for k, v in validation.items())]
    lines += ["", "Missing hazard/enemy types (placed 0 in every run of the mode):"] + [f"  {m}: {', '.join(v) or 'none'}" for m, v in missing.items()]
    lines += ["", "Mode comparison (castle-1 mean against the nearest Normal castle):"]
    for lane, d in mode_cmp.items():
        for k, v in d.items():
            lines.append(f"  {lane:15s} {k}: {v}")
    lines += ["", "Owner future-rule checks (reported, not enforced):"]
    for m, d in rules.items():
        lines.append(f"  {m}: R1 first castle {d['R1_intermediate_enemy_first_castle']} (target ~{d['R1_target_castle']}), R2 pursuit into hazard {d['R2_pursuit_into_hazard_total']} "
                     f"({d['R2_outside_future_permission']} outside future permission: {d['R2_future_rule']}), R3 protected {d['R3_enemy_on_protected_structure']}")
        lines.append(f"      R4 first height: {d['R4_first_height_by_enemy']}   R5 declining castle steps: {d['R5_declining_castle_steps']}")
    lines += ["", "Notes / incomplete datasets:"] + ["  " + n for n in notes] + ["", "Measurement limitations:"] + ["  " + x for x in (runs[0]["measurement_limits"] if runs else [])]
    text_page(pdf, "Upwardly - three-lane difficulty baseline (executive summary)", lines, 6)

    fig, axs = plt.subplots(2, 2, figsize=(11.69, 8.27))
    for ax, lane in zip(axs.flat, LANES + ["TOTAL_REFERENCE"]):
        for mode in MODES:
            xs, ys = cmean(mode, lane)
            if xs:
                ax.plot(xs, ys, marker="o", color=MODE_COL[mode], label=mode)
                for x, y in zip(xs, ys):
                    ax.annotate(f"{y:.1f}", (x, y), fontsize=5, textcoords="offset points", xytext=(0, 3), ha="center")
        ax.set_title(f"{lane}: castle mean of 25 m windows (mean over seeds)", fontsize=9)
        ax.set_xticks(range(1, 11)); ax.set_xlabel("castle"); ax.grid(alpha=0.3); ax.legend(fontsize=7)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

    fig, axs = plt.subplots(1, 3, figsize=(11.69, 4.5))
    for ax, mode in zip(axs, MODES):
        for lane in LANES:
            xs, ys = cmean(mode, lane)
            if xs and ys[0] > 0:
                ax.plot(xs, [100 * y / ys[0] for y in ys], marker="o", color=COLORS[lane], label=lane)
            elif xs:
                ax.plot([], [], color=COLORS[lane], label=f"{lane} (C1 = 0: % not calculable)")
        ax.set_title(f"{mode}: lanes as % of their castle-1 mean", fontsize=9); ax.set_xticks(range(1, 11)); ax.grid(alpha=0.3); ax.legend(fontsize=6)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

    fig, ax = plt.subplots(figsize=(11.69, 5))
    types = [t for t in sorted({t for (_, t) in inv}) if inv[("NORMAL", t)]["lane"] in ("HAZARDS", "ENEMIES") or inv[("PUNISHER", t)]["lane"] in ("HAZARDS", "ENEMIES")]
    xs = np.arange(len(types))
    for i, mode in enumerate(MODES):
        ax.bar(xs + (i - 1) * 0.27, [inv[(mode, t)]["placed"] for t in types], 0.27, color=MODE_COL[mode], label=mode)
    ax.set_xticks(xs); ax.set_xticklabels(types, rotation=45, fontsize=7); ax.set_title("Hazard and enemy placements per mode (all seeds); zero = defined but not placed"); ax.legend()
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

    fig, ax = plt.subplots(figsize=(11.69, 5))
    for mode in MODES:
        fails = defaultdict(int)
        for s in tables["solver_failures.csv"]:
            if s["run"].startswith(mode + "-"):
                fails[int(s["castle"])] += 1
        ax.plot(range(1, 11), [fails[c] for c in range(1, 11)], marker="o", color=MODE_COL[mode], label=mode)
    ax.set_title("Solver failures per castle (all seeds). A solver failure is not proof that the course is impossible."); ax.set_xticks(range(1, 11)); ax.legend(); ax.grid(alpha=0.3)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

# ------------------------------------------------------------------ detailed PDF
ledger_by = defaultdict(list)
for row in tables["ledger.csv"]:
    ledger_by[(row["run"], int(row["window"]))].append(row)
el_by = defaultdict(list)
for e in tables["elements.csv"]:
    el_by[(e["run"], int(e["window"]))].append(e)
mod_by = defaultdict(list)
for m in tables["modules.csv"]:
    mod_by[m["run"]].append(m)

with PdfPages(os.path.join(OUT, "detailed_report.pdf")) as pdf:
    text_page(pdf, "Scoring methodology and legend", [
        f"Model {full['scoring_model']}. Primary resolution 25 m; castles are 500 m (20 windows).",
        "TERRAIN (per route link, split across windows by the height it spans):",
        "  T-GAP 2 x clamp((gap/reach - 0.4)/0.6)   reach = longest jump at that rise (EncounterModel.reach)",
        "  T-LONG 3 x (gap/reach - 0.85) when over 0.85 (EncounterModel; covers grab/catch gaps up to 1.2)",
        "  T-LAND 1.5 x clamp((3 m - landing width)/2 m)",
        "  T-MOVE-TAKEOFF 0.5, T-TILT-TAKEOFF 0.6, T-MOVE-TARGET 0.8, T-CRUMBLE 0.7, T-WET 0.4 (EncounterModel)   T-RAMP-VARIANT 0.4",
        "  T-TRAVERSE / T-LAUNCH 0: ropes, cables, pads and springs used to travel (owner-approved zero; recorded as measured zero)",
        "  Terrain has no repetition discount. EncounterModel's narrow take-off is recorded raw, not scored (width already scored as landing).",
        "HAZARDS: one ledger row per hazard anchored on a route platform, weight from difficulty_weights.properties.",
        "ENEMIES: one entry per enemy that can reach a route platform (EncounterModel.reaches), split over the windows of the platforms it reaches.",
        "  Repetition (hazards and enemies, per type and window): first 3 full, each further one 0.25.",
        "MITIGATION: clubs on route platforms (1 each), reported separately, never subtracted from a lane.",
        "INTERACTION (separate ledger, 0.5 each, capped 2 per window): terrain >= 1 with a hazard or enemy, hazard with enemy, 2+ hazard or enemy families on one link.",
        "TOTAL_REFERENCE = terrain + hazards + enemies + interaction - mitigation: for reference only.",
        "Raw = weight x window fraction (before repetition); adjusted = after repetition. Blank = not measured (never a silent zero).",
        "Solver time is supporting evidence, never a score. Flags: NO_ROUTE_GEOMETRY, SOLVER_FAILED, SOLVER_UNREACHED, MODULE_UNATTRIBUTED, RUN_STOPPED_HERE.",
        "v2 reference (DifficultyScore) is a different model: shown as its own column, never compared directly."], 7)

    for run in RUNS:
        fig, axs = plt.subplots(4, 1, figsize=(11.69, 8.27), sharex=True)
        for ax, lane in zip(axs, LANES):
            x, y = wseries(run, LANE_COL[lane])
            ax.bar(x, [0 if v is None else v for v in y], width=22, color=COLORS[lane], alpha=0.55, label="window (raw data)")
            ax.plot(x, trend(y), color="black", lw=1, ls="--", label="trend (8-window mean)")
            ax.set_ylim(0, ymax_lane[lane] * 1.05); ax.set_ylabel(lane, fontsize=7); ax.legend(fontsize=6, loc="upper left")
        x, y = wseries(run, "total_reference")
        axs[3].plot(x, [0 if v is None else v for v in y], color="#555", lw=0.8); axs[3].set_ylabel("total ref", fontsize=7)
        for c in range(1, 11):
            for ax in axs:
                ax.axvline(c * 500, color="#bbb", lw=0.5)
        axs[0].set_title(f"{run}: three-lane progression over the whole climb (adjusted scores, 25 m windows)")
        axs[3].set_xlabel("height (m)")
        fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

        fig, axs = plt.subplots(2, 2, figsize=(11.69, 8.27))
        for lane in LANES:
            vals = [num(w[LANE_COL[lane]]) for w in by_run[run] if num(w[LANE_COL[lane]]) is not None]
            axs[0, 0].hist(vals, bins=20, alpha=0.5, color=COLORS[lane], label=lane)
        axs[0, 0].set_title("distribution of window scores"); axs[0, 0].legend(fontsize=7)
        mk = defaultdict(int)
        for m in mod_by[run]:
            if m["final_attempt"] == "true" and m["accepted"] == "true":
                mk[m["kind"]] += 1
        ks = sorted(mk, key=lambda k: -mk[k])
        axs[0, 1].bar(ks, [mk[k] for k in ks], color="#5d6d7e"); axs[0, 1].set_title("modules accepted (final attempts)"); axs[0, 1].tick_params(axis="x", rotation=70, labelsize=6)
        hz, en = defaultdict(int), defaultdict(int)
        for e in tables["elements.csv"]:
            if e["run"] == run and e["lane"] == "HAZARDS":
                hz[e["type"]] += 1
            if e["run"] == run and e["lane"] == "ENEMIES":
                en[e["type"]] += 1
        axs[1, 0].bar(list(hz), list(hz.values()), color=COLORS["HAZARDS"]); axs[1, 0].set_title("hazard placements"); axs[1, 0].tick_params(axis="x", rotation=45, labelsize=7)
        axs[1, 1].bar(list(en), list(en.values()), color=COLORS["ENEMIES"]); axs[1, 1].set_title("enemy placements"); axs[1, 1].tick_params(axis="x", labelsize=7)
        fig.suptitle(f"{run}: distributions and frequencies"); fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

        for c in range(1, 11):
            ws = sorted([w for w in by_run[run] if int(w["castle"]) == c], key=lambda w: int(w["window"]))
            if not ws:
                continue
            fig, axs = plt.subplots(3, 2, figsize=(11.69, 8.27))
            x = [float(w["y0"]) for w in ws]
            for ax, lane in zip([axs[0, 0], axs[0, 1], axs[1, 0]], LANES):
                y = [num(w[LANE_COL[lane]]) for w in ws]
                ax.bar(x, [0 if v is None else v for v in y], width=20, color=COLORS[lane])
                for xi, yi in zip(x, y):
                    ax.annotate("n/a" if yi is None else f"{yi:.1f}", (xi, 0 if yi is None else yi), fontsize=5, ha="center", textcoords="offset points", xytext=(0, 2))
                ax.set_ylim(0, ymax_lane[lane] * 1.1); ax.set_title(f"{lane} per 25 m", fontsize=8)
            for lane in LANES:
                y = [num(w[LANE_COL[lane]]) for w in ws]
                axs[1, 1].plot(x, [np.nan if v is None else v for v in y], marker=".", color=COLORS[lane], label=lane)
            axs[1, 1].set_title("three-lane overlay", fontsize=8); axs[1, 1].legend(fontsize=6)
            byk, byt = defaultdict(lambda: defaultdict(float)), defaultdict(lambda: defaultdict(float))
            elmod = {e["id"]: e["module_kind"] for e in tables["elements.csv"] if e["run"] == run}
            for w in ws:
                for row in ledger_by[(run, int(w["window"]))]:
                    if row["lane"] in LANES:
                        src = row["source"].split(">")[0]
                        byk[elmod.get(src, "?")][int(w["window"])] += float(row["value"])
                        byt[row["rule"]][int(w["window"])] += float(row["value"])
            for ax, data, title in [(axs[2, 0], byk, "contribution by module kind"), (axs[2, 1], byt, "contribution by rule / element type")]:
                bottom = np.zeros(len(ws))
                for key in sorted(data, key=lambda k: -sum(data[k].values()))[:12]:
                    vals = np.array([data[key].get(int(w["window"]), 0) for w in ws])
                    ax.bar(x, vals, width=20, bottom=bottom, label=key); bottom += vals
                ax.set_title(title, fontsize=8); ax.legend(fontsize=5, ncol=2)
            fig.suptitle(f"{run} castle {c}"); fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

        lines = ["window  y0     T      H      E      X      M   links fail unre  setb rej  modules / flags"]
        for w in sorted(by_run[run], key=lambda w: int(w["window"])):
            lines.append(f"{int(w['window']):5d} {float(w['y0']):6.0f} {w['terrain_adj'] or 'n/a':>6} {w['hazard_adj']:>6} {w['enemy_adj']:>6} {w['interaction']:>6} {w['mitigation']:>5} "
                         f"{w['links']:>4} {w['solver_links_failed'] or '-':>4} {w['solver_links_unreached'] or '-':>4} {w['solver_setbacks'] or '-':>4} {w['gen_rejected_attempts']:>3}  {w['flags'][:40]}")
        for i in range(0, len(lines), 95):
            text_page(pdf, f"{run}: 25 m segment table ({i // 95 + 1})", lines[i:i + 95] if i == 0 else [lines[0]] + lines[i:i + 95], 6)

        tried = defaultdict(lambda: [0, 0, 0, 0, 0, 0, 0, 0])
        for m in mod_by[run]:
            t = tried[m["kind"]]
            t[0] += 1; t[1] += m["accepted"] == "true" and m["final_attempt"] == "true"; t[2] += m["accepted"] != "true"
            for j, k in enumerate(["refused_layer", "refused_hazard", "refused_plan", "refused_window", "refused_conflict"]):
                t[3 + j] += int(m[k])
        lines = ["kind          tried accepted refused  layer hazard   plan window conflict"] + [f"{k:12s} {v[0]:6d} {v[1]:8d} {v[2]:7d} {v[3]:6d} {v[4]:6d} {v[5]:6d} {v[6]:6d} {v[7]:8d}" for k, v in sorted(tried.items())]
        gf = [g for g in tables["generation_failures.csv"] if g["run"] == run]
        lines += ["", f"slice attempts that failed and were retried: {len(gf)}"] + [f"  slice {g['slice']}: {g['detail'][:120]}" for g in gf[:30]]
        sf = [s for s in tables["solver_failures.csv"] if s["run"] == run]
        lines += ["", f"solver failures: {len(sf)}"] + [f"  slice {s['slice']} {s['from']}->{s['to']} ({s['from_type']}->{s['to_type']}) {float(s['y0']):.0f} m castle {s['castle']}: {s['fail_info'][:90]}" for s in sf[:40]]
        text_page(pdf, f"{run}: generator selection and solver failure diagnostics", lines, 6)

    fig, axs = plt.subplots(3, 3, figsize=(11.69, 8.27))
    for i, mode in enumerate(MODES):
        for j, lane in enumerate(LANES):
            ax = axs[i, j]
            for run in RUNS:
                if run.startswith(mode + "-"):
                    xs = sorted({c for (r, l, c) in castle_run if r == run and l == lane})
                    ax.plot(xs, [castle_run[(run, lane, c)] for c in xs], marker="o", label=run.split("-")[1])
            ax.set_title(f"{mode} {lane}: seed-to-seed", fontsize=7); ax.set_xticks(range(1, 11)); ax.tick_params(labelsize=6); ax.legend(fontsize=6)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

    # owner review 2026-10-26: castle 1-10 means (over seeds) of all nine mode/lane combinations, one shared scale per lane (same scoring for every
    # mode), NORMAL drawn faintly in the other modes for reference
    fig, axs = plt.subplots(3, 3, figsize=(11.69, 8.27))
    for j, lane in enumerate(LANES):
        vals = [x for m in MODES for x in progression.get(f"{m}/{lane}", {}).get("means", []) if isinstance(x, (int, float))]
        top = (max(vals) if vals else 1.0) * 1.1
        for i, mode in enumerate(MODES):
            ax = axs[i, j]; xs = list(range(1, 11))
            pg = progression.get(f"{mode}/{lane}", {}); ys = pg.get("means", []); cx = pg.get("castles", [])
            if mode != "NORMAL":
                ng = progression.get(f"NORMAL/{lane}", {})
                ax.plot(ng.get("castles", []), ng.get("means", []), color="#bbbbbb", lw=1, label="NORMAL")
            ax.plot(cx, ys, marker="o", lw=1.6, label=mode)
            ax.set_ylim(0, top); ax.set_xticks(xs); ax.tick_params(labelsize=6); ax.grid(alpha=0.3)
            ax.set_title(f"{mode} {lane} (castle means, shared {lane.lower()} scale)", fontsize=7); ax.legend(fontsize=6, loc="lower right")
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)

    lines = []
    for k, v in progression.items():
        lines.append(f"{k}: means {v['means']}")
        lines.append(f"    castle-to-castle % {v['castle_to_castle_pct']}  slope {v['slope_per_castle']}  first->last % {v['first_to_last_pct']}  declining steps {v['declining_steps']}  abrupt {v['abrupt_jumps']}  not calculable {v['not_calculable']}")
    text_page(pdf, "Progression analysis (all modes, lanes)", lines, 5.5)

# ------------------------------------------------------------------ per-run 25 m segment diagnostic records (with a route/element diagram)
LANE_MARK = {"TERRAIN": ("s", "#3b7dd8"), "HAZARDS": ("^", "#d8743b"), "ENEMIES": ("o", "#8e44ad"), "UTILITY": ("D", "#27ae60")}
for run in RUNS:
    with PdfPages(os.path.join(OUT, f"segments_{run}.pdf")) as pdf:
        for w in sorted(by_run[run], key=lambda w: int(w["window"])):
            wi = int(w["window"])
            fig = plt.figure(figsize=(8.27, 11.69))
            ax = fig.add_axes([0.08, 0.55, 0.86, 0.38])
            els = el_by[(run, wi)]
            if els:
                s0 = min(float(e["s"]) for e in els)
                for e in els:
                    mk, col = LANE_MARK.get(e["lane"], ("x", "k"))
                    s, y, wd = float(e["s"]) - s0, float(e["y"]), float(e["w"] or 0)
                    if e["lane"] == "TERRAIN" and wd > 0:
                        ax.plot([s - wd / 2, s + wd / 2], [y, y], color=col, lw=3 if e["route"] == "true" else 1, alpha=1 if e["route"] == "true" else 0.4)
                    else:
                        ax.scatter([s], [y], marker=mk, color=col, s=25, zorder=3)
                        ax.annotate(e["type"], (s, y), fontsize=5, xytext=(2, 2), textcoords="offset points")
            ax.set_xlabel("arc length from the window's first element (m)"); ax.set_ylabel("height (m)")
            ax.set_title(f"{run} window {wi} ({float(w['y0']):.0f}-{float(w['y1']):.0f} m, castle {w['castle']}): thick = route platform, thin = decoy", fontsize=8)
            rows = sorted(ledger_by[(run, wi)], key=lambda r: -float(r["value"]))
            lines = [f"TERRAIN {w['terrain_adj'] or 'n/a (no route geometry)'}  HAZARDS {w['hazard_adj']}  ENEMIES {w['enemy_adj']}  INTERACTION {w['interaction']}  MITIGATION {w['mitigation']}  total ref {w['total_reference']}  (v2 ref {w['v2_reference_score'] or 'n/a'})",
                     f"links {w['links']} (demanding {w['demanding_links']})  max gap ratio {w['max_gap_ratio'] or 'n/a'}  min landing width {w['min_landing_width'] or 'n/a'}  decoys {w['decoy_elements']}  off-route hazards {w['off_route_hazards']}  unreachable enemies {w['unreachable_enemies']}",
                     f"solver: traversed {w['solver_links_traversed'] or 'n/a'} failed {w['solver_links_failed'] or 'n/a'} unreached {w['solver_links_unreached'] or 'n/a'} time {w['solver_time_s'] or 'n/a'} s setbacks {w['solver_setbacks'] or 'n/a'} plan failures {w['solver_plan_failures'] or 'n/a'}",
                     f"generator: rejected attempts here {w['gen_rejected_attempts']}  fallbacks {w['gen_fallbacks']}  modules {w['modules']}", f"counts {w['counts']}   flags {w['flags'] or 'none'}", "",
                     "lane         rule              source            value  weight frac  rep   property"]
            for r in rows[:45]:
                lines.append(f"{r['lane']:12s} {r['rule']:17s} {r['source']:17s} {float(r['value']):6.3f} {float(r['weight']):5.2f} {float(r['fraction']):4.2f} {float(r['repetition']):4.2f}  {r['property'][:60]}")
            if len(rows) > 45:
                lines.append(f"... {len(rows) - 45} more rows in ledger.csv")
            hz = [e for e in els if e["lane"] in ("HAZARDS", "ENEMIES")]
            lines += ["", "hazards / enemies (raw element parameters the Sim interprets):"] + [f"  {e['id']:10s} {e['type']:11s} route={e['route']:5s} s={float(e['s']):7.2f} y={float(e['y']):7.2f} w={e['w']} amp={e['amp']} period={e['period']} len={e['len']} dir={e['dir']} module={e['module_kind']} [{e['structure']}]" for e in hz[:18]]
            fig.text(0.04, 0.50, "\n".join(lines), va="top", family="monospace", fontsize=5)
            pdf.savefig(fig); plt.close(fig)

# ------------------------------------------------------------------ expansion (2026-10-26): components, pacing, timing, memory, exposure, coverage
def wcsv(name, header, rows):
    with open(os.path.join(OUT, name), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(header); w.writerows(rows)

run_mode = {r: r.split("-s")[0] for r in RUNS}
comp = defaultdict(float)                                  # (mode, run, castle, lane, rule) -> value
for row in tables["ledger.csv"]:
    comp[(run_mode.get(row["run"], "?"), row["run"], int(row["castle"]), row["lane"], row["rule"])] += float(row["value"])
wcsv("components.csv", ["mode", "run", "castle", "lane", "rule", "value"], [list(k) + [round(v, 3)] for k, v in sorted(comp.items())])

pacing = []
for run in RUNS:
    for c in range(1, 11):
        ws = sorted([w for w in by_run[run] if int(w["castle"]) == c], key=lambda w: int(w["window"]))
        if not ws:
            continue
        tot = [num(w["total_reference"]) or 0 for w in ws]
        q1, q3 = np.percentile(tot, 25), np.percentile(tot, 75)
        spikes = [int(w["window"]) for w, v in zip(ws, tot) if v > q3 + 1.5 * (q3 - q1)]
        run_len = best = 0
        for v in tot:
            run_len = run_len + 1 if v <= q1 else 0; best = max(best, run_len)
        lanes_zero = {lane: sum(1 for w in ws if (num(w[LANE_COL[lane]]) or 0) == 0) for lane in LANES}
        pacing.append([run, run_mode[run], c, len(ws), round(float(np.mean(tot)), 3), round(float(np.std(tot)), 3), round(float(q1), 3), round(float(q3), 3), len(spikes), " ".join(map(str, spikes)), best * 25,
                       lanes_zero["TERRAIN"], lanes_zero["HAZARDS"], lanes_zero["ENEMIES"]])
wcsv("pacing.csv", ["run", "mode", "castle", "windows", "mean_total", "sd_total", "q1", "q3", "spike_windows", "spike_window_ids", "longest_valley_m", "zero_terrain_windows", "zero_hazard_windows", "zero_enemy_windows"], pacing)

CH = tables["chunks.csv"]
timing = defaultdict(lambda: [0.0, 0.0, 0, 0, 0.0, 0.0, 0.0])        # (run, castle) -> gen s, solve s, slices, failed attempts, failed s, alloc gen MB, alloc solve MB
for r in CH:
    t = timing[(r["run"], int(r["castle"]))]
    t[0] += num(r["gen_wall_s"]) or 0; t[1] += num(r["solve_wall_s"]) or 0; t[2] += 1; t[3] += int(r["failed_attempts"]); t[4] += num(r["failed_attempts_s"]) or 0
    t[5] += (num(r["gen_alloc_bytes"]) or 0) / 1e6; t[6] += (num(r["solve_alloc_bytes"]) or 0) / 1e6
wcsv("timing.csv", ["run", "castle", "gen_wall_s", "solve_wall_s", "slices", "failed_slice_attempts", "failed_slice_attempts_s", "gen_alloc_mb", "solve_alloc_mb"],
     [[k[0], k[1]] + [round(x, 3) if isinstance(x, float) else x for x in v] for k, v in sorted(timing.items())])
mt = defaultdict(lambda: [0, 0, 0.0, 0.0])                          # (run, kind, role) -> tried, accepted, accepted ms, refused ms
for m in tables["modules.csv"]:
    if m.get("wall_ms", "") == "":
        continue
    t = mt[(m["run"], m["kind"], m["role"])]; t[0] += 1
    if m["accepted"] == "true" and m["final_attempt"] == "true":
        t[1] += 1; t[2] += float(m["wall_ms"])
    else:
        t[3] += float(m["wall_ms"])
wcsv("module_timing.csv", ["run", "kind", "role", "tried", "accepted", "acceptance_rate", "accepted_ms", "refused_or_discarded_ms", "mean_ms_per_try"],
     [[k[0], k[1], k[2], v[0], v[1], round(v[1] / v[0], 3) if v[0] else "", round(v[2], 1), round(v[3], 1), round((v[2] + v[3]) / v[0], 2) if v[0] else ""] for k, v in sorted(mt.items())])

HZ = tables["hazards.csv"]
expo = defaultdict(lambda: [0, 0, 0, 0])                             # (mode, type) -> placed, scored, sharing a platform, route links exposed
for h in HZ:
    e = expo[(run_mode.get(h["run"], "?"), h["type"])]; e[0] += 1; e[1] += h["status"] in ("SCORED", "MITIGATION")
    e[2] += h["same_platform_threats"] not in ("[]", ""); e[3] += int(h["route_links_exposed"] or 0)
wcsv("exposure.csv", ["mode", "type", "placed", "scored_or_active", "sharing_platform_with_other_threats", "route_links_exposed_total"], [list(k) + v for k, v in sorted(expo.items())])

COVERAGE = [
    ("generation time per run / castle / chunk", "available", "chunks.csv, timing.csv, run.json"),
    ("generation time per module attempt and slice attempt", "available", "modules.csv wall_ms, chunks.csv"),
    ("time in accepted / refused / fallback candidates and slice retries", "available", "chunks.csv, module_timing.csv"),
    ("time in validation, solver proof and rollback inside one attempt", "unavailable", "they run inside one generator call; separating them needs new hooks deep in commit()/rollbackTo"),
    ("module selection attempts, refusal reasons, acceptance rates", "available", "modules.csv, module_timing.csv, module_inventory.csv"),
    ("per-type hazard/enemy selection attempts and refusals", "unavailable", "placement draws happen inside module builders; only module-level attempts and refusal counters are observable"),
    ("missing hazard/enemy types", "available", "inventory.csv, report_full.json"),
    ("memory: allocated bytes per slice (generation, solver), heap used", "available", "chunks.csv (HotSpot ThreadMXBean)"),
    ("terrain geometry, gap ratio, landing width, route mechanics, recovery platforms", "available", "links.csv, windows.csv route_mechanics / recovery_platforms"),
    ("hazard parameters, hit radius, route exposure, overlapping threats", "partial", "hazards.csv, exposure.csv; swept danger areas and timing windows of aimed cannons and spikes are not computed"),
    ("enemy aggression parameters, placement, reach/pursuit", "available", "run.json mode_parameters, hazards.csv enemy_reach_*"),
    ("actual enemy encounters (contacts) during solving", "unavailable", "the solver reports setbacks per link, not which object caused them; links.csv setbacks next to its enemies/hazards is the closest evidence"),
    ("terrain/hazard/enemy interactions", "available", "ledger.csv INTERACTION rows"),
    ("solver planning, wall time, sim time, refused plans, revisits, failures and locations", "available", "links.csv, solver.csv, solver_failures.csv"),
    ("spikes, valleys, pacing, castle progression", "available", "pacing.csv, report_full.json progression"),
    ("component contributions per mode", "available", "components.csv, components_report.pdf"),
    ("placement-rule checks", "available", "rule_checks.csv, report_full.json owner_rule_checks"),
]
blank = []
for f in FILES:
    rows = tables[f]
    if not rows:
        blank.append([f, "", "NO ROWS (file absent or empty)", ""]); continue
    for col in rows[0].keys():
        n = sum(1 for r in rows if r.get(col, "") in ("", None))
        if n:
            blank.append([f, col, n, len(rows)])
wcsv("coverage.csv", ["measurement", "status", "where / why"], COVERAGE)
wcsv("unmeasured_fields.csv", ["file", "column", "blank_values", "rows"], blank)

with PdfPages(os.path.join(OUT, "components_report.pdf")) as pdf:
    for mode in MODES:
        runs_m = [r for r in RUNS if run_mode[r] == mode]
        if not runs_m:
            continue
        fig, axs = plt.subplots(3, 1, figsize=(11.69, 8.27), sharex=True)
        for ax, lane in zip(axs, LANES):
            rules_ = sorted({k[4] for k in comp if k[0] == mode and k[3] == lane})
            bottom = np.zeros(10)
            for rule in rules_:
                vals = np.array([sum(comp.get((mode, r, c, lane, rule), 0) for r in runs_m) / len(runs_m) for c in range(1, 11)])
                ax.bar(range(1, 11), vals, bottom=bottom, label=rule); bottom += vals
            ax.set_ylabel(lane, fontsize=8); ax.legend(fontsize=5, ncol=4, loc="upper left")
        axs[0].set_title(f"{mode}: lane totals per castle by component (mean over seeds)"); axs[2].set_xticks(range(1, 11)); axs[2].set_xlabel("castle")
        fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    fig, axs = plt.subplots(2, 2, figsize=(11.69, 8.27))
    for mode in MODES:
        for ax, idx_, title in [(axs[0, 0], 0, "generation wall time per castle (s)"), (axs[0, 1], 1, "solver wall time per castle (s)"), (axs[1, 0], 5, "generation allocation per castle (MB)")]:
            xs = list(range(1, 11)); ys = [np.mean([timing[(r, c)][idx_] for r in RUNS if run_mode[r] == mode and (r, c) in timing] or [np.nan]) for c in xs]
            ax.plot(xs, ys, marker="o", color=MODE_COL[mode], label=mode); ax.set_title(title, fontsize=8); ax.legend(fontsize=6)
    for mode in MODES:
        acc = sum(v[2] for k, v in mt.items() if run_mode.get(k[0]) == mode) / 1000; rej = sum(v[3] for k, v in mt.items() if run_mode.get(k[0]) == mode) / 1000
        axs[1, 1].bar([f"{mode}\naccepted", f"{mode}\nrefused"], [acc, rej], color=MODE_COL[mode])
    axs[1, 1].set_title("module time: accepted vs refused/discarded (s, all seeds)", fontsize=8); axs[1, 1].tick_params(labelsize=6)
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    fig, axs = plt.subplots(1, 2, figsize=(11.69, 5))
    for mode in MODES:
        xs = list(range(1, 11))
        axs[0].plot(xs, [np.mean([p[8] for p in pacing if p[1] == mode and p[2] == c] or [np.nan]) for c in xs], marker="o", color=MODE_COL[mode], label=mode)
        axs[1].plot(xs, [np.mean([p[10] for p in pacing if p[1] == mode and p[2] == c] or [np.nan]) for c in xs], marker="o", color=MODE_COL[mode], label=mode)
    axs[0].set_title("difficulty spikes per castle (windows above Q3 + 1.5 IQR)", fontsize=8); axs[1].set_title("longest valley per castle (m at or below Q1)", fontsize=8)
    for ax in axs:
        ax.legend(fontsize=6); ax.set_xticks(range(1, 11))
    fig.tight_layout(); pdf.savefig(fig); plt.close(fig)
    text_page(pdf, "Measurement coverage (expansion)", [f"{m:75s} {st:12s} {why}" for m, st, why in COVERAGE] + ["", "Blank (unmeasured) fields per file: unmeasured_fields.csv"], 5.5)

# ------------------------------------------------------------------ index
idx = ["# Upwardly three-lane difficulty baseline - report index", "",
       f"Scoring model: `{full['scoring_model']}` (reference: v2 DifficultyScore, separate column). Generator rules: v{runs[0]['generator_rules_version'] if runs else '?'}.",
       f"Source commit: `{runs[0].get('commit', '?') if runs else '?'}`  branch `{runs[0].get('branch', '?') if runs else '?'}`  GitHub Actions run `{runs[0].get('actions_run_id', '?') if runs else '?'}`", "",
       "| file | contents |", "|---|---|",
       "| executive_report.pdf | one-page summary, mode comparisons, castle progression per lane, missing mechanics, solver failures, limitations |",
       "| detailed_report.pdf | methodology, per-run progression, distributions, per-castle graphs (lanes, overlay, module and element contributions), 25 m tables, generator and solver diagnostics, cross-seed, progression analysis |",
       "| segments_<run>.pdf | one diagnostic record per 25 m window: route/element diagram, scores, every ledger row, hazards/enemies, solver, generator |",
       "| windows.csv | every 25 m window: raw and adjusted lane scores, geometry, solver, generator, counts, flags |",
       "| castles.csv | per castle and lane: n, mean, median, min, max, sd, quartiles, spikes, gaps, solver |",
       "| ledger.csv | every contribution: lane, rule, source id, property, raw, weight, fraction, repetition, value |",
       "| elements.csv | every placed element and hazard: id, type, lane, position, parameters, module, structure |",
       "| links.csv | every route link: geometry, EncounterModel terms, terrain, hazards/enemies, solver outcome |",
       "| modules.csv | every module the generator tried (accepted and refused) with refusal counters |",
       "| solver.csv / solver_failures.csv | per slice solver outcome / each failure with height, castle and reason |",
       "| generation_failures.csv | slice attempts that failed and were retried |",
       "| inventory.csv / module_inventory.csv | all 29 element types and 25 module kinds (+ structures): defined, placed, active |",
       "| rule_checks.csv | owner future-rule discrepancies (reported, not enforced) |",
       "| windows_v2_reference.csv | the earlier single-score model, for reference only |",
       "| report_full.json | all runs, totals, castles, progression, mode comparison, rule checks, validation, notes |",
       "| validation.json | coverage, ledger reconciliation and CSV/JSON agreement per run |",
       "| components_report.pdf | per-mode lane components by castle, generation/solver timing and allocation, module time accepted vs refused, spikes and valleys, coverage |",
       "| chunks.csv / timing.csv / module_timing.csv | generation and solver wall time, slice retries, accepted/refused module time, allocation and heap per slice/castle/kind |",
       "| hazards.csv / exposure.csv | every hazard/enemy/club: parameters, hit radius, route exposure, threats sharing its platform, enemy reach |",
       "| components.csv / pacing.csv | every rule's contribution per mode, run, castle and lane / spikes, valleys and zero-lane windows per castle |",
       "| coverage.csv / unmeasured_fields.csv | what is measured, partial or unavailable (and why) / blank values per column |", "",
       "Incomplete datasets: " + ("; ".join(notes) if notes else "none")]
open(os.path.join(OUT, "INDEX.md"), "w").write("\n".join(idx) + "\n")
print(f"runs {len(runs)}, windows {len(W)}, ledger {len(tables['ledger.csv'])}; validation: " + ", ".join(f"{k} {'PASS' if v['passed'] else 'FAIL'}" for k, v in validation.items()) + f"; notes {notes}")
