#!/usr/bin/env python3
"""Certification reporting package for a nine-run difficulty campaign (owner KPI framework, docs/KPI_FRAMEWORK.md).

Usage:
  cert_package.py OUT_DIR PARTS_DIR [options]      build the package from the campaign's course folders
  cert_package.py --selftest [OUT_DIR]             synthetic status checks, then (with OUT_DIR) verify a built package

  PARTS_DIR        folder with one lanes-mode-<m>-seed-<s> sub-folder per course (the difficulty-report artifacts)
  --variety-dir D  variety_report.py's output folder (variety_summary.json: flags, seed similarity); optional
  --overrides F    evaluation_overrides.json: analyst status, interpretation and recommended action per KPI, plus findings,
                   risks and recommendations for the executive report; optional
  --evidence k=v   evidence from outside the campaign (repeatable): ci_conclusion, ci_sha, ci_run_url, fingerprint,
                   release_android, release_windows, release_linux, release_steam, release_steamdeck, ci_minutes
  --manifest-extra k=v   recorded verbatim in the manifest (repeatable)
  --expect-modes / --expect-seeds   JSON lists (default: env EXPECT_MODES / EXPECT_SEEDS, as batch_summary.py)
  --build-label    "Build 83" by default; names the PDFs and the ZIP

Writes OUT_DIR/<Upwardly_BuildNN_Certification>/ (manifest.json, data/, kpi_measurements.json/.csv, charts/, dashboard.html,
the executive and technical PDFs, TECHNICAL_REPORT.md), OUT_DIR/<name>.zip of that folder, and OUT_DIR/manifest.json (the ZIP's
SHA-256). Statuses: PASS / FAIL / WATCH / NOT VERIFIED. An automatic status is set only where the framework gives an objective rule;
a missing measurement is NOT VERIFIED and can never pass, automatically or by analyst override. Nothing here feeds the game.
"""
import argparse, csv, datetime, hashlib, json, math, os, platform, re, shutil, statistics, subprocess, sys, textwrap, zipfile
from collections import Counter, defaultdict, OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import batch_summary as BS          # noqa: E402  (shared helpers, mode colours and chart style; sets the Agg backend)
import matplotlib                   # noqa: E402
import matplotlib.pyplot as plt     # noqa: E402
from matplotlib.backends.backend_pdf import PdfPages  # noqa: E402
from matplotlib.patches import Rectangle, Patch  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402
plt.rcParams["figure.max_open_warning"] = 0
plt.rcParams["axes.axisbelow"] = True          # gridlines behind the marks
plt.rcParams["pdf.fonttype"] = 42              # TrueType in the PDFs (selectable text, clean glyph names)          # the package keeps its ~25 figures open to write them into both PDFs as vectors

MODES = BS.MODES
MODE_COL = BS.COLORS
MODE_MARK = {"NORMAL": "o", "PUNISHER": "s", "NGH": "^"}
INK, INK2, GRID, SURF = BS.INK, BS.INK2, BS.GRID, BS.SURF
MUTED = "#898781"
LANE_KEYS = ["terrain", "hazards", "enemies", "interaction", "mitigation", "total"]
LANE_FROM = {"TERRAIN": "terrain", "HAZARDS": "hazards", "ENEMIES": "enemies", "INTERACTION": "interaction", "MITIGATION": "mitigation",
             "TOTAL_REFERENCE": "total"}
LANE_COL = {"terrain": "#2a78d6", "hazards": "#eb6834", "enemies": "#1baf7a", "interaction": "#eda100", "mitigation": "#6250d6"}
STATUS_COL = {"PASS": "#0ca30c", "WATCH": "#fab219", "FAIL": "#d03b3b", "NOT VERIFIED": "#898781"}
STATUS_ICON = {"PASS": "✓", "FAIL": "✗", "WATCH": "!", "NOT VERIFIED": "?"}
STATUSES = ["PASS", "FAIL", "WATCH", "NOT VERIFIED"]
MECHANICS = ["PAD", "SPRING", "SEESAW", "RAMP", "SWING", "MOVE_H", "MOVE_V", "MOVE_Z", "ROPE", "CABLE", "CRUMBLE"]
ENEMY_TYPES = ["CRAB", "BEE", "SKULL", "BLOB", "ENFORCER"]
TIERS = [("enforcers_yellow", "Yellow", "#eda100", "//"), ("enforcers_orange", "Orange", "#eb6834", ".."), ("enforcers_red", "Red", "#e34948", "xx")]
REFUSALS = ["refused_layer", "refused_hazard", "refused_plan", "refused_window", "refused_conflict", "refused_threat"]
VERDICTS = BS.VERDICTS
NM = "not measured in this campaign"
CASTLE_M = 500.0
COURSE_TOP_M = 5000.0

# Enforcer counters the Sim records during the solver's witness climb (encounters.csv); all tiers combined: the tier is not recorded per counter.
ENF_COUNTERS = OrderedDict([
    ("enf_kick_tries", "kick attempts"), ("enf_kick_hits", "kicks that hit the climber"), ("enf_swat_tries", "swat attempts"),
    ("enf_swat_hits", "swats that hit the climber"), ("enf_retreats", "retreats (Sim counter)"), ("enf_falls", "Enforcer falls"),
    ("enf_robots_alerted", "Enforcers alerted"), ("enf_swats_considered", "swats considered"), ("enf_swats_declined", "swats declined"),
    ("enf_swats_risky", "swats judged risky"), ("enf_land_own_deck", "landings on own deck"), ("enf_land_neighbour_deck", "landings on a neighbour deck"),
    ("enf_hops", "deck-to-deck hops"), ("enf_fall_edge", "falls off an edge"), ("enf_fall_chase", "falls while chasing"), ("enf_fall_no_deck", "falls with no deck")])
AVL_COUNTERS = OrderedDict([
    ("avl_fired", "avalanches fired"), ("avl_skipped_cooldown", "skipped (cooldown)"), ("avl_blocks_released", "blocks released"),
    ("avl_blocks_near_player", "blocks near the climber"), ("avl_blocks_in_traversal_area", "blocks in the traversal area"),
    ("avl_block_knocks", "block-on-block knocks"), ("avl_hits", "hits on the climber"), ("avl_ricochets", "ricochets"),
    ("avl_peak_active", "peak active blocks (max)")])

# the 16 KPIs (docs/KPI_FRAMEWORK.md) and KPI 7's two submeasures; "objective" = the framework's rule decides the whole KPI
KPIS = OrderedDict([
    ("1", ("Run identity and reproducibility", "Does it work?", True)),
    ("2", ("Course completion and validation", "Does it work?", True)),
    ("3", ("Terrain difficulty", "Is it appropriately difficult?", False)),
    ("4", ("Hazard difficulty", "Is it appropriately difficult?", False)),
    ("5", ("Enemy difficulty", "Is it appropriately difficult?", False)),
    ("6", ("Interaction and mitigation", "Is it appropriately difficult?", False)),
    ("7", ("Difficulty density and pacing", "Is it appropriately difficult?", False)),
    ("7A", ("Density and pacing (spikes, easy stretches, consecutive hazards, distribution)", "Is it appropriately difficult?", False)),
    ("7B", ("Castle-to-castle escalation and controller observability", "Is it appropriately difficult?", False)),
    ("8", ("Gameplay variety and repetition", "Is it enjoyable and varied?", False)),
    ("9", ("Generator quality and efficiency", "Does it work?", False)),
    ("10", ("Solver accuracy and fairness", "Does it work?", False)),
    ("11", ("Avalanche performance", "Is it enjoyable and varied?", False)),
    ("12", ("Enforcer performance", "Is it enjoyable and varied?", False)),
    ("13", ("Generation and runtime performance", "Does it work?", False)),
    ("14", ("Determinism, saves and replays", "Does it work?", True)),
    ("15", ("Release and platform integrity", "Does it work?", True)),
    ("16", ("Advanced movement utilization", "Is it enjoyable and varied?", False)),
])
QUESTIONS = ["Does it work?", "Is it appropriately difficult?", "Is it enjoyable and varied?"]


# ---------------------------------------------------------------- small helpers
def fnum(v):
    """A finite float, or None for a blank, missing or non-numeric cell (never a silent zero)."""
    try:
        x = float(v)
        return x if math.isfinite(x) else None
    except (TypeError, ValueError):
        return None


def inum(v):
    x = fnum(v)
    return int(round(x)) if x is not None else None


def truthy(v): return str(v).strip().lower() in ("1", "true", "yes", "y")


def mean(v):
    v = [x for x in v if x is not None]
    return sum(v) / len(v) if v else None


def sd(v):
    v = [x for x in v if x is not None]
    return statistics.stdev(v) if len(v) > 1 else (0.0 if v else None)


def ssum(v):
    v = [x for x in v if x is not None]
    return sum(v) if v else None


def fmt(v, nd=1, pct=False, signed=False):
    if v is None or (isinstance(v, float) and not math.isfinite(v)): return "-"
    if isinstance(v, bool): return "yes" if v else "no"
    if isinstance(v, (int, float)):
        x = 100 * v if pct else v
        s = f"{x:+,.{nd}f}" if signed else f"{x:,.{nd}f}"
        if isinstance(v, int) and not pct: s = f"{v:+,d}" if signed else f"{v:,d}"
        return s + ("%" if pct else "")
    return str(v)


def entropy(counts):
    n = sum(counts)
    return -sum(c / n * math.log2(c / n) for c in counts if c > 0) if n else None


def read_csv(d, name):
    p = os.path.join(d, name)
    if not os.path.exists(p): return None, None
    with open(p, newline="") as f:
        r = csv.DictReader(f)
        rows = list(r)
        return rows, list(r.fieldnames or [])


def read_json(p):
    try:
        with open(p) as f: return json.load(f)
    except (OSError, ValueError):
        return None


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def castle_of_y(y):
    if y is None: return None
    return max(1, min(10, int(y // CASTLE_M) + 1))


def mode_rank(m): return MODES.index(m) if m in MODES else 9


def kv_list(items):
    out = OrderedDict()
    for s in items or []:
        if "=" not in s: raise SystemExit(f"expected key=value, got {s!r}")
        k, v = s.split("=", 1)
        out[k.strip()] = v.strip()
    return out


# ---------------------------------------------------------------- loading one course folder
FILES = ["gate.json", "run.json", "castles.csv", "castle_control.csv", "windows.csv", "solver.csv", "solver_failures.csv", "generation_failures.csv",
         "integrity.csv", "chunks.csv", "encounters.csv", "hazards.csv", "elements.csv", "links.csv", "modules.csv", "module_inventory.csv",
         "traversal.csv", "variety_castles.csv", "variety_run.json", "inventory.csv", "rule_checks.csv", "counts.csv", "fallbacks.csv"]
CTL_COLS = ["status", "target_total", "target_terrain", "target_hazards", "target_enemies", "target_interaction", "est_total", "deviation_from_target",
            "factor_terrain", "factor_hazards", "factor_enemies", "factor_interaction", "req_terrain", "req_hazards", "req_enemies", "req_interaction",
            "saturated", "growth_vs_prev", "cum_growth", "planned_rise", "slope_error", "slope_aware", "modules_attempted", "modules_refused",
            "modules_refused_early", "hazard_attempts", "hazard_accepted", "hazard_acceptance", "hazard_factor_requested", "hazard_factor_applied",
            "hazard_constraint", "hazard_short_of_target"]
STR_CTL = {"status", "saturated", "slope_aware", "hazard_constraint"}


def load_course(d):
    folder = os.path.basename(d.rstrip("/"))
    m = re.match(r"lanes-mode-(\d+)-seed-(.+)$", folder)
    gate = read_json(os.path.join(d, "gate.json")) or {}
    runj = read_json(os.path.join(d, "run.json")) or {}
    run = gate.get("run") or runj.get("run") or (f"{MODES[int(m.group(1))]}-s{m.group(2)}" if m and int(m.group(1)) < 3 else folder)
    mode = runj.get("mode") or (MODES[int(m.group(1))] if m and int(m.group(1)) < 3 else BS.mode_of(run))
    seed = str(runj.get("seed") if runj.get("seed") is not None else (m.group(2) if m else run.split("-s")[-1]))
    c = {"dir": d, "folder": folder, "run": run, "mode": mode, "seed": seed, "gate": gate, "runj": runj, "present": True, "t": {}, "h": {}}
    for name in FILES:
        if name.endswith(".csv"):
            rows, hdr = read_csv(d, name)
            if rows is not None: c["t"][name], c["h"][name] = rows, hdr
    c["vrun"] = read_json(os.path.join(d, "variety_run.json"))
    c["files"] = sorted(f for f in os.listdir(d) if os.path.isfile(os.path.join(d, f))) if os.path.isdir(d) else []
    c["bs"] = BS.course(d)          # batch_summary's own reading: verdict tally, causes, witness setbacks, heap (reused, not re-derived)
    derive(c)
    return c


def missing_course(run, mode, seed):
    c = {"dir": None, "folder": None, "run": run, "mode": mode, "seed": str(seed), "gate": {}, "runj": {}, "present": False, "t": {}, "h": {},
         "vrun": None, "files": [], "bs": None}
    derive(c)
    return c


def has(c, name, col=None):
    return name in c["h"] and (col is None or col in c["h"][name])


def derive(c):
    """Per castle records, per slice records and the course summary, from the course's own files only."""
    T = c["t"]
    K = {k: {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k} for k in range(1, 11)}
    seen = set()
    for r in T.get("castles.csv", []):
        k = inum(r.get("castle"))
        if not k or k not in K: continue
        seen.add(k)
        lane = LANE_FROM.get(r.get("lane"))
        if not lane: continue
        rec = K[k]
        rec[lane] = fnum(r.get("adj_sum"))
        if lane == "total":
            rec.update(windows=inum(r.get("windows")), win_mean=fnum(r.get("mean")), win_median=fnum(r.get("median")), win_min=fnum(r.get("min")),
                       win_max=fnum(r.get("max")), win_sd=fnum(r.get("sd")), win_q1=fnum(r.get("q1")), win_q3=fnum(r.get("q3")),
                       spike_windows=inum(r.get("high_spike_windows")), solver_links_failed=inum(r.get("solver_links_failed")),
                       solver_links_unreached=inum(r.get("solver_links_unreached")), lane_source="castles.csv")
        if lane in ("hazards", "enemies"): rec[f"no_{lane}_windows"] = inum(r.get("low_gap_windows"))
    ctl_have = "castle_control.csv" in T
    for r in T.get("castle_control.csv", []):
        k = inum(r.get("castle"))
        if not k or k not in K: continue
        rec = K[k]
        for col in CTL_COLS:
            if col in r: rec["ctl_" + col] = (r.get(col) or "") if col in STR_CTL else fnum(r.get(col))
        for lane in ("terrain", "hazards", "enemies", "interaction", "mitigation", "total"):
            if rec.get(lane) is None and fnum(r.get("lane_" + lane)) is not None:
                rec[lane] = fnum(r.get("lane_" + lane)); rec["lane_source"] = "castle_control.csv"
        seen.add(k)
    for k, rec in K.items():
        if ctl_have and rec.get("ctl_status") is not None: rec["complete"] = rec["ctl_status"] == "complete"
        else: rec["complete"] = (rec.get("windows") or 0) >= 20
        rec["complete_source"] = "castle_control.csv status" if ctl_have and rec.get("ctl_status") is not None else "castles.csv windows (20 per castle)"
    # hazards and enemies placed (hazards.csv: one row per placed hazard or enemy element)
    htypes = defaultdict(lambda: [0, 0])          # (castle, type, lane) -> placed, on route
    avl_events = []
    for r in T.get("hazards.csv", []):
        k = inum(r.get("castle")); lane = r.get("lane", "")
        if not k or k not in K: continue
        on = truthy(r.get("on_route"))
        htypes[(k, r.get("type", ""), lane)][0] += 1
        htypes[(k, r.get("type", ""), lane)][1] += 1 if on else 0
        if lane in ("HAZARDS", "ENEMIES"):
            key = "hazards" if lane == "HAZARDS" else "enemies"
            K[k][key + "_placed"] = K[k].get(key + "_placed", 0) + 1
            K[k][key + "_on_route"] = K[k].get(key + "_on_route", 0) + (1 if on else 0)
        if r.get("type") == "AVALANCHE":
            avl_events.append({"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k, "y": fnum(r.get("y")), "blocks": inum(r.get("len")),
                               "status": r.get("status", ""), "on_route": on})
        if r.get("type") == "ENFORCER": K[k]["enforcer_elements"] = K[k].get("enforcer_elements", 0) + 1
    if "hazards.csv" in T:
        for k in K:
            for key in ("hazards_placed", "hazards_on_route", "enemies_placed", "enemies_on_route", "enforcer_elements"): K[k].setdefault(key, 0)
    c["hazard_types"] = [{"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k, "type": t, "lane": lane, "placed": v[0], "on_route": v[1]}
                         for (k, t, lane), v in sorted(htypes.items())]
    c["avalanche_events"] = avl_events
    # encounters per castle: every numeric counter summed (Enforcer tiers, avalanches, the Sim's witness counters); peak_active as a max
    enc_cols = [h for h in c["h"].get("encounters.csv", []) if h not in ("run", "slice", "y0", "castle", "solved")]
    for r in T.get("encounters.csv", []):
        k = inum(r.get("castle"))
        if not k or k not in K: continue
        for col in enc_cols:
            v = fnum(r.get(col))
            if v is None: continue
            if col == "avl_peak_active": K[k][col] = max(K[k].get(col) or 0, v)
            else: K[k][col] = (K[k].get(col) or 0) + v
    if "encounters.csv" in T:
        for k in K:
            for col in enc_cols: K[k].setdefault(col, 0.0)
            K[k]["enforcers"] = sum(K[k].get(t[0]) or 0 for t in TIERS)
    # mechanics (KPI 16): placed elements, on the main route, route links using them, links the solver's witness traversed
    mech = defaultdict(lambda: {"placed": 0, "main_route": 0, "route_links": 0, "solver_traversed": 0})
    for r in T.get("elements.csv", []):
        t = r.get("type")
        if t not in MECHANICS: continue
        k = inum(r.get("castle"))
        if not k or k not in K: continue
        mech[(k, t)]["placed"] += 1
        if truthy(r.get("route")): mech[(k, t)]["main_route"] += 1
    links = T.get("links.csv", [])
    run_h, best_h = 0, defaultdict(int)
    lk = defaultdict(lambda: {"links": 0, "mech_links": 0, "traversed": 0, "failed": 0, "unreached": 0, "gap_max": None, "gap_over_09": 0, "gap_over_1": 0,
                              "land_min": None, "worst_gap_link": None})
    for r in links:
        k = castle_of_y(fnum(r.get("y0")))
        if k is None: continue
        st = r.get("solver_status", "")
        types = {r.get("from_type"), r.get("to_type")}
        L = lk[k]; L["links"] += 1
        if types - {"STATIC", None, ""}: L["mech_links"] += 1
        if st.startswith("TRAVERSED"): L["traversed"] += 1
        elif st == "FAILED": L["failed"] += 1
        elif st == "UNREACHED": L["unreached"] += 1
        g = fnum(r.get("gap_ratio")); w = fnum(r.get("landing_width"))
        if g is not None:
            if L["gap_max"] is None or g > L["gap_max"]: L["gap_max"], L["worst_gap_link"] = g, f"slice {r.get('slice')} {r.get('from')}->{r.get('to')}"
            L["gap_over_09"] += g >= 0.9; L["gap_over_1"] += g > 1.0
        if w is not None: L["land_min"] = w if L["land_min"] is None else min(L["land_min"], w)
        for t in types & set(MECHANICS):
            mech[(k, t)]["route_links"] += 1
            if st.startswith("TRAVERSED"): mech[(k, t)]["solver_traversed"] += 1
        hz = (r.get("hazards") or "").strip("[] ")
        run_h = run_h + 1 if hz else 0
        best_h[k] = max(best_h[k], run_h)
    if links:
        for k in K:
            L = lk[k]
            K[k].update(route_links=L["links"], mechanic_links=L["mech_links"], links_traversed=L["traversed"], links_failed=L["failed"],
                        links_unreached=L["unreached"], gap_ratio_max=L["gap_max"], links_gap_ratio_ge_0_9=L["gap_over_09"],
                        links_gap_ratio_gt_1=L["gap_over_1"], landing_width_min=L["land_min"], worst_gap_link=L["worst_gap_link"],
                        longest_hazard_link_run=best_h[k])
    c["mechanics"] = [{"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k, "mechanic": t, **mech[(k, t)]}
                      for k in range(1, 11) for t in MECHANICS] if ("elements.csv" in T or links) else []
    # windows: zero-hazard stretches, the hardest window
    W = T.get("windows.csv", [])
    c["windows"] = []
    zero_run, zero_best = 0, defaultdict(int)
    for r in W:
        k = inum(r.get("castle"))
        if not k or k not in K: continue
        rec = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k, "window": inum(r.get("window")), "y0": fnum(r.get("y0")),
               "terrain": fnum(r.get("terrain_adj")), "hazards": fnum(r.get("hazard_adj")), "enemies": fnum(r.get("enemy_adj")),
               "interaction": fnum(r.get("interaction")), "mitigation": fnum(r.get("mitigation")), "total": fnum(r.get("total_reference")),
               "links": inum(r.get("links")), "demanding_links": inum(r.get("demanding_links")), "recovery_platforms": inum(r.get("recovery_platforms")),
               "unreachable_enemies": inum(r.get("unreachable_enemies")), "flags": r.get("flags", "")}
        c["windows"].append(rec)
        zero_run = zero_run + 1 if (rec["hazards"] or 0) == 0 else 0
        zero_best[k] = max(zero_best[k], zero_run)
        for col in ("demanding_links", "recovery_platforms", "unreachable_enemies"):
            if rec[col] is not None: K[k][col] = (K[k].get(col) or 0) + rec[col]
        if rec["total"] is not None and (K[k].get("hardest_window") is None or rec["total"] > K[k]["hardest_window"]):
            K[k]["hardest_window"], K[k]["hardest_window_index"] = rec["total"], rec["window"]
    if W:
        for k in K: K[k]["longest_zero_hazard_windows"] = zero_best[k]
    # traversal and variety per castle
    for r in T.get("traversal.csv", []):
        k = inum(r.get("castle"))
        if k in K:
            for col in ("challenge_density", "rope_modules", "max_consecutive_rope_modules", "max_consecutive_ordinary_links", "longest_unchallenged_climb_m",
                        "rope_climb_m", "unchallenged_horizontal_m", "fallbacks_accepted"):
                K[k][col] = fnum(r.get(col))
    for r in T.get("variety_castles.csv", []):
        k = inum(r.get("castle"))
        if k in K:
            for col in ("unique_mechanics", "terrain_families", "hazard_families", "enemy_families", "terrain_entropy", "hazard_entropy", "enemy_entropy",
                        "signature_entropy", "mean_variety_score", "longest_signature_run", "longest_terrain_run", "repeated_trigrams", "jaccard_vs_previous_castle"):
                K[k][col] = fnum(r.get(col))
    # slices: chunks.csv (generation) beside solver.csv (the witness)
    sol = {inum(r.get("slice")): r for r in T.get("solver.csv", [])}
    c["slices"] = []
    for r in T.get("chunks.csv", []):
        s = inum(r.get("slice")); k = inum(r.get("castle"))
        sv = sol.get(s, {})
        rec = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "slice": s, "castle": k, "y0": fnum(r.get("y0")), "y1": fnum(r.get("y1")),
               "gen_wall_s": fnum(r.get("gen_wall_s")), "solve_wall_s": fnum(r.get("solve_wall_s")), "slice_attempts": inum(r.get("slice_attempts")),
               "failed_attempts": inum(r.get("failed_attempts")), "failed_attempts_s": fnum(r.get("failed_attempts_s")), "fallback_s": fnum(r.get("fallback_s")),
               "modules_accepted": inum(r.get("modules_accepted")), "modules_refused_or_discarded": inum(r.get("modules_refused_or_discarded")),
               "relaxed": truthy(r.get("relaxed")), "heap_used_mb": (fnum(r.get("heap_used_bytes")) or 0) / 1e6 if r.get("heap_used_bytes") else None,
               "gen_alloc_mb": (fnum(r.get("gen_alloc_bytes")) or 0) / 1e6 if r.get("gen_alloc_bytes") else None,
               "completed": sv.get("completed"), "verdict": sv.get("verdict"), "cause": sv.get("cause"), "fail_class": sv.get("fail_class"),
               "min_margin": fnum(sv.get("min_margin")), "sim_time_s": fnum(sv.get("sim_time_s"))}
        for col in ("fallback_moves", "resyncs", "waits", "unclear_moves", "witness_setbacks"):
            rec[col] = inum(sv.get(col)) if col in sv else None
        for col in c["h"].get("solver.csv", []):
            if "assist" in col.lower(): rec[col] = inum(sv.get(col))
        c["slices"].append(rec)
        if k in K:
            for col in ("gen_wall_s", "solve_wall_s", "failed_attempts", "failed_attempts_s"):
                if rec[col] is not None: K[k][col] = (K[k].get(col) or 0) + rec[col]
            K[k]["slices"] = K[k].get("slices", 0) + 1
    # modules: per kind (module_inventory: tried / accepted in course / refused / fallback) and refusal reasons and wall time (modules.csv)
    mods = OrderedDict()
    for r in T.get("module_inventory.csv", []):
        mods[r.get("kind")] = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "kind": r.get("kind"), "purpose": r.get("purpose", ""),
                               "tried": inum(r.get("tried")), "accepted_in_course": inum(r.get("accepted_in_course")), "refused": inum(r.get("refused")),
                               "accepted_as_fallback": inum(r.get("accepted_as_fallback")), "inventory_status": r.get("status", "")}
    early_col = has(c, "modules.csv", "refused_early")
    for r in T.get("modules.csv", []):
        kd = r.get("kind")
        m = mods.setdefault(kd, {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "kind": kd, "purpose": "", "tried": None,
                                 "accepted_in_course": None, "refused": None, "accepted_as_fallback": None, "inventory_status": ""})
        m["attempt_rows"] = m.get("attempt_rows", 0) + 1
        m["wall_ms"] = (m.get("wall_ms") or 0) + (fnum(r.get("wall_ms")) or 0)
        if not truthy(r.get("accepted")):
            for col in REFUSALS: m[col] = (m.get(col) or 0) + (inum(r.get(col)) or 0)
        m["refused_early"] = ((m.get("refused_early") or 0) + (1 if truthy(r.get("refused_early")) else 0)) if early_col else None
        k = castle_of_y(fnum(r.get("y")))
        if k in K:
            K[k]["module_attempts"] = K[k].get("module_attempts", 0) + 1
            K[k]["modules_accepted_final"] = K[k].get("modules_accepted_final", 0) + (1 if truthy(r.get("accepted")) and truthy(r.get("final_attempt")) else 0)
    c["modules"] = list(mods.values())
    # growth from the measured lane totals (castle k against castle k-1), both castles complete
    for k in range(2, 11):
        a, b = K[k - 1], K[k]
        if a.get("complete") and b.get("complete") and a.get("total") and b.get("total") is not None:
            b["growth_pct"] = (b["total"] - a["total"]) / a["total"]
            b["step_points"] = b["total"] - a["total"]
    c["castles"] = K
    # the course summary
    S = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "folder": c["folder"], "present": c["present"]}
    g, rj = c["gate"], c["runj"]
    S.update(gate_passes=g.get("passes") if g else None, gate_generation=g.get("generation"), gate_solver=g.get("solver"),
             gate_integrity=g.get("geometric_integrity"), gate_execution=g.get("report_execution"),
             reached_m=fnum(rj.get("reached_m")), top_m=fnum(rj.get("top_m")), slices=rj.get("slices") if rj else (len(c["slices"]) or None),
             stopped=rj.get("stopped"), rules_version=rj.get("generator_rules_version"), commit=rj.get("commit"), branch=rj.get("branch"),
             actions_run_id=rj.get("actions_run_id"), scoring_model=rj.get("scoring_model"),
             fingerprint=next((str(v) for kk, v in list(rj.items()) + list(g.items()) if "fingerprint" in kk.lower()), None),
             tuning_hash=hashlib.sha256(json.dumps(rj.get("mode_parameters"), sort_keys=True).encode()).hexdigest()[:16] if rj.get("mode_parameters") else None,
             weights_hash=hashlib.sha256(json.dumps(rj.get("weights"), sort_keys=True).encode()).hexdigest()[:16] if rj.get("weights") else None,
             gen_s=fnum(rj.get("generation_seconds")), solve_s=fnum(rj.get("solver_seconds")))
    S["castles_complete"] = sum(1 for k in K if K[k].get("complete")) if c["present"] else 0
    S["s_per_m"] = S["gen_s"] / S["reached_m"] if S["gen_s"] and S["reached_m"] else None
    S["s_per_slice"] = S["gen_s"] / S["slices"] if S["gen_s"] and S["slices"] else None
    for lane in LANE_KEYS: S[lane] = ssum(K[k].get(lane) for k in K if K[k].get("complete")) if c["present"] else None
    t1, t10 = K[1].get("total") if K[1].get("complete") else None, K[10].get("total") if K[10].get("complete") else None
    S["growth_c1_c10"] = (t10 - t1) / t1 if t1 and t10 is not None else None
    steps = [K[k].get("growth_pct") for k in range(2, 11) if K[k].get("growth_pct") is not None]
    S["dips"] = sum(1 for x in steps if x < 0) if steps else None
    S["worst_step"] = min(steps) if steps else None
    S["step_sd_points"] = sd([K[k].get("step_points") for k in range(2, 11)]) if steps else None
    sv = T.get("solver.csv")
    S["solver_slices"] = len(sv) if sv is not None else None
    S["verdict_column"] = has(c, "solver.csv", "verdict")
    S["verdicts"] = dict(Counter(r.get("verdict") or "NONE" for r in sv)) if sv is not None and S["verdict_column"] else None
    S["proven"] = S["verdicts"].get("PROVEN_SOLVABLE", 0) if S["verdicts"] is not None else None
    S["completed_false"] = sum(1 for r in sv if not truthy(r.get("completed"))) if sv is not None else None
    S["fail_classes"] = dict(Counter(r.get("fail_class", "") for r in sv)) if sv is not None and has(c, "solver.csv", "fail_class") else None
    S["solver_failure_rows"] = len(T["solver_failures.csv"]) if "solver_failures.csv" in T else None
    S["integrity_rows"] = len(T["integrity.csv"]) if "integrity.csv" in T else None
    S["generation_failure_records"] = len(T["generation_failures.csv"]) if "generation_failures.csv" in T else None
    S["links_failed"] = sum(K[k].get("links_failed", 0) for k in K) if links else None
    S["links_unreached"] = sum(K[k].get("links_unreached", 0) for k in K) if links else None
    for col in ("fallback_moves", "resyncs", "waits", "unclear_moves", "witness_setbacks"):
        S[col] = sum(r.get(col) or 0 for r in c["slices"]) if has(c, "solver.csv", col) else None
    S["assist_columns"] = [h for h in c["h"].get("solver.csv", []) if "assist" in h.lower()]
    S["peak_heap_mb"] = max((r["heap_used_mb"] for r in c["slices"] if r["heap_used_mb"] is not None), default=None)
    S["max_gen_alloc_mb"] = max((r["gen_alloc_mb"] for r in c["slices"] if r["gen_alloc_mb"] is not None), default=None)
    S["failed_attempts"] = ssum(r["failed_attempts"] for r in c["slices"])
    S["failed_attempts_s"] = ssum(r["failed_attempts_s"] for r in c["slices"])
    S["relaxed_slices"] = sum(1 for r in c["slices"] if r["relaxed"]) if c["slices"] else None
    for col in ["enforcers_yellow", "enforcers_orange", "enforcers_red", "enforcers", "avalanches", "avalanche_blocks", "avalanche_unfair", "avalanche_dropped",
                "enforcer_unfair", "enforcer_dropped", "enforcers_planned", "avalanches_planned", "setbacks"] + list(ENF_COUNTERS) + list(AVL_COUNTERS):
        vals = [K[k].get(col) for k in K]
        S[col] = (max(v for v in vals if v is not None) if col == "avl_peak_active" else ssum(vals)) if any(v is not None for v in vals) else None
    for key in ("hazards_placed", "hazards_on_route", "enemies_placed", "enemies_on_route", "enforcer_elements"):
        S[key] = ssum(K[k].get(key) for k in K) if "hazards.csv" in T else None
    S["avalanche_events"] = len(avl_events) if "hazards.csv" in T else None
    S["modules_tried"] = ssum(m["tried"] for m in c["modules"]); S["modules_accepted"] = ssum(m["accepted_in_course"] for m in c["modules"])
    S["modules_refused"] = ssum(m["refused"] for m in c["modules"]); S["modules_fallback"] = ssum(m["accepted_as_fallback"] for m in c["modules"])
    S["modules_refused_early"] = ssum(m.get("refused_early") for m in c["modules"]) if early_col else None
    S["rule_checks"] = dict(Counter(r.get("rule", "") for r in T["rule_checks.csv"])) if "rule_checks.csv" in T else None
    S["missing_files"] = [f for f in FILES if f not in c["files"]] if c["present"] else FILES[:]
    c["summary"] = S


# ---------------------------------------------------------------- campaign model
def build_model(parts, expect_modes, expect_seeds, variety_dir):
    dirs = sorted(os.path.join(parts, d) for d in os.listdir(parts) if os.path.isdir(os.path.join(parts, d)) and
                  (d.startswith("lanes-mode-") or os.path.exists(os.path.join(parts, d, "gate.json")) or os.path.exists(os.path.join(parts, d, "run.json")))) \
        if parts and os.path.isdir(parts) else []
    courses = [load_course(d) for d in dirs]
    expected = []
    if expect_modes is not None and expect_seeds is not None:
        expected = [(MODES[int(m)], str(s)) for m in expect_modes for s in expect_seeds]
        have = {(c["mode"], c["seed"]) for c in courses}
        for (m, s) in expected:
            if (m, s) not in have: courses.append(missing_course(f"{m}-s{s}", m, s))
    courses.sort(key=lambda c: (mode_rank(c["mode"]), c["seed"], c["run"]))
    vsum = read_json(os.path.join(variety_dir, "variety_summary.json")) if variety_dir else None
    M = {"courses": courses, "expected": expected, "variety_summary": vsum, "parts": parts}
    # per mode and castle: mean / sd / min / max over the courses that completed that castle
    agg = []
    fields = LANE_KEYS + ["growth_pct", "hazards_placed", "enemies_placed", "avalanches", "avalanche_blocks", "enforcers", "enforcers_yellow", "enforcers_orange",
                          "enforcers_red", "gen_wall_s", "solve_wall_s", "unique_mechanics", "signature_entropy", "mean_variety_score", "longest_signature_run",
                          "win_sd", "spike_windows", "longest_unchallenged_climb_m", "mechanic_links", "route_links", "ctl_target_total", "ctl_est_total",
                          "ctl_slope_error", "ctl_planned_rise"]
    for m in MODES:
        mc = [c for c in courses if c["mode"] == m and c["present"]]
        if not mc: continue
        for k in range(1, 11):
            recs = [c["castles"][k] for c in mc if c["castles"][k].get("complete")]
            row = {"mode": m, "castle": k, "courses": len(recs)}
            for f in fields:
                v = [r.get(f) for r in recs if r.get(f) is not None]
                row[f + "_mean"] = mean(v) if v else None
                row[f + "_sd"] = sd(v) if v else None
                row[f + "_min"] = min(v) if v else None
                row[f + "_max"] = max(v) if v else None
                row[f + "_n"] = len(v)
            agg.append(row)
    M["mode_castles"] = agg
    M["curve"] = {m: {r["castle"]: r["total_mean"] for r in agg if r["mode"] == m and r["total_mean"] is not None} for m in MODES}
    return M


def mc_get(M, mode, castle, field):
    for r in M["mode_castles"]:
        if r["mode"] == mode and r["castle"] == castle: return r.get(field)
    return None


def present(M): return [c for c in M["courses"] if c["present"]]


def by_mode(M, mode): return [c for c in M["courses"] if c["mode"] == mode and c["present"]]


def modes_found(M): return [m for m in MODES if by_mode(M, m)]


def all_have(M, name, col=None):
    cs = present(M)
    return bool(cs) and all(has(c, name, col) for c in cs)


def any_have(M, name, col=None): return any(has(c, name, col) for c in present(M))


def per_mode(M, fn):
    """{mode: fn(course list)} for the modes found."""
    return OrderedDict((m, fn(by_mode(M, m))) for m in modes_found(M))


def course_mean(M, key):
    return per_mode(M, lambda cs: mean([c["summary"].get(key) for c in cs]))


def worst_course(M, key, lowest=True, modes=None):
    best = None
    for c in present(M):
        if modes and c["mode"] not in modes: continue
        v = c["summary"].get(key)
        if v is None: continue
        if best is None or (v < best["value"] if lowest else v > best["value"]):
            best = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": None, "value": v}
    return best


def worst_castle(M, key, lowest=False, modes=None):
    best = None
    for c in present(M):
        if modes and c["mode"] not in modes: continue
        for k, r in c["castles"].items():
            v = r.get(key)
            if v is None or not r.get("complete"): continue
            if best is None or (v < best["value"] if lowest else v > best["value"]):
                best = {"run": c["run"], "mode": c["mode"], "seed": c["seed"], "castle": k, "value": v}
    return best


# ---------------------------------------------------------------- KPI evaluation
class KPI:
    def __init__(self, kid):
        name, q, objective = KPIS[kid]
        self.d = OrderedDict(id=kid, name=name, question=q, fully_objective=objective, automatic_status=None, status=None, status_source=None,
                             checks=[], measurements=[], missing_inputs=[], interpretation="", recommended_action="", analyst=None, notes=[])

    def m(self, name, value, unit="", file=None, columns=None, worst=None, note="", required=True):
        """A measurement; value None (or an all-None per-mode dict) records 'not measured in this campaign'. A required one that is missing
        is listed in missing_inputs (the KPI can then never pass); an informational one (required=False) is only reported."""
        empty = value is None or (isinstance(value, dict) and (not value or all(v is None for v in value.values())))
        self.d["measurements"].append(OrderedDict(name=name, value=None if empty else value, unit=unit, measured=not empty,
                                                  evidence={"file": file, "columns": columns or []}, worst_case=worst, note=note if not empty else (note or NM),
                                                  required=required))
        if empty and required: self.d["missing_inputs"].append(f"{name}: {NM}" + (f" ({file}{': ' + ', '.join(columns) if columns else ''})" if file else ""))

    def check(self, name, rule, status, measured, file=None, columns=None, worst=None):
        self.d["checks"].append(OrderedDict(name=name, rule=rule, status=status, measured=measured, evidence={"file": file, "columns": columns or []},
                                            worst_case=worst))
        if status == "NOT VERIFIED": self.d["missing_inputs"].append(f"check '{name}': {measured}")

    def finish(self):
        cs = [c["status"] for c in self.d["checks"]]
        if "FAIL" in cs: s = "FAIL"
        elif self.d["fully_objective"] and cs and all(x == "PASS" for x in cs) and not self.d["missing_inputs"]: s = "PASS"
        else: s = "NOT VERIFIED"
        self.d["automatic_status"] = s
        self.d["automatic_rule"] = ("objective framework rule decides the whole KPI" if self.d["fully_objective"] else
                                    "objective checks can only establish a FAIL; the rest needs analyst evaluation")
        return self.d


def pm(M, fn):
    v = per_mode(M, fn)
    return v if any(x is not None for x in v.values()) else None


def eval_kpis(M, ev, candidate_sha):
    cs = present(M)
    out = OrderedDict()
    nexp = len(M["expected"])

    # KPI 1 ----------------------------------------------------------------------------------------------------------
    k = KPI("1")
    commits = sorted({str(c["summary"]["commit"]) for c in cs if c["summary"]["commit"] is not None})
    real = [x for x in commits if x not in ("local", "")]
    rules = sorted({str(c["summary"]["rules_version"]) for c in cs if c["summary"]["rules_version"] is not None})
    fps = sorted({c["summary"]["fingerprint"] for c in cs if c["summary"]["fingerprint"]})
    k.m("candidate commit (package)", candidate_sha, file="env GITHUB_SHA / git rev-parse HEAD")
    k.m("commits recorded by the courses", commits or None, file="run.json", columns=["commit"])
    k.m("generator rules versions", rules or None, file="run.json", columns=["generator_rules_version"])
    k.m("generator fingerprint", fps or ([ev["fingerprint"]] if ev.get("fingerprint") else None), file="run.json / gate.json (any *fingerprint* key) or --evidence fingerprint",
        note="" if fps else ("supplied as evidence, not recorded by the courses" if ev.get("fingerprint") else ""))
    k.m("tuning (mode_parameters) hash per mode", pm(M, lambda x: sorted({c["summary"]["tuning_hash"] for c in x if c["summary"]["tuning_hash"]}) or None),
        file="run.json", columns=["mode_parameters"])
    k.m("CI run ids recorded by the courses", sorted({str(c["summary"]["actions_run_id"]) for c in cs if c["summary"]["actions_run_id"]}) or None,
        file="run.json", columns=["actions_run_id"], required=False)
    k.m("courses found / expected", f"{len(cs)} / {nexp if nexp else 'not supplied'}", file="course folders, EXPECT_MODES / EXPECT_SEEDS")
    if not real: k.check("one recorded commit", "every course records the same real commit", "NOT VERIFIED", "commit not recorded (" + (", ".join(commits) or "none") + ")", "run.json", ["commit"])
    elif len(real) > 1 or len(real) < len(commits): k.check("one recorded commit", "every course records the same real commit", "FAIL", "mixed: " + ", ".join(commits), "run.json", ["commit"])
    else: k.check("one recorded commit", "every course records the same real commit", "PASS", real[0], "run.json", ["commit"])
    if real and candidate_sha and len(real) == 1:
        ok = real[0].startswith(candidate_sha[:7]) or candidate_sha.startswith(real[0][:7])
        k.check("courses built from the candidate", "recorded commit = candidate commit", "PASS" if ok else "FAIL", f"{real[0][:12]} vs {candidate_sha[:12]}", "run.json", ["commit"])
    else: k.check("courses built from the candidate", "recorded commit = candidate commit", "NOT VERIFIED", "candidate or course commit unknown", "run.json", ["commit"])
    if not rules: k.check("one rules version", "every course records the same generator_rules_version", "NOT VERIFIED", "not recorded", "run.json", ["generator_rules_version"])
    else: k.check("one rules version", "every course records the same generator_rules_version", "PASS" if len(rules) == 1 else "FAIL", ", ".join(rules), "run.json", ["generator_rules_version"])
    fp_all = fps or ([ev["fingerprint"]] if ev.get("fingerprint") else [])
    k.check("fingerprint recorded", "one generator fingerprint for the candidate", "PASS" if len(fp_all) == 1 else ("FAIL" if len(fp_all) > 1 else "NOT VERIFIED"),
            ", ".join(fp_all) or "no fingerprint in run.json/gate.json and none supplied (--evidence fingerprint=...)", "run.json")
    k.check("standard sample", "9 full-height simulations, 3 seeds per difficulty", *sample_check(M))
    k.check("reproducibility", "full CI (determinism, replays) passes on the candidate commit", *ci_check(ev, candidate_sha))
    out["1"] = k.finish()

    # KPI 2 ----------------------------------------------------------------------------------------------------------
    k = KPI("2")
    missing = [c["run"] for c in M["courses"] if not c["present"]]
    k.m("courses present", f"{len(cs)} of {len(M['courses'])}" + (f" (missing: {', '.join(missing)})" if missing else ""), file="course folders")
    k.m("height reached per course, m", {c["run"]: c["summary"]["reached_m"] for c in cs}, "m", "run.json", ["reached_m"], worst_course(M, "reached_m", True))
    k.m("castles complete per course", {c["run"]: c["summary"]["castles_complete"] for c in cs}, "castles", "castle_control.csv status / castles.csv windows",
        worst=worst_course(M, "castles_complete", True))
    vc = all_have(M, "solver.csv", "verdict")
    tally = Counter()
    for c in cs:
        for v, n in (c["summary"]["verdicts"] or {}).items(): tally[v] += n
    k.m("solver verdicts (all slices)", dict(tally) if vc else None, "slices", "solver.csv", ["verdict"])
    k.m("solver 'completed' column (legacy, not a proof verdict)", {c["run"]: f"{c['summary']['solver_slices'] - (c['summary']['completed_false'] or 0)}/{c['summary']['solver_slices']}"
                                                                  for c in cs if c["summary"]["solver_slices"] is not None} or None, "slices", "solver.csv", ["completed"], required=False)
    k.m("gate generation verdicts", {c["run"]: c["summary"]["gate_generation"] for c in cs}, file="gate.json", columns=["generation"])
    k.m("integrity violations", ssum(c["summary"]["integrity_rows"] for c in cs), "rows", "integrity.csv; gate.json geometric_integrity")
    k.m("failed slice attempts recorded (retried; see KPI 9)", ssum(c["summary"]["generation_failure_records"] for c in cs), "records", "generation_failures.csv", required=False)
    # checks
    if missing: k.check("all expected courses present", "9 of 9 (the expected panel)", "NOT VERIFIED", "missing: " + ", ".join(missing), "course folders")
    elif not cs: k.check("all expected courses present", "9 of 9", "NOT VERIFIED", "no course folders", "course folders")
    else: k.check("all expected courses present", "every expected course present", "PASS" if nexp else "NOT VERIFIED",
                  f"{len(cs)} present" + ("" if nexp else " (expected panel not supplied)"), "course folders")
    k.check("standard sample", "9 full-height simulations, 3 seeds per difficulty", *sample_check(M))
    short = [c["run"] for c in cs if (c["summary"]["reached_m"] or 0) < COURSE_TOP_M - 1e-6]
    unknown_h = [c["run"] for c in cs if c["summary"]["reached_m"] is None]
    k.check("5,000 m", "reached_m >= 5,000 in every course", "FAIL" if [x for x in short if x not in unknown_h] else ("NOT VERIFIED" if unknown_h or not cs else "PASS"),
            ("short: " + ", ".join(short)) if short else "all reached", "run.json", ["reached_m"], worst_course(M, "reached_m", True))
    c10 = [c["run"] for c in cs if c["summary"]["castles_complete"] < 10]
    k.check("10 castles", "10 complete castles in every course", "FAIL" if c10 else ("PASS" if cs else "NOT VERIFIED"), ("incomplete: " + ", ".join(c10)) if c10 else "10/10 everywhere",
            "castle_control.csv / castles.csv")
    k.check("slices proven", "100% of tested slices PROVEN_SOLVABLE", *proven_check(M))
    gfail = [c["run"] for c in cs if c["summary"]["gate_generation"] not in (None, "COMPLETE")]
    gmiss = [c["run"] for c in cs if c["summary"]["gate_generation"] is None]
    k.check("generation failures", "0 (gate generation COMPLETE in every course)", "FAIL" if gfail else ("NOT VERIFIED" if gmiss or not cs else "PASS"),
            ("not complete: " + ", ".join(gfail)) if gfail else (("gate missing: " + ", ".join(gmiss)) if gmiss else "0"), "gate.json", ["generation"])
    ifail = [c["run"] for c in cs if (c["summary"]["integrity_rows"] or 0) > 0 or (c["summary"]["gate_integrity"] and not str(c["summary"]["gate_integrity"]).startswith("CLEAN"))]
    imiss = [c["run"] for c in cs if c["summary"]["integrity_rows"] is None and c["summary"]["gate_integrity"] is None]
    k.check("integrity violations", "0 (integrity.csv empty, gate CLEAN)", "FAIL" if ifail else ("NOT VERIFIED" if imiss or not cs else "PASS"),
            ("violations: " + ", ".join(ifail)) if ifail else (("not recorded: " + ", ".join(imiss)) if imiss else "0"), "integrity.csv, gate.json", ["geometric_integrity"])
    gp = [c["run"] for c in cs if c["summary"]["gate_passes"] is False]
    k.check("validation gate", "gate.json passes in every course", "FAIL" if gp else ("NOT VERIFIED" if any(c["summary"]["gate_passes"] is None for c in cs) or not cs else "PASS"),
            ("failed: " + ", ".join(gp)) if gp else "all pass", "gate.json", ["passes"])
    out["2"] = k.finish()

    # KPI 3 terrain --------------------------------------------------------------------------------------------------
    k = KPI("3")
    lane_kpi(k, M, "terrain", "castles.csv TERRAIN adj_sum")
    k.m("largest gap/reach ratio on the route", pm(M, lambda x: max((r.get("gap_ratio_max") or 0 for c in x for r in c["castles"].values()), default=None) if any(has(c, "links.csv") for c in x) else None),
        "ratio", "links.csv", ["gap_ratio"], worst_castle(M, "gap_ratio_max"))
    k.m("route links with gap/reach >= 0.9, per course", pm(M, lambda x: mean([ssum(r.get("links_gap_ratio_ge_0_9") for r in c["castles"].values()) for c in x])), "links", "links.csv", ["gap_ratio"])
    k.m("route links with gap/reach > 1.0 (beyond plain reach), per course", pm(M, lambda x: mean([ssum(r.get("links_gap_ratio_gt_1") for r in c["castles"].values()) for c in x])), "links", "links.csv", ["gap_ratio"])
    k.m("narrowest landing", pm(M, lambda x: min((r["landing_width_min"] for c in x for r in c["castles"].values() if r.get("landing_width_min") is not None), default=None)),
        "m", "links.csv", ["landing_width"], worst_castle(M, "landing_width_min", lowest=True))
    k.m("demanding links per course", pm(M, lambda x: mean([ssum(r.get("demanding_links") for r in c["castles"].values()) for c in x])), "links", "windows.csv", ["demanding_links"])
    k.m("share of route links using a mechanic", pm(M, lambda x: share(x, "mechanic_links", "route_links")), "%", "links.csv", ["from_type", "to_type"])
    k.m("recovery platforms per course", pm(M, lambda x: mean([ssum(r.get("recovery_platforms") for r in c["castles"].values()) for c in x])), "platforms", "windows.csv", ["recovery_platforms"])
    out["3"] = k.finish()

    # KPI 4 hazards --------------------------------------------------------------------------------------------------
    k = KPI("4")
    lane_kpi(k, M, "hazards", "castles.csv HAZARDS adj_sum")
    fam = per_mode_types(M, "HAZARDS")
    k.m("hazards placed per course", course_mean(M, "hazards_placed"), "elements", "hazards.csv", ["lane=HAZARDS"], worst_castle(M, "hazards_placed"))
    k.m("share of hazards on the route", pm(M, lambda x: share_s(x, "hazards_on_route", "hazards_placed")), "%", "hazards.csv", ["on_route"])
    k.m("hazard types per mode (count over the mode's courses)", fam or None, "elements", "hazards.csv", ["type"])
    k.m("hazard-type entropy", OrderedDict((m, entropy(list(v.values()))) for m, v in fam.items()) if fam else None, "bits", "hazards.csv", ["type"])
    k.m("largest single hazard type share", OrderedDict((m, top_share(v)) for m, v in fam.items()) if fam else None, "", "hazards.csv", ["type"])
    k.m("hazard-module acceptance (controller)", pm(M, lambda x: mean([r.get("ctl_hazard_acceptance") for c in x for r in c["castles"].values()])) if any_have(M, "castle_control.csv", "hazard_acceptance") else None,
        "share", "castle_control.csv", ["hazard_acceptance"])
    k.m("windows with no hazard, per course", pm(M, lambda x: mean([ssum(r.get("no_hazards_windows") for r in c["castles"].values()) for c in x])), "windows", "castles.csv", ["HAZARDS low_gap_windows"])
    out["4"] = k.finish()

    # KPI 5 enemies --------------------------------------------------------------------------------------------------
    k = KPI("5")
    lane_kpi(k, M, "enemies", "castles.csv ENEMIES adj_sum")
    fam = per_mode_types(M, "ENEMIES")
    k.m("enemies placed per course", course_mean(M, "enemies_placed"), "elements", "hazards.csv", ["lane=ENEMIES"], worst_castle(M, "enemies_placed"))
    k.m("enemy types per mode (count over the mode's courses)", fam or None, "elements", "hazards.csv", ["type"])
    k.m("largest single enemy type share (reliance on one type)", OrderedDict((m, top_share(v)) for m, v in fam.items()) if fam else None, "", "hazards.csv", ["type"])
    k.m("enemy-type entropy", OrderedDict((m, entropy(list(v.values()))) for m, v in fam.items()) if fam else None, "bits", "hazards.csv", ["type"])
    k.m("unreachable enemies per course", pm(M, lambda x: mean([ssum(r.get("unreachable_enemies") for r in c["castles"].values()) for c in x])), "enemies", "windows.csv", ["unreachable_enemies"])
    k.m("Enforcers per course", course_mean(M, "enforcers"), "placed", "encounters.csv", ["enforcers_yellow", "enforcers_orange", "enforcers_red"])
    k.m("Sim setbacks during the witness climb, per course", course_mean(M, "setbacks"), "setbacks", "encounters.csv", ["setbacks"], worst_course(M, "setbacks", False))
    out["5"] = k.finish()

    # KPI 6 ----------------------------------------------------------------------------------------------------------
    k = KPI("6")
    lane_kpi(k, M, "interaction", "castles.csv INTERACTION adj_sum")
    k.m("mitigation lane per course", course_mean(M, "mitigation"), "points", "castles.csv", ["MITIGATION adj_sum"], worst_course(M, "mitigation", False))
    k.m("interaction share of the total", pm(M, lambda x: share_s(x, "interaction", "total")), "%", "castles.csv")
    k.m("mitigation as a share of the total", pm(M, lambda x: share_s(x, "mitigation", "total")), "%", "castles.csv")
    k.m("double-counting audit", None, file="ledger.csv", required=False, note="the ledger's rule-by-rule audit is not part of this package; " + NM)
    out["6"] = k.finish()

    # KPI 7A ---------------------------------------------------------------------------------------------------------
    k = KPI("7A")
    win_stats = OrderedDict()
    for m in modes_found(M):
        v = sorted(w["total"] for c in by_mode(M, m) for w in c["windows"] if w["total"] is not None)
        if v:
            q = statistics.quantiles(v, n=4) if len(v) > 3 else [v[0], v[len(v) // 2], v[-1]]
            win_stats[m] = OrderedDict(windows=len(v), mean=mean(v), median=statistics.median(v), sd=sd(v), min=v[0], q1=q[0], q3=q[2], max=v[-1])
    k.m("25 m window total, distribution per mode", win_stats or None, "points per window", "windows.csv", ["total_reference"])
    k.m("castle window sd (mean over castles)", pm(M, lambda x: mean([r.get("win_sd") for c in x for r in c["castles"].values() if r.get("complete")])), "points",
        "castles.csv", ["TOTAL_REFERENCE sd"], worst_castle(M, "win_sd"))
    k.m("spike windows per course (above q3 + 1.5 IQR of their castle)", pm(M, lambda x: mean([ssum(r.get("spike_windows") for r in c["castles"].values()) for c in x])),
        "windows", "castles.csv", ["TOTAL_REFERENCE high_spike_windows"], worst_castle(M, "spike_windows"))
    k.m("hardest 25 m window", pm(M, lambda x: max((r["hardest_window"] for c in x for r in c["castles"].values() if r.get("hardest_window") is not None), default=None)),
        "points", "windows.csv", ["total_reference"], worst_castle(M, "hardest_window"))
    k.m("windows with no hazard and longest such run", pm(M, lambda x: max((r.get("longest_zero_hazard_windows") or 0 for c in x for r in c["castles"].values()), default=None) if any(c["windows"] for c in x) else None),
        "windows (25 m each)", "windows.csv", ["hazard_adj"], worst_castle(M, "longest_zero_hazard_windows"))
    k.m("longest unchallenged climb per castle (max)", pm(M, lambda x: max((r["longest_unchallenged_climb_m"] for c in x for r in c["castles"].values() if r.get("longest_unchallenged_climb_m") is not None), default=None)),
        "m", "traversal.csv", ["longest_unchallenged_climb_m"], worst_castle(M, "longest_unchallenged_climb_m"))
    k.m("unchallenged horizontal travel per castle (mean)", pm(M, lambda x: mean([r.get("unchallenged_horizontal_m") for c in x for r in c["castles"].values()])), "m", "traversal.csv", ["unchallenged_horizontal_m"])
    k.m("longest run of consecutive hazard links", pm(M, lambda x: max((r.get("longest_hazard_link_run") or 0 for c in x for r in c["castles"].values()), default=None) if any(has(c, "links.csv") for c in x) else None),
        "links", "links.csv", ["hazards"], worst_castle(M, "longest_hazard_link_run"))
    out["7A"] = k.finish()

    # KPI 7B ---------------------------------------------------------------------------------------------------------
    k = KPI("7B")
    curve = M["curve"]
    k.m("mode-mean castle totals 1-10", OrderedDict((m, [curve[m].get(i) for i in range(1, 11)]) for m in modes_found(M)) or None, "points per castle", "castles.csv", ["TOTAL_REFERENCE adj_sum"])
    k.m("growth castle 1 -> 10 (mode mean curve)", OrderedDict((m, (curve[m][10] - curve[m][1]) / curve[m][1] if curve[m].get(1) and curve[m].get(10) is not None else None) for m in modes_found(M)) or None, "%", "castles.csv")
    k.m("growth castle 1 -> 10 per course", {c["run"]: c["summary"]["growth_c1_c10"] for c in cs} or None, "%", "castles.csv", worst=worst_course(M, "growth_c1_c10", True))
    k.m("castle-to-castle growth (mode mean curve)", OrderedDict((m, [((curve[m][i] - curve[m][i - 1]) / curve[m][i - 1]) if curve[m].get(i - 1) and curve[m].get(i) is not None else None for i in range(2, 11)]) for m in modes_found(M)) or None, "% per castle", "castles.csv")
    k.m("dips (castle below its predecessor), mode mean curve", OrderedDict((m, sum(1 for i in range(2, 11) if curve[m].get(i - 1) is not None and curve[m].get(i) is not None and curve[m][i] < curve[m][i - 1])) for m in modes_found(M)) or None, "castles", "castles.csv")
    k.m("dips per course", {c["run"]: c["summary"]["dips"] for c in cs} or None, "castles", "castles.csv", worst=worst_course(M, "dips", False))
    k.m("worst castle step per course", {c["run"]: c["summary"]["worst_step"] for c in cs} or None, "%", "castles.csv", worst=worst_course(M, "worst_step", True))
    k.m("castle-to-castle swing (sd of steps, mode mean curve)", OrderedDict((m, sd([curve[m][i] - curve[m][i - 1] for i in range(2, 11) if curve[m].get(i - 1) is not None and curve[m].get(i) is not None])) for m in modes_found(M)) or None, "points", "castles.csv")
    sep = separation(M)
    k.m("inter-difficulty advantage at castle 1 / castle 10", OrderedDict((p, (s["adv1"], s["adv10"])) for p, s in sep.items()) or None, "%", "castles.csv")
    k.m("separation erosion castle 1 -> 10", OrderedDict((p, s["erosion"]) for p, s in sep.items()) or None, "%", "castles.csv")
    k.check("difficulty ordering", "NORMAL < PUNISHER < NGH at every castle (mode means)", *ordering_check(M), file="castles.csv", columns=["TOTAL_REFERENCE adj_sum"])
    for p in ("PUNISHER over NORMAL", "NGH over PUNISHER"):
        s = sep.get(p)
        if s and s["adv1"] is not None and s["adv1"] <= 0:
            k.check(f"separation erosion, {p}", "at most 25% loss of the adjacent-mode percentage advantage, castle 1 -> 10", "FAIL",
                    f"no advantage to erode: {p.split(' over ')[0]} is {100 * s['adv1']:+.1f}% against {p.split(' over ')[1]} at castle 1 ({100 * s['adv10']:+.1f}% at castle 10)", "castles.csv")
        elif not s or s["erosion"] is None:
            k.check(f"separation erosion, {p}", "at most 25% loss of the adjacent-mode percentage advantage, castle 1 -> 10", "NOT VERIFIED",
                    s["why"] if s else "both modes' castles 1 and 10 are needed", "castles.csv")
        else:
            k.check(f"separation erosion, {p}", "at most 25% loss of the adjacent-mode percentage advantage, castle 1 -> 10", "PASS" if s["erosion"] <= 0.25 else "FAIL",
                    f"{100 * s['adv1']:+.1f}% -> {100 * s['adv10']:+.1f}%: erosion {100 * s['erosion']:.1f}%", "castles.csv")
    # controller observability (castle_control.csv KPI 7B columns)
    ctl_cols = ["req_terrain", "saturated", "growth_vs_prev", "cum_growth", "planned_rise", "slope_error", "modules_attempted", "modules_refused", "modules_refused_early"]
    for col in ctl_cols:
        if not all_have(M, "castle_control.csv", col): k.m(f"controller {col}", None, file="castle_control.csv", columns=[col])
    if all_have(M, "castle_control.csv", "slope_error"):
        k.m("controller slope error (worst castle)", pm(M, lambda x: min((r["ctl_slope_error"] for c in x for r in c["castles"].values() if r.get("ctl_slope_error") is not None), default=None)), "",
            "castle_control.csv", ["slope_error"], worst_castle(M, "ctl_slope_error", lowest=True))
        k.m("castles with a saturated controller factor", pm(M, lambda x: sum(1 for c in x for r in c["castles"].values() if r.get("ctl_saturated"))), "castles", "castle_control.csv", ["saturated"])
        k.m("modules refused / attempted (early) per course", pm(M, lambda x: f"{fmt(mean([ssum(r.get('ctl_modules_refused') for r in c['castles'].values()) for c in x]), 0)} / "
                                                                       f"{fmt(mean([ssum(r.get('ctl_modules_attempted') for r in c['castles'].values()) for c in x]), 0)} "
                                                                       f"({fmt(mean([ssum(r.get('ctl_modules_refused_early') for r in c['castles'].values()) for c in x]), 0)})"),
            "modules", "castle_control.csv", ["modules_refused", "modules_attempted", "modules_refused_early"])
    if all_have(M, "castle_control.csv", "target_total"):
        k.m("deviation from target (mean over castles)", pm(M, lambda x: mean([r.get("ctl_deviation_from_target") for c in x for r in c["castles"].values() if r.get("complete")])), "",
            "castle_control.csv", ["deviation_from_target"], worst_castle(M, "ctl_deviation_from_target", lowest=True))
    out["7B"] = k.finish()

    # KPI 7 = 7A and 7B together --------------------------------------------------------------------------------------
    k = KPI("7")
    for sub in ("7A", "7B"):
        k.check(f"KPI {sub}", f"{KPIS[sub][0]}", out[sub]["automatic_status"], f"automatic status of {sub}")
    k.d["notes"].append("KPI 7 combines 7A and 7B: FAIL if either fails; otherwise NOT VERIFIED until both are evaluated (the final status follows the submeasures' final statuses).")
    d7 = k.finish()
    d7["missing_inputs"] = []          # 7A / 7B carry their own
    out["7"] = d7
    out.move_to_end("7A"); out.move_to_end("7B")          # display order 7, 7A, 7B (the later KPIs follow)

    # KPI 8 variety --------------------------------------------------------------------------------------------------
    k = KPI("8")
    vcol = lambda col: pm(M, lambda x: mean([r.get(col) for c in x for r in c["castles"].values() if r.get("complete")]))
    k.m("unique mechanics per castle (mean)", vcol("unique_mechanics"), "mechanics", "variety_castles.csv", ["unique_mechanics"], worst_castle(M, "unique_mechanics", lowest=True))
    k.m("signature entropy per castle (mean)", vcol("signature_entropy"), "bits", "variety_castles.csv", ["signature_entropy"], worst_castle(M, "signature_entropy", lowest=True))
    k.m("variety score per castle (mean)", vcol("mean_variety_score"), "0-1", "variety_castles.csv", ["mean_variety_score"], worst_castle(M, "mean_variety_score", lowest=True))
    k.m("longest same-signature run (max)", pm(M, lambda x: max((r["longest_signature_run"] for c in x for r in c["castles"].values() if r.get("longest_signature_run") is not None), default=None)),
        "links", "variety_castles.csv", ["longest_signature_run"], worst_castle(M, "longest_signature_run"))
    k.m("repeated trigrams per castle (mean)", vcol("repeated_trigrams"), "trigrams", "variety_castles.csv", ["repeated_trigrams"])
    k.m("similarity to the previous castle (Jaccard, mean)", vcol("jaccard_vs_previous_castle"), "0-1", "variety_castles.csv", ["jaccard_vs_previous_castle"])
    k.m("rope modules per castle (mean) / max consecutive rope modules", pm(M, lambda x: f"{fmt(mean([r.get('rope_modules') for c in x for r in c['castles'].values()]), 1)} / "
                                                                                f"{fmt(max((r.get('max_consecutive_rope_modules') or 0 for c in x for r in c['castles'].values()), default=None), 0)}") if any_have(M, "traversal.csv") else None,
        "modules", "traversal.csv", ["rope_modules", "max_consecutive_rope_modules"])
    top = {}
    for c in cs:
        seqs = (c["vrun"] or {}).get("repeated_sequences") or []
        if seqs: top[c["run"]] = f"{seqs[0].get('sequence')} x{seqs[0].get('count')}"
    k.m("most repeated 3-link sequence per course", top or None, "", "variety_run.json", ["repeated_sequences"])
    absent = {}
    for c in cs:
        v = c["vrun"] or {}
        a = [f"{grp}:{x}" for grp in ("terrain", "hazards", "enemies") for x in (v.get(grp) or {}).get("absent", [])]
        if v: absent[c["run"]] = ", ".join(a) or "none"
    k.m("families absent from a course", absent or None, "", "variety_run.json", ["terrain/hazards/enemies absent"])
    rising = {}
    for c in cs:
        n = 0; ok = False
        for i in range(2, 11):
            a, b = c["castles"][i - 1], c["castles"][i]
            if a.get("mean_variety_score") is not None and b.get("mean_variety_score") is not None and b.get("growth_pct") is not None:
                ok = True; n += b["growth_pct"] > 0 and b["mean_variety_score"] < a["mean_variety_score"]
        rising[c["run"]] = n if ok else None
    k.m("castles where difficulty rises while variety falls", rising if any(v is not None for v in rising.values()) else None, "castles", "castles.csv + variety_castles.csv")
    vs = M["variety_summary"]
    k.m("variety flags against the baseline", len(vs.get("flags", [])) if vs else None, "flags", "variety_summary.json (variety_report.py)", ["flags"],
        note="" if vs else "variety_report.py output not supplied (--variety-dir)")
    k.m("seed-to-seed similarity", vs.get("seed_similarity") if vs else None, "0-1", "variety_summary.json", ["seed_similarity"])
    rc = Counter()
    for c in cs:
        for r, n in (c["summary"]["rule_checks"] or {}).items(): rc[r] += n
    k.m("rule-check records (placement rules)", dict(rc) if any_have(M, "rule_checks.csv") else None, "records", "rule_checks.csv", ["rule"])
    out["8"] = k.finish()

    # KPI 9 generator ------------------------------------------------------------------------------------------------
    k = KPI("9")
    k.m("module tries per course", course_mean(M, "modules_tried"), "modules", "module_inventory.csv", ["tried"])
    k.m("modules accepted in the course per course", course_mean(M, "modules_accepted"), "modules", "module_inventory.csv", ["accepted_in_course"])
    k.m("acceptance (accepted / tried)", pm(M, lambda x: share_s(x, "modules_accepted", "modules_tried")), "%", "module_inventory.csv")
    k.m("accepted as fallback per course", course_mean(M, "modules_fallback"), "modules", "module_inventory.csv", ["accepted_as_fallback"])
    k.m("refused modules per course", course_mean(M, "modules_refused"), "modules", "module_inventory.csv", ["refused"])
    k.m("refused early (before any proof) per course", course_mean(M, "modules_refused_early") if all_have(M, "modules.csv", "refused_early") else None, "modules", "modules.csv", ["refused_early"])
    rr = OrderedDict()
    for m in modes_found(M):
        rr[m] = OrderedDict((col.replace("refused_", ""), ssum(x.get(col) for c in by_mode(M, m) for x in c["modules"])) for col in REFUSALS)
    k.m("refusal-check tallies (refused attempts, summed over the mode)", rr if any(v for d in rr.values() for v in d.values() if v) else None, "checks", "modules.csv", REFUSALS)
    k.m("failed slice attempts per course (retried)", course_mean(M, "failed_attempts"), "attempts", "chunks.csv", ["failed_attempts"], worst_course(M, "failed_attempts", False))
    k.m("relaxed slices per course", course_mean(M, "relaxed_slices"), "slices", "chunks.csv", ["relaxed"])
    k.m("failed-attempt records per course", course_mean(M, "generation_failure_records"), "records", "generation_failures.csv")
    never = {}
    for c in cs:
        n = [m["kind"] for m in c["modules"] if m.get("inventory_status", "").startswith(("SELECTED, NEVER", "NEVER"))]
        if c["modules"]: never[c["run"]] = ", ".join(n) or "none"
    k.m("module kinds never accepted", never or None, "", "module_inventory.csv", ["status"])
    k.m("Enforcers planned vs placed per course", pm(M, lambda x: f"{fmt(mean([c['summary']['enforcers_planned'] for c in x]))} / {fmt(mean([c['summary']['enforcers'] for c in x]))}") if all_have(M, "encounters.csv", "enforcers_planned") else None,
        "", "encounters.csv", ["enforcers_planned", "enforcers_*"])
    k.m("avalanches planned vs placed per course", pm(M, lambda x: f"{fmt(mean([c['summary']['avalanches_planned'] for c in x]))} / {fmt(mean([c['summary']['avalanches'] for c in x]))}") if all_have(M, "encounters.csv", "avalanches_planned") else None,
        "", "encounters.csv", ["avalanches_planned", "avalanches"])
    k.m("encounters refused as unfair / dropped per course (Enforcers; avalanches)", pm(M, lambda x: f"{fmt(mean([c['summary']['enforcer_unfair'] for c in x]))}/{fmt(mean([c['summary']['enforcer_dropped'] for c in x]))}; "
                                                                                                 f"{fmt(mean([c['summary']['avalanche_unfair'] for c in x]))}/{fmt(mean([c['summary']['avalanche_dropped'] for c in x]))}") if any_have(M, "encounters.csv") else None,
        "", "encounters.csv", ["enforcer_unfair", "enforcer_dropped", "avalanche_unfair", "avalanche_dropped"])
    k.m("fallback and selection-guard tallies", fallback_tallies(M), "", "fallbacks.csv")
    out["9"] = k.finish()

    # KPI 10 solver --------------------------------------------------------------------------------------------------
    k = KPI("10")
    k.m("solver verdicts", dict(tally) if vc else None, "slices", "solver.csv", ["verdict"])
    fc = Counter()
    for c in cs:
        for v, n in (c["summary"]["fail_classes"] or {}).items(): fc[v] += n
    k.m("solver outcome classes (legacy fail_class)", dict(fc) if fc else None, "slices", "solver.csv", ["fail_class"])
    k.m("failed links / unreached links", OrderedDict((c["run"], f"{c['summary']['links_failed']} / {c['summary']['links_unreached']}") for c in cs if c["summary"]["links_failed"] is not None) or None,
        "links", "links.csv", ["solver_status"])
    k.m("solver failure records", ssum(c["summary"]["solver_failure_rows"] for c in cs), "records", "solver_failures.csv")
    for col, what in (("witness_setbacks", "witness setbacks"), ("resyncs", "resyncs (landed on another route element and carried on)"), ("fallback_moves", "moves without a look-ahead-good follow-up"),
                      ("waits", "waits on the spot"), ("unclear_moves", "moves made while a look-ahead still saw a hazard")):
        k.m(f"{what}, per course", course_mean(M, col) if all_have(M, "solver.csv", col) else None, "", "solver.csv", [col], worst_course(M, col, False) if all_have(M, "solver.csv", col) else None)
    ac = sorted({h for c in cs for h in c["summary"]["assist_columns"]})
    k.m("collision-assisted vs clean proofs", {h: ssum(r.get(h) for c in cs for r in c["slices"]) for h in ac} if ac else None, "slices", "solver.csv", ac or ["(no *assist* column)"],
        note="" if ac else "no collision-assist column in solver.csv; never inferred from the other counters", required=False)
    k.m("smallest timing margin", min((r["min_margin"] for c in cs for r in c["slices"] if r["min_margin"] is not None), default=None), "", "solver.csv", ["min_margin"])
    k.check("slices proven", "100% of tested slices PROVEN_SOLVABLE (acceptance standard: solvability)", *proven_check(M))
    out["10"] = k.finish()

    # KPI 11 avalanches ----------------------------------------------------------------------------------------------
    k = KPI("11")
    k.m("avalanches per course", course_mean(M, "avalanches"), "events", "encounters.csv", ["avalanches"], worst_course(M, "avalanches", True, ["PUNISHER", "NGH"]))
    k.m("avalanches per castle (mode mean)", OrderedDict((m, [mc_get(M, m, i, "avalanches_mean") for i in range(1, 11)]) for m in modes_found(M)) if any_have(M, "encounters.csv") else None,
        "events", "encounters.csv", ["avalanches"])
    ev_rows = [e for c in cs for e in c["avalanche_events"]]
    bl = per_mode(M, lambda x: [e["blocks"] for c in x for e in c["avalanche_events"] if e["blocks"] is not None])
    k.m("blocks per avalanche (mean, min-max)", OrderedDict((m, f"{fmt(mean(v), 1)} ({min(v)}-{max(v)})" if v else "no avalanches") for m, v in bl.items()) if any_have(M, "hazards.csv") else None,
        "blocks", "hazards.csv", ["type=AVALANCHE", "len"])
    for col, what in AVL_COUNTERS.items():
        k.m(f"{what} per course", course_mean(M, col) if all_have(M, "encounters.csv", col) else None, "", "encounters.csv", [col])
    k.m("refused as unfair / dropped per course", pm(M, lambda x: f"{fmt(mean([c['summary']['avalanche_unfair'] for c in x]))} / {fmt(mean([c['summary']['avalanche_dropped'] for c in x]))}") if any_have(M, "encounters.csv") else None,
        "", "encounters.csv", ["avalanche_unfair", "avalanche_dropped"])
    normal = by_mode(M, "NORMAL")
    if not normal: k.check("NORMAL avalanches", "only castles 9-10, at most 2 blocks each", "NOT VERIFIED", "no NORMAL course", "hazards.csv")
    elif not all(has(c, "hazards.csv") for c in normal): k.check("NORMAL avalanches", "only castles 9-10, at most 2 blocks each", "NOT VERIFIED", "hazards.csv missing in a NORMAL course", "hazards.csv")
    else:
        ne = [e for c in normal for e in c["avalanche_events"]]
        bad = [e for e in ne if e["castle"] not in (9, 10) or e["blocks"] is None or e["blocks"] > 2]
        enc_bad = [(c["run"], i) for c in normal for i, r in c["castles"].items() if i < 9 and (r.get("avalanches") or 0) > 0]
        k.check("NORMAL avalanches", "only castles 9-10, at most 2 blocks each", "FAIL" if bad or enc_bad else "PASS",
                f"{len(ne)} NORMAL avalanches" + (f"; violations: " + "; ".join(f"{e['run']} castle {e['castle']} {e['blocks']} blocks" for e in bad[:6]) if bad else "") +
                (f"; encounters.csv counts avalanches before castle 9 in {enc_bad[:4]}" if enc_bad else ""), "hazards.csv, encounters.csv", ["type", "castle", "len", "avalanches"],
                {"run": bad[0]["run"], "mode": "NORMAL", "seed": bad[0]["seed"], "castle": bad[0]["castle"], "value": bad[0]["blocks"]} if bad else None)
    out["11"] = k.finish()

    # KPI 12 Enforcers -----------------------------------------------------------------------------------------------
    k = KPI("12")
    for col, tier, _, _ in TIERS:
        k.m(f"{tier} Enforcers placed per course", course_mean(M, col), "placed", "encounters.csv", [col])
    k.m("Enforcers per castle (mode mean)", OrderedDict((m, [mc_get(M, m, i, "enforcers_mean") for i in range(1, 11)]) for m in modes_found(M)) if any_have(M, "encounters.csv") else None,
        "placed", "encounters.csv", ["enforcers_*"])
    for col, what in ENF_COUNTERS.items():
        k.m(f"{what} per course (all tiers)", course_mean(M, col) if all_have(M, "encounters.csv", col) else None, "", "encounters.csv", [col],
            note="Sim counter during the solver's witness climb; the tier is not recorded per counter")
    for what in ("pursuit distance", "platforms followed", "successful knock-offs", "disengagements"):
        k.m(what, None, file="encounters.csv", required=False,
            note=f"not instrumented: LaneReport has no {what} counter; never inferred from other counters or from absence (the analyst weighs this gap)")
    if not normal: k.check("no Enforcers in NORMAL", "0 Enforcers in every NORMAL course", "NOT VERIFIED", "no NORMAL course", "encounters.csv")
    elif not all(has(c, "encounters.csv") or has(c, "hazards.csv") for c in normal):
        k.check("no Enforcers in NORMAL", "0 Enforcers in every NORMAL course", "NOT VERIFIED", "encounters.csv and hazards.csv missing in a NORMAL course", "encounters.csv")
    else:
        n_enc = sum(c["summary"]["enforcers"] or 0 for c in normal); n_el = sum(c["summary"]["enforcer_elements"] or 0 for c in normal)
        k.check("no Enforcers in NORMAL", "0 Enforcers in every NORMAL course", "PASS" if n_enc == 0 and n_el == 0 else "FAIL",
                f"{int(n_enc)} placed (encounters.csv), {int(n_el)} ENFORCER elements (hazards.csv)", "encounters.csv, hazards.csv", ["enforcers_*", "type=ENFORCER"],
                worst_course(M, "enforcers", False, ["NORMAL"]) if n_enc else None)
    out["12"] = k.finish()

    # KPI 13 performance ---------------------------------------------------------------------------------------------
    k = KPI("13")
    k.m("generation time per course", course_mean(M, "gen_s"), "s", "run.json", ["generation_seconds"], worst_course(M, "gen_s", False))
    k.m("generation time per metre", course_mean(M, "s_per_m"), "s/m", "run.json", ["generation_seconds", "reached_m"], worst_course(M, "s_per_m", False))
    k.m("generation time per slice", course_mean(M, "s_per_slice"), "s", "run.json", ["generation_seconds", "slices"])
    k.m("solver time per course", course_mean(M, "solve_s"), "s", "run.json", ["solver_seconds"], worst_course(M, "solve_s", False))
    k.m("peak heap", pm(M, lambda x: max((c["summary"]["peak_heap_mb"] for c in x if c["summary"]["peak_heap_mb"] is not None), default=None)), "MB", "chunks.csv", ["heap_used_bytes"], worst_course(M, "peak_heap_mb", False))
    k.m("largest per-slice generation allocation", pm(M, lambda x: max((c["summary"]["max_gen_alloc_mb"] for c in x if c["summary"]["max_gen_alloc_mb"] is not None), default=None)), "MB", "chunks.csv", ["gen_alloc_bytes"])
    k.m("rejected-attempt overhead (failed-attempt time / generation time)", pm(M, lambda x: share_s(x, "failed_attempts_s", "gen_s")), "%", "chunks.csv, run.json", ["failed_attempts_s"])
    sl = sorted((r for c in cs for r in c["slices"] if r["gen_wall_s"] is not None), key=lambda r: -r["gen_wall_s"])
    k.m("slowest slice", f"{sl[0]['run']} slice {sl[0]['slice']} (castle {sl[0]['castle']}): {sl[0]['gen_wall_s']:.1f} s" if sl else None, "s", "chunks.csv", ["gen_wall_s"],
        {"run": sl[0]["run"], "mode": sl[0]["mode"], "seed": sl[0]["seed"], "castle": sl[0]["castle"], "value": sl[0]["gen_wall_s"]} if sl else None)
    exp = per_mode(M, lambda x: sorted(((kd, ssum(mm.get("wall_ms") for c in x for mm in c["modules"] if mm["kind"] == kd)) for kd in {mm["kind"] for c in x for mm in c["modules"]}),
                                       key=lambda t: -(t[1] or 0))[:3])
    k.m("most expensive module kinds (wall time summed over the mode)", OrderedDict((m, ", ".join(f"{kd} {v / 1000:.0f} s" for kd, v in lst if v)) for m, lst in exp.items()) if any_have(M, "modules.csv") else None,
        "", "modules.csv", ["wall_ms"])
    k.m("CI time", ev.get("ci_minutes"), "min", "--evidence ci_minutes", note="" if ev.get("ci_minutes") else "not supplied", required=False)
    out["13"] = k.finish()

    # KPI 14 ---------------------------------------------------------------------------------------------------------
    k = KPI("14")
    k.m("full CI conclusion on the candidate", ev.get("ci_conclusion"), "", "--evidence ci_conclusion", note="" if ev.get("ci_conclusion") else "evidence from the full CI run not supplied")
    k.m("full CI commit", ev.get("ci_sha"), "", "--evidence ci_sha")
    k.m("full CI run", ev.get("ci_run_url"), "", "--evidence ci_run_url", required=False)
    k.m("replay event/state hashes, saves", None, file="course outputs", note="the campaign records no replay or save evidence; it comes from the full CI suite", required=False)
    k.check("full CI on the candidate", "full CI (determinism, replays, saves) passes on the candidate commit", *ci_check(ev, candidate_sha))
    out["14"] = k.finish()

    # KPI 15 ---------------------------------------------------------------------------------------------------------
    k = KPI("15")
    plats = ["release_android", "release_windows", "release_linux", "release_steam", "release_steamdeck"]
    for p in plats: k.m(p.replace("release_", "") + " evidence", ev.get(p), "", f"--evidence {p}", note="" if ev.get(p) else "not supplied")
    vals = [str(ev.get(p, "")).lower() for p in plats]
    bad = [p for p, v in zip(plats, vals) if v in ("failure", "failed", "fail")]
    st = "FAIL" if bad else ("PASS" if all(v in ("success", "pass", "passed") for v in vals) else "NOT VERIFIED")
    k.check("platform evidence", "identity, packaging, install and startup verified on Android, Windows, Linux, Steam, Steam Deck", st,
            ", ".join(f"{p.replace('release_', '')}={ev.get(p, 'not supplied')}" for p in plats), "--evidence release_*")
    k.d["notes"].append("Passing this KPI does not authorise publication.")
    out["15"] = k.finish()

    # KPI 16 ---------------------------------------------------------------------------------------------------------
    k = KPI("16")
    if any(c["mechanics"] for c in cs):
        for m in modes_found(M):
            rows = [r for c in by_mode(M, m) for r in c["mechanics"]]
            n = len(by_mode(M, m))
            tab = OrderedDict()
            for t in MECHANICS:
                rr_ = [r for r in rows if r["mechanic"] == t]
                pl, mr, rl, tr = (sum(r[f] for r in rr_) for f in ("placed", "main_route", "route_links", "solver_traversed"))
                tab[t] = OrderedDict(placed=pl / n, main_route=mr / n, optional=(pl - mr) / n, route_links=rl / n, solver_traversed=tr / n,
                                     castles_present=len({(r["run"], r["castle"]) for r in rr_ if r["placed"]}) / n)
            k.m(f"{m}: per mechanic per course (placed, main route, optional, route links, solver-traversed, castles present)", tab, "per course", "elements.csv, links.csv",
                ["type", "route", "castle", "from_type", "to_type", "solver_status"])
        k.m("mechanics placed but never traversed by the solver", pm(M, lambda x: ", ".join(t for t in MECHANICS if sum(r["placed"] for c in x for r in c["mechanics"] if r["mechanic"] == t) and
                                                                                         not sum(r["solver_traversed"] for c in x for r in c["mechanics"] if r["mechanic"] == t)) or "none"),
            "", "elements.csv, links.csv")
    else:
        k.m("mechanic utilization", None, file="elements.csv, links.csv")
    k.m("share of route links using a mechanic", pm(M, lambda x: share(x, "mechanic_links", "route_links")), "%", "links.csv", ["from_type", "to_type"])
    out["16"] = k.finish()
    return out


def lane_kpi(k, M, lane, src):
    k.m(f"{lane} lane per course", course_mean(M, lane), "points", "castles.csv", [src], worst_course(M, lane, True))
    k.m(f"{lane} lane castle 1 / castle 10 (mode mean)", OrderedDict((m, (mc_get(M, m, 1, lane + "_mean"), mc_get(M, m, 10, lane + "_mean"))) for m in modes_found(M)) or None, "points", "castles.csv", [src])
    k.m(f"{lane} lane growth castle 1 -> 10", OrderedDict((m, ((mc_get(M, m, 10, lane + "_mean") - mc_get(M, m, 1, lane + "_mean")) / mc_get(M, m, 1, lane + "_mean"))
                                                            if mc_get(M, m, 1, lane + "_mean") and mc_get(M, m, 10, lane + "_mean") is not None else None) for m in modes_found(M)) or None, "%", "castles.csv", [src])
    k.m(f"{lane} lane dips (castle below its predecessor, mode mean)", OrderedDict((m, sum(1 for i in range(2, 11) if mc_get(M, m, i, lane + "_mean") is not None and mc_get(M, m, i - 1, lane + "_mean") is not None
                                                                                       and mc_get(M, m, i, lane + "_mean") < mc_get(M, m, i - 1, lane + "_mean"))) for m in modes_found(M)) or None, "castles", "castles.csv", [src])


def share(cs, a, b):
    x = ssum(r.get(a) for c in cs for r in c["castles"].values()); y = ssum(r.get(b) for c in cs for r in c["castles"].values())
    return x / y if x is not None and y else None


def share_s(cs, a, b):
    x = ssum(c["summary"].get(a) for c in cs); y = ssum(c["summary"].get(b) for c in cs)
    return x / y if x is not None and y else None


def per_mode_types(M, lane):
    out = OrderedDict()
    for m in modes_found(M):
        cnt = Counter()
        for c in by_mode(M, m):
            for r in c["hazard_types"]:
                if r["lane"] == lane: cnt[r["type"]] += r["placed"]
        if cnt: out[m] = OrderedDict(cnt.most_common())
    return out


def top_share(cnt):
    n = sum(cnt.values())
    if not n: return None
    t, v = max(cnt.items(), key=lambda x: x[1])
    return f"{t} {100 * v / n:.1f}%"


def fallback_tallies(M):
    if not all_have(M, "fallbacks.csv"): return None
    out = OrderedDict()
    for m in modes_found(M):
        cnt = Counter()
        for c in by_mode(M, m):
            for r in c["t"].get("fallbacks.csv", []):
                for kk, v in r.items():
                    if kk not in ("slice", "y0", "castle") and fnum(v): cnt[kk] += fnum(v)
        out[m] = OrderedDict((kk, v / len(by_mode(M, m))) for kk, v in cnt.most_common())
    return out


def sample_check(M):
    cs = present(M)
    per = Counter(c["mode"] for c in cs)
    full = [c for c in cs if c["summary"]["castles_complete"] >= 10]
    meas = f"{len(cs)} courses (" + ", ".join(f"{m} {per.get(m, 0)}" for m in MODES) + f"); {len(full)} full height"
    missing = [c["run"] for c in M["courses"] if not c["present"]]
    if missing or any(per.get(m, 0) < 3 for m in MODES): return ("NOT VERIFIED", meas + (f"; missing {', '.join(missing)}" if missing else "") + " - the standard is 3 seeds per difficulty", "course folders")
    if len(full) < len(cs): return ("FAIL", meas, "course folders")
    return ("PASS", meas, "course folders")


def ci_check(ev, cand):
    conc = str(ev.get("ci_conclusion", "")).lower()
    if not conc: return ("NOT VERIFIED", "full CI conclusion not supplied (--evidence ci_conclusion=success ci_sha=...)", "--evidence ci_conclusion")
    if conc not in ("success", "pass", "passed"): return ("FAIL", f"full CI conclusion: {conc}", "--evidence ci_conclusion")
    sha = ev.get("ci_sha", "")
    if not sha or not cand: return ("NOT VERIFIED", "CI passed, but the CI commit (ci_sha) or the candidate is unknown", "--evidence ci_sha")
    if not (sha.startswith(cand[:7]) or cand.startswith(sha[:7])): return ("NOT VERIFIED", f"CI passed on {sha[:12]}, not on the candidate {cand[:12]}", "--evidence ci_sha")
    return ("PASS", f"full CI success on {sha[:12]}", "--evidence ci_conclusion, ci_sha")


def proven_check(M):
    cs = present(M)
    if not cs: return ("NOT VERIFIED", "no courses", "solver.csv", ["verdict"])
    failed = [c["run"] for c in cs if (c["summary"]["completed_false"] or 0) > 0 or (c["summary"]["verdicts"] and any(v != "PROVEN_SOLVABLE" and n for v, n in c["summary"]["verdicts"].items()))]
    if failed: return ("FAIL", "slices not proven in: " + ", ".join(failed), "solver.csv", ["verdict", "completed"])
    if not all(c["summary"]["verdict_column"] for c in cs):
        return ("NOT VERIFIED", "solver.csv has no 'verdict' column in " + ", ".join(c["run"] for c in cs if not c["summary"]["verdict_column"]) +
                " (older rules: 'completed' is not a proof verdict)", "solver.csv", ["verdict"])
    n = sum(c["summary"]["solver_slices"] or 0 for c in cs); p = sum(c["summary"]["proven"] or 0 for c in cs)
    if n == 0: return ("NOT VERIFIED", "no solver slices (solver off?)", "solver.csv", ["verdict"])
    return ("PASS", f"{p} / {n} PROVEN_SOLVABLE", "solver.csv", ["verdict"])


def separation(M):
    out = OrderedDict()
    c = M["curve"]
    for lo, hi in (("NORMAL", "PUNISHER"), ("PUNISHER", "NGH")):
        p = f"{hi} over {lo}"
        need = [c[lo].get(1), c[lo].get(10), c[hi].get(1), c[hi].get(10)]
        if any(v is None for v in need): out[p] = {"adv1": None, "adv10": None, "erosion": None, "per_castle": [], "why": f"castles 1 and 10 of {lo} and {hi} are needed"}; continue
        adv = [((c[hi][i] - c[lo][i]) / c[lo][i]) if c[lo].get(i) and c[hi].get(i) is not None else None for i in range(1, 11)]
        a1, a10 = adv[0], adv[9]
        er = (a1 - a10) / a1 if a1 and a1 > 0 else None
        out[p] = {"adv1": a1, "adv10": a10, "erosion": er, "per_castle": adv, "why": "" if er is not None else f"{hi} is not above {lo} at castle 1"}
    return out


def ordering_check(M):
    if any(not by_mode(M, m) for m in MODES): return ("NOT VERIFIED", "all three difficulties are needed")
    bad, miss = [], []
    for i in range(1, 11):
        v = [M["curve"][m].get(i) for m in MODES]
        if any(x is None for x in v): miss.append(i); continue
        if not (v[0] < v[1] < v[2]): bad.append(f"castle {i} ({' / '.join(f'{x:.0f}' for x in v)})")
    if bad: return ("FAIL", "not ordered at " + "; ".join(bad))
    if miss: return ("NOT VERIFIED", "castles not measured in every mode: " + ", ".join(map(str, miss)))
    return ("PASS", "ordered at all 10 castles")


def apply_overrides(kpis, ov):
    """Analyst status / interpretation / action. An analyst PASS is refused where the automatic evaluation found missing inputs or a FAIL."""
    exceptions = []
    order = [k for k in kpis if k != "7"] + ["7"]          # KPI 7 after its submeasures
    for kid in order:
        d = kpis[kid]
        o = (ov or {}).get("kpis", {}).get(kid) or {}
        d["interpretation"] = o.get("interpretation", "")
        d["recommended_action"] = o.get("recommended_action", o.get("action", ""))
        d["analyst"] = (ov or {}).get("analyst")
        st = (o.get("status") or "").upper().strip()
        if st and st not in STATUSES:
            exceptions.append(f"KPI {kid}: analyst status {st!r} is not one of {STATUSES}; ignored"); st = ""
        auto = d["automatic_status"]
        if kid == "7" and st == "PASS" and not (kpis["7A"]["status"] == kpis["7B"]["status"] == "PASS"):
            d["status"], d["status_source"] = auto, "automatic (analyst PASS refused: 7A and 7B must both pass)"
            exceptions.append(f"KPI 7: analyst PASS refused (7A {kpis['7A']['status']}, 7B {kpis['7B']['status']})")
        elif st == "PASS" and (d["missing_inputs"] or auto == "FAIL"):
            d["status"], d["status_source"] = auto, "automatic (analyst PASS refused: " + ("automatic FAIL" if auto == "FAIL" else "missing measurements") + ")"
            exceptions.append(f"KPI {kid}: analyst PASS refused ({'automatic FAIL' if auto == 'FAIL' else 'missing measurements: ' + '; '.join(d['missing_inputs'][:3])})")
        elif st:
            d["status"], d["status_source"] = st, "analyst"
            if auto in ("PASS", "FAIL") and st != auto: exceptions.append(f"KPI {kid}: analyst status {st} differs from the automatic {auto}")
        else:
            d["status"], d["status_source"] = auto, "automatic"
    # KPI 7 follows its submeasures' final statuses unless the analyst set it explicitly
    if kpis["7"]["status_source"] == "automatic":
        a, b = kpis["7A"]["status"], kpis["7B"]["status"]
        s = "FAIL" if "FAIL" in (a, b) else "PASS" if a == b == "PASS" else "WATCH" if "WATCH" in (a, b) and "NOT VERIFIED" not in (a, b) else "NOT VERIFIED"
        if s != kpis["7"]["status"]: kpis["7"]["status"], kpis["7"]["status_source"] = s, "derived from 7A / 7B final statuses"
    return exceptions


# ---------------------------------------------------------------- data files
CASTLE_FIELDS = ["run", "mode", "seed", "castle", "complete", "complete_source", "lane_source", "terrain", "hazards", "enemies", "interaction", "mitigation", "total",
                 "growth_pct", "step_points", "windows", "win_mean", "win_median", "win_min", "win_max", "win_sd", "win_q1", "win_q3", "spike_windows",
                 "no_hazards_windows", "no_enemies_windows", "longest_zero_hazard_windows", "hardest_window", "hardest_window_index",
                 "hazards_placed", "hazards_on_route", "enemies_placed", "enemies_on_route", "enforcer_elements",
                 "enforcers", "enforcers_yellow", "enforcers_orange", "enforcers_red", "enforcers_planned", "enforcer_unfair", "enforcer_dropped",
                 "avalanches", "avalanche_blocks", "avalanches_planned", "avalanche_unfair", "avalanche_dropped", "setbacks"] + list(ENF_COUNTERS) + list(AVL_COUNTERS) + \
                ["route_links", "mechanic_links", "links_traversed", "links_failed", "links_unreached", "gap_ratio_max", "links_gap_ratio_ge_0_9", "links_gap_ratio_gt_1",
                 "landing_width_min", "worst_gap_link", "longest_hazard_link_run", "demanding_links", "recovery_platforms", "unreachable_enemies",
                 "challenge_density", "rope_modules", "max_consecutive_rope_modules", "max_consecutive_ordinary_links", "longest_unchallenged_climb_m", "rope_climb_m",
                 "unchallenged_horizontal_m", "fallbacks_accepted", "unique_mechanics", "terrain_families", "hazard_families", "enemy_families", "terrain_entropy",
                 "hazard_entropy", "enemy_entropy", "signature_entropy", "mean_variety_score", "longest_signature_run", "longest_terrain_run", "repeated_trigrams",
                 "jaccard_vs_previous_castle", "slices", "gen_wall_s", "solve_wall_s", "failed_attempts", "failed_attempts_s", "module_attempts", "modules_accepted_final"] + \
                ["ctl_" + c for c in CTL_COLS]
COURSE_FIELDS = ["run", "mode", "seed", "folder", "present", "gate_passes", "gate_execution", "gate_generation", "gate_solver", "gate_integrity", "reached_m", "top_m",
                 "slices", "stopped", "castles_complete", "rules_version", "commit", "branch", "actions_run_id", "fingerprint", "tuning_hash", "weights_hash", "scoring_model",
                 "terrain", "hazards", "enemies", "interaction", "mitigation", "total", "growth_c1_c10", "dips", "worst_step", "step_sd_points",
                 "verdict_column", "solver_slices", "proven", "completed_false", "solver_failure_rows", "links_failed", "links_unreached", "integrity_rows",
                 "generation_failure_records", "fallback_moves", "resyncs", "waits", "unclear_moves", "witness_setbacks", "setbacks",
                 "gen_s", "solve_s", "s_per_m", "s_per_slice", "peak_heap_mb", "max_gen_alloc_mb", "failed_attempts", "failed_attempts_s", "relaxed_slices",
                 "hazards_placed", "hazards_on_route", "enemies_placed", "enemies_on_route", "enforcer_elements", "enforcers", "enforcers_yellow", "enforcers_orange",
                 "enforcers_red", "enforcers_planned", "enforcer_unfair", "enforcer_dropped", "avalanches", "avalanche_events", "avalanche_blocks", "avalanches_planned",
                 "avalanche_unfair", "avalanche_dropped"] + list(ENF_COUNTERS) + list(AVL_COUNTERS) + \
                ["modules_tried", "modules_accepted", "modules_refused", "modules_fallback", "modules_refused_early", "verdicts", "fail_classes", "rule_checks", "missing_files"]
SLICE_FIELDS = ["run", "mode", "seed", "slice", "castle", "y0", "y1", "gen_wall_s", "solve_wall_s", "slice_attempts", "failed_attempts", "failed_attempts_s", "fallback_s",
                "modules_accepted", "modules_refused_or_discarded", "relaxed", "heap_used_mb", "gen_alloc_mb", "completed", "verdict", "cause", "fail_class", "min_margin",
                "sim_time_s", "fallback_moves", "resyncs", "waits", "unclear_moves", "witness_setbacks"]


def cell(v):
    if v is None: return ""
    if isinstance(v, float): return f"{v:.6g}"
    if isinstance(v, (dict, list)): return json.dumps(v, sort_keys=True)
    return v


def write_csv(path, fields, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f); w.writerow(fields)
        for r in rows: w.writerow([cell(r.get(k)) for k in fields])


def write_data(M, pkg):
    d = os.path.join(pkg, "data"); os.makedirs(d, exist_ok=True)
    cs = M["courses"]
    castles = [c["castles"][k] for c in cs for k in range(1, 11) if c["present"]]
    write_csv(os.path.join(d, "courses.csv"), COURSE_FIELDS, [c["summary"] for c in cs])
    write_csv(os.path.join(d, "castles.csv"), CASTLE_FIELDS, castles)
    mc_fields = list(M["mode_castles"][0].keys()) if M["mode_castles"] else ["mode", "castle", "courses"]
    write_csv(os.path.join(d, "mode_castles.csv"), mc_fields, M["mode_castles"])
    modes = []
    for m in modes_found(M):
        x = by_mode(M, m)
        row = {"mode": m, "courses": len(x)}
        for f in ["terrain", "hazards", "enemies", "interaction", "mitigation", "total", "growth_c1_c10", "dips", "gen_s", "solve_s", "s_per_m", "peak_heap_mb",
                  "enforcers", "enforcers_yellow", "enforcers_orange", "enforcers_red", "avalanches", "avalanche_blocks", "hazards_placed", "enemies_placed",
                  "modules_tried", "modules_accepted", "modules_refused", "witness_setbacks", "setbacks"]:
            v = [c["summary"].get(f) for c in x]
            row[f + "_mean"], row[f + "_min"], row[f + "_max"] = mean(v), min((y for y in v if y is not None), default=None), max((y for y in v if y is not None), default=None)
        modes.append(row)
    write_csv(os.path.join(d, "modes.csv"), list(modes[0].keys()) if modes else ["mode"], modes)
    long = {"hazard_types": [r for c in cs for r in c["hazard_types"]], "mechanics": [r for c in cs for r in c["mechanics"]],
            "modules": [r for c in cs for r in c["modules"]], "slices": [r for c in cs for r in c["slices"]], "windows": [r for c in cs for r in c["windows"]],
            "avalanche_events": [r for c in cs for r in c["avalanche_events"]]}
    for name, rows in long.items():
        fields = SLICE_FIELDS if name == "slices" else (list(OrderedDict((k, 1) for r in rows for k in r).keys()) if rows else ["run"])
        write_csv(os.path.join(d, f"{name}.csv"), fields, rows)
    blob = {"courses": [c["summary"] for c in cs], "castles": castles, "mode_castles": M["mode_castles"], "modes": modes, "separation": separation(M),
            "variety_summary": M["variety_summary"], **long}
    with open(os.path.join(d, "package_data.json"), "w") as f: json.dump(blob, f, indent=1, default=str)
    return blob


def write_kpis(pkg, kpis):
    with open(os.path.join(pkg, "kpi_measurements.json"), "w") as f: json.dump({"kpis": list(kpis.values())}, f, indent=1, default=str)
    with open(os.path.join(pkg, "kpi_measurements.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["kpi", "kpi_name", "status", "status_source", "automatic_status", "kind", "item", "value", "unit", "item_status", "evidence_file", "evidence_columns",
                    "worst_case", "note"])
        for kid, d in kpis.items():
            for c in d["checks"]:
                w.writerow([kid, d["name"], d["status"], d["status_source"], d["automatic_status"], "check", c["name"] + ": " + c["rule"], c["measured"], "", c["status"],
                            c["evidence"]["file"] or "", ";".join(c["evidence"]["columns"]), worst_str(c["worst_case"]), ""])
            for mm in d["measurements"]:
                w.writerow([kid, d["name"], d["status"], d["status_source"], d["automatic_status"], "measurement", mm["name"], vstr(mm["value"], mm["unit"]), mm["unit"],
                            "measured" if mm["measured"] else "NOT MEASURED", mm["evidence"]["file"] or "", ";".join(mm["evidence"]["columns"]), worst_str(mm["worst_case"]), mm["note"]])


def worst_str(w):
    if not w: return ""
    return f"{w.get('run')}" + (f" castle {w['castle']}" if w.get("castle") else "") + f" = {vstr(w.get('value'))}"


def vstr(v, unit=""):
    pct = unit == "%"
    if v is None: return NM
    if isinstance(v, dict): return "; ".join(f"{k}={vstr(x, unit)}" for k, x in v.items())
    if isinstance(v, (list, tuple)): return "[" + ", ".join(vstr(x, unit) for x in v) + "]"
    if isinstance(v, float): return fmt(v, 1 if pct else (3 if abs(v) < 10 else 1), pct=pct)
    return str(v)


# ---------------------------------------------------------------- charts
def nodata(ax, msg):
    ax.axis("off")
    ax.text(0.5, 0.5, msg, ha="center", va="center", fontsize=11, color=INK2, wrap=True, transform=ax.transAxes)


def mode_legend(ax, extra=None, loc="best"):
    h = [Line2D([], [], color=MODE_COL[m], marker=MODE_MARK[m], label=m) for m in MODES]
    ax.legend(handles=h + (extra or []), frameon=False, fontsize=8, loc=loc)


def chart_scorecard(kpis, title):
    fig, ax = plt.subplots(figsize=(11, 7.4))
    ax.axis("off")
    ax.set_xlim(0, 1); ax.set_ylim(0, len(kpis) + 1.6)
    ax.text(0, len(kpis) + 1.1, title, fontsize=14, weight="bold", va="center")
    hdr = [(0.0, "KPI"), (0.47, "status"), (0.66, "decided by"), (0.84, "checks / missing")]
    for x, t in hdr: ax.text(x, len(kpis) + 0.35, t, fontsize=9, color=INK2, weight="bold")
    for i, (kid, d) in enumerate(kpis.items()):
        y = len(kpis) - i - 0.4
        sub = kid in ("7A", "7B")
        ax.text(0.03 if sub else 0.0, y, f"{kid}  {d['name']}", fontsize=8.5 if sub else 9.5, va="center", color=INK2 if sub else INK)
        s = d["status"]
        ax.add_patch(Rectangle((0.47, y - 0.34), 0.17, 0.68, color=STATUS_COL[s], ec="none"))
        ax.text(0.475, y, f"{STATUS_ICON[s]}  {s}", fontsize=9, va="center", color="#0b0b0b" if s in ("WATCH",) else "#ffffff", weight="bold")
        src = d["status_source"]
        lab = "analyst" if src == "analyst" else "from 7A / 7B" if src.startswith("derived") else ("automatic rule" if s in ("PASS", "FAIL") else "pending analyst" if not d["fully_objective"] else "evidence missing")
        if "refused" in src: lab = "analyst PASS refused"
        ax.text(0.66, y, lab, fontsize=8.5, va="center", color=INK2)
        nc = Counter(c["status"] for c in d["checks"])
        ax.text(0.84, y, (" ".join(f"{STATUS_ICON[s_]}{n}" for s_, n in nc.items()) or "-") + f"   missing {len(d['missing_inputs'])}", fontsize=8.5, va="center", color=INK2)
        ax.plot([0, 1], [y - 0.5, y - 0.5], color=GRID, lw=0.6)
    leg = [Patch(color=STATUS_COL[s], label=f"{STATUS_ICON[s]} {s}") for s in STATUSES]
    ax.legend(handles=leg, loc="lower center", ncol=4, frameon=False, fontsize=8.5, bbox_to_anchor=(0.5, -0.04))
    fig.tight_layout()
    return fig


def chart_progression_mode(M, m):
    fig, ax = plt.subplots(figsize=(9, 4.8))
    cs = by_mode(M, m)
    if not cs: nodata(ax, f"{m}: no course in this campaign"); return fig
    xs = list(range(1, 11))
    for c in cs:
        ys = [c["castles"][k].get("total") if c["castles"][k].get("complete") else None for k in xs]
        ax.plot(xs, [y if y is not None else float("nan") for y in ys], color=MODE_COL[m], lw=1, alpha=0.55, marker=MODE_MARK[m], ms=3)
        last = max((i for i, y in enumerate(ys) if y is not None), default=None)
        if last is not None: ax.annotate(f"seed {c['seed']}", (xs[last], ys[last]), xytext=(4, 0), textcoords="offset points", fontsize=7, color=INK2, va="center")
    mn = [mc_get(M, m, k, "total_mean") for k in xs]; lo = [mc_get(M, m, k, "total_min") for k in xs]; hi = [mc_get(M, m, k, "total_max") for k in xs]
    nan = lambda v: [x if x is not None else float("nan") for x in v]
    ax.fill_between(xs, nan(lo), nan(hi), color=MODE_COL[m], alpha=0.13, lw=0)
    ax.plot(xs, nan(mn), color=MODE_COL[m], lw=2.6, marker=MODE_MARK[m], ms=7, label=f"{m} mean (n={len(cs)})")
    tg = [mc_get(M, m, k, "ctl_target_total_mean") for k in xs]
    extra = [Patch(color=MODE_COL[m], alpha=0.13, label="min-max over seeds"), Line2D([], [], color=MODE_COL[m], lw=1, alpha=0.55, label="individual seeds")]
    if any(t is not None for t in tg):
        ax.plot(xs, nan(tg), color=INK, lw=1.6, ls="--", label="controller target (castle_control.csv)")
    else:
        ax.text(0.99, 0.02, "target: not recorded in this campaign (castle_control.csv target_total)", transform=ax.transAxes, ha="right", fontsize=7.5, color=INK2)
    h, l = ax.get_legend_handles_labels()
    ax.legend(handles=h + extra, frameon=False, fontsize=8, loc="upper left")
    ax.set_xticks(xs); ax.set_xlabel("castle (500 m each)"); ax.set_ylabel("difficulty, total reference points per castle")
    ax.set_title(f"{m}: ten-castle progression", loc="left")
    fig.tight_layout(); return fig


def chart_progression_combined(M):
    fig, ax = plt.subplots(figsize=(9, 4.8))
    xs = list(range(1, 11)); any_ = False
    for m in modes_found(M):
        mn = [mc_get(M, m, k, "total_mean") for k in xs]; lo = [mc_get(M, m, k, "total_min") for k in xs]; hi = [mc_get(M, m, k, "total_max") for k in xs]
        nan = lambda v: [x if x is not None else float("nan") for x in v]
        ax.fill_between(xs, nan(lo), nan(hi), color=MODE_COL[m], alpha=0.12, lw=0)
        ax.plot(xs, nan(mn), color=MODE_COL[m], marker=MODE_MARK[m], ms=6, label=f"{m} mean, band min-max (n={len(by_mode(M, m))})"); any_ = True
        tg = [mc_get(M, m, k, "ctl_target_total_mean") for k in xs]
        if any(t is not None for t in tg): ax.plot(xs, nan(tg), color=MODE_COL[m], lw=1.3, ls="--", label=f"{m} target")
    if not any_: nodata(ax, "no course data"); return fig
    ax.set_xticks(xs); ax.set_xlabel("castle (500 m each)"); ax.set_ylabel("total reference points per castle")
    ax.set_title("Difficulty progression, NORMAL / PUNISHER / NGH (targets dashed where recorded)", loc="left")
    ax.legend(frameon=False, fontsize=8); fig.tight_layout(); return fig


def chart_castle_change(M):
    fig, ax = plt.subplots(figsize=(9, 4.6))
    ms = modes_found(M); w = 0.8 / max(1, len(ms))
    for j, m in enumerate(ms):
        xs = list(range(2, 11)); ys = [mc_get(M, m, k, "growth_pct_mean") for k in xs]
        ax.bar([x - 0.4 + w * (j + 0.5) for x in xs], [100 * y if y is not None else 0 for y in ys], w * 0.92, color=MODE_COL[m], label=f"{m} mean", edgecolor=SURF, lw=1)
        for c in by_mode(M, m):
            pts = [(x - 0.4 + w * (j + 0.5), 100 * c["castles"][x]["growth_pct"]) for x in xs if c["castles"][x].get("growth_pct") is not None]
            if pts: ax.scatter(*zip(*pts), s=10, color=INK, zorder=3, marker=MODE_MARK[m], alpha=0.6)
    ax.axhline(0, color=INK2, lw=1)
    ax.set_xticks(range(2, 11)); ax.set_xticklabels([f"{k - 1}->{k}" for k in range(2, 11)])
    ax.set_xlabel("castle step"); ax.set_ylabel("change in total, % (bars: mode mean of seeds; dots: each seed)")
    ax.set_title("Castle-to-castle change (negative = dip)", loc="left"); ax.legend(frameon=False, fontsize=8)
    fig.tight_layout(); return fig


def chart_composition(M):
    ms = modes_found(M) or ["NORMAL"]
    fig, axs = plt.subplots(1, len(ms), figsize=(4.2 * len(ms) + 1, 4.8), sharey=True, squeeze=False)
    for ax, m in zip(axs[0], ms):
        if not by_mode(M, m): nodata(ax, "no data"); continue
        xs = list(range(1, 11)); base = [0.0] * 10
        for lane in ("terrain", "hazards", "enemies", "interaction"):
            v = [mc_get(M, m, k, lane + "_mean") or 0 for k in xs]
            ax.bar(xs, v, 0.78, bottom=base, color=LANE_COL[lane], edgecolor=SURF, lw=1.2, label=lane)
            base = [a + b for a, b in zip(base, v)]
        mit = [-(mc_get(M, m, k, "mitigation_mean") or 0) for k in xs]
        ax.bar(xs, mit, 0.78, color=LANE_COL["mitigation"], hatch="///", edgecolor=SURF, lw=1.2, label="mitigation (subtracted)")
        ax.axhline(0, color=INK2, lw=1)
        ax.set_title(m, loc="left"); ax.set_xticks(xs); ax.set_xlabel("castle")
    axs[0][0].set_ylabel("lane points per castle, mode mean")
    h, l = axs[0][0].get_legend_handles_labels()
    fig.suptitle("Difficulty composition: terrain, hazards, enemies, interaction (stacked); mitigation below zero", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(rect=(0, 0.08, 1, 1)); fig.legend(h, l, loc="lower center", ncol=5, frameon=False, fontsize=8.5); return fig


def chart_target_vs_actual(M):
    fig, ax = plt.subplots(figsize=(9, 4.6))
    xs = list(range(1, 11)); drawn = False
    for m in modes_found(M):
        tg = [mc_get(M, m, k, "ctl_target_total_mean") for k in xs]
        if not any(t is not None for t in tg): continue
        ac = [mc_get(M, m, k, "total_mean") for k in xs]
        nan = lambda v: [x if x is not None else float("nan") for x in v]
        ax.plot(xs, nan(ac), color=MODE_COL[m], marker=MODE_MARK[m], label=f"{m} actual (measured)")
        ax.plot(xs, nan(tg), color=MODE_COL[m], ls="--", lw=1.5, label=f"{m} target")
        drawn = True
    if not drawn: nodata(ax, "Target vs actual: no controller target in this campaign\n(castle_control.csv target_total not present) - " + NM); return fig
    ax.set_xticks(xs); ax.set_xlabel("castle"); ax.set_ylabel("total reference points per castle")
    ax.set_title("Target (dashed) vs actual (solid)", loc="left"); ax.legend(frameon=False, fontsize=8, ncol=2); fig.tight_layout(); return fig


def chart_separation(M):
    fig, ax = plt.subplots(figsize=(9, 4.6))
    sep = separation(M); drawn = False
    cols = {"PUNISHER over NORMAL": MODE_COL["PUNISHER"], "NGH over PUNISHER": MODE_COL["NGH"]}
    marks = {"PUNISHER over NORMAL": "s", "NGH over PUNISHER": "^"}
    for p, s in sep.items():
        if not s["per_castle"]: continue
        ys = [100 * y if y is not None else float("nan") for y in s["per_castle"]]
        lab = f"{p}: erosion {100 * s['erosion']:.1f}%" if s["erosion"] is not None else f"{p}: erosion -"
        ax.plot(range(1, 11), ys, color=cols[p], marker=marks[p], label=lab); drawn = True
        if s["adv1"] is not None and s["adv1"] > 0:
            ax.plot([1, 10], [100 * 0.75 * s["adv1"]] * 2, color=cols[p], ls="--", lw=1.2, label=f"{p}: 25% erosion limit (75% of castle-1 advantage)")
    if not drawn: nodata(ax, "inter-difficulty separation: two adjacent modes with castles 1 and 10 needed"); return fig
    ax.axhline(0, color=INK2, lw=1)
    ax.set_xticks(range(1, 11)); ax.set_xlabel("castle"); ax.set_ylabel("advantage of the harder mode, % of the easier mode's total")
    ax.set_title("Inter-difficulty separation (limit dashed)", loc="left"); ax.legend(frameon=False, fontsize=8); fig.tight_layout(); return fig


def chart_density(M, lane, title, ylabel):
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(12, 4.6), gridspec_kw={"width_ratios": [1.25, 1]})
    key = "hazards_placed" if lane == "HAZARDS" else "enemies_placed"
    xs = list(range(1, 11)); drawn = False
    for m in modes_found(M):
        v = [mc_get(M, m, k, key + "_mean") for k in xs]
        if any(x is not None for x in v):
            lo = [mc_get(M, m, k, key + "_min") for k in xs]; hi = [mc_get(M, m, k, key + "_max") for k in xs]
            nan = lambda q: [x if x is not None else float("nan") for x in q]
            a1.fill_between(xs, nan(lo), nan(hi), color=MODE_COL[m], alpha=0.12, lw=0)
            a1.plot(xs, nan(v), color=MODE_COL[m], marker=MODE_MARK[m], label=m); drawn = True
    if not drawn: nodata(a1, f"{title}: hazards.csv not present - {NM}"); nodata(a2, ""); return fig
    a1.set_xticks(xs); a1.set_xlabel("castle (500 m)"); a1.set_ylabel(ylabel); a1.set_title(f"{title} per castle (mean, band min-max)", loc="left"); a1.legend(frameon=False, fontsize=8)
    fam = per_mode_types(M, lane)
    types = list(OrderedDict((t, 1) for v in fam.values() for t in v).keys())
    pal = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
    if len(types) > 8:
        tot = Counter(); [tot.update(v) for v in fam.values()]
        keep = [t for t, _ in tot.most_common(7)]
        for m in fam: fam[m] = OrderedDict([(t, fam[m].get(t, 0)) for t in keep] + [("other", sum(v for t, v in fam[m].items() if t not in keep))])
        types = keep + ["other"]
    ms = list(fam)
    left = [0.0] * len(ms)
    for i, t in enumerate(types):
        v = [100 * fam[m].get(t, 0) / max(1, sum(fam[m].values())) for m in ms]
        a2.barh(ms, v, left=left, color=pal[i % 8], edgecolor=SURF, lw=1.2, label=t.lower())
        for j, (l, x) in enumerate(zip(left, v)):
            if x >= 7: a2.text(l + x / 2, j, f"{x:.0f}%", ha="center", va="center", fontsize=7, color="#0b0b0b" if pal[i % 8] in ("#eda100", "#e87ba4", "#1baf7a") else "#ffffff")
        left = [a + b for a, b in zip(left, v)]
    a2.invert_yaxis(); a2.set_xlabel("share of placed elements, %"); a2.set_title("type mix per mode", loc="left")
    a2.legend(frameon=False, fontsize=7, loc="lower center", bbox_to_anchor=(0.5, -0.42), ncol=4)
    fig.tight_layout(); return fig


def chart_avalanches(M):
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(12, 4.6))
    ms = modes_found(M); w = 0.8 / max(1, len(ms)); drawn = False
    for j, m in enumerate(ms):
        v = [mc_get(M, m, k, "avalanches_mean") for k in range(1, 11)]
        if any(x is not None for x in v):
            a1.bar([k - 0.4 + w * (j + 0.5) for k in range(1, 11)], [x or 0 for x in v], w * 0.92, color=MODE_COL[m], edgecolor=SURF, label=m); drawn = True
    if not drawn: nodata(a1, f"avalanches: encounters.csv not present - {NM}")
    else:
        a1.axvspan(8.5, 10.5, color=GRID, alpha=0.5, lw=0, zorder=0); a1.text(9.5, a1.get_ylim()[1] * 0.95, "NORMAL allowed\n(castles 9-10)", ha="center", va="top", fontsize=7, color=INK2)
        a1.set_xticks(range(1, 11)); a1.set_xlabel("castle"); a1.set_ylabel("avalanche triggers per course (mean)"); a1.set_title("Avalanche triggers per castle", loc="left"); a1.legend(frameon=False, fontsize=8)
    bl = {m: Counter(e["blocks"] for c in by_mode(M, m) for e in c["avalanche_events"] if e["blocks"] is not None) for m in ms}
    sizes = list(range(1, max((s for v in bl.values() for s in v), default=0) + 1))
    if not sizes: nodata(a2, "blocks per avalanche: no avalanche events in hazards.csv")
    else:
        for j, m in enumerate(ms):
            a2.bar([i - 0.4 + w * (j + 0.5) for i in range(len(sizes))], [bl[m].get(s, 0) for s in sizes], w * 0.92, color=MODE_COL[m], edgecolor=SURF, label=m)
        a2.axvline(1.5, color=INK, ls="--", lw=1.2, label="NORMAL limit: at most 2 blocks (left of the line)")
        a2.set_xticks(range(len(sizes))); a2.set_xticklabels(sizes); a2.set_xlabel("blocks per avalanche (hazards.csv len)"); a2.set_ylabel("avalanches (all courses)")
        a2.set_title("Blocks per event", loc="left"); a2.legend(frameon=False, fontsize=8)
    fig.tight_layout(); return fig


def chart_enforcers(M):
    ms = modes_found(M) or ["NORMAL"]
    fig = plt.figure(figsize=(13, 7.6), layout="constrained")
    gs = fig.add_gridspec(2, len(ms))
    any_enc = any_have(M, "encounters.csv")
    for j, m in enumerate(ms):
        ax = fig.add_subplot(gs[0, j])
        if not any_enc: nodata(ax, NM); continue
        base = [0.0] * 10
        for col, tier, color, hatch in TIERS:
            v = [mc_get(M, m, k, col + "_mean") or 0 for k in range(1, 11)]
            ax.bar(range(1, 11), v, 0.78, bottom=base, color=color, hatch=hatch, edgecolor=SURF, lw=1, label=tier)
            base = [a + b for a, b in zip(base, v)]
        ax.set_title(f"{m}: Enforcers placed by tier", loc="left", fontsize=10); ax.set_xticks(range(1, 11)); ax.set_xlabel("castle")
        if j == 0: ax.set_ylabel("placed per course (mean)")
        if j == len(ms) - 1: ax.legend(frameon=False, fontsize=8)
        if sum(base) == 0: ax.set_ylim(0, 1); ax.text(5.5, 0.5, "none placed", ha="center", fontsize=9, color=INK2)
    ax = fig.add_subplot(gs[1, :])
    cols = [c for c in ENF_COUNTERS if all_have(M, "encounters.csv", c)]
    if not cols: nodata(ax, f"Enforcer behaviour counters: {NM}")
    else:
        w = 0.8 / max(1, len(ms))
        for j, m in enumerate(ms):
            v = [mean([c["summary"].get(col) for c in by_mode(M, m)]) or 0 for col in cols]
            ax.bar([i - 0.4 + w * (j + 0.5) for i in range(len(cols))], v, w * 0.92, color=MODE_COL[m], edgecolor=SURF, label=m)
        ax.set_xticks(range(len(cols))); ax.set_xticklabels([ENF_COUNTERS[c] for c in cols], rotation=20, ha="right", fontsize=8)
        ax.set_ylabel("per course (mean)"); ax.legend(frameon=False, fontsize=8)
        ax.set_title("Enforcer behaviour: Sim counters during the solver's witness climb, all tiers combined (tier not recorded per counter).\n"
                     "No disengagement counter exists: retreats are the Sim's own count; nothing is inferred from absence.", loc="left", fontsize=9)
    return fig


def mode_kind_table(M, field):
    t = OrderedDict()
    for m in modes_found(M):
        cnt = Counter()
        for c in by_mode(M, m):
            for x in c["modules"]:
                if x.get(field) is not None: cnt[x["kind"]] += x[field] / len(by_mode(M, m))
        t[m] = cnt
    return t


def chart_module_distribution(M):
    fig, ax = plt.subplots(figsize=(11, 6.2))
    t = mode_kind_table(M, "accepted_in_course")
    tot = Counter(); [tot.update(v) for v in t.values()]
    kinds = [k for k, v in tot.most_common(22) if v > 0]
    if not kinds: nodata(ax, f"module distribution: module_inventory.csv not present - {NM}"); return fig
    ms = list(t); h = 0.8 / max(1, len(ms))
    for j, m in enumerate(ms):
        ax.barh([i - 0.4 + h * (j + 0.5) for i in range(len(kinds))], [t[m].get(k, 0) for k in kinds], h * 0.92, color=MODE_COL[m], edgecolor=SURF, label=m)
    ax.set_yticks(range(len(kinds))); ax.set_yticklabels(kinds, fontsize=8); ax.invert_yaxis()
    ax.set_xlabel("modules accepted in the final course, per course (mean)"); ax.set_title("Module distribution (module_inventory.csv accepted_in_course)", loc="left")
    ax.legend(frameon=False, fontsize=8); fig.tight_layout(); return fig


def chart_module_acceptance(M):
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(13, 6.2), gridspec_kw={"width_ratios": [1.3, 1]})
    tr, ac = mode_kind_table(M, "tried"), mode_kind_table(M, "accepted_in_course")
    tot = Counter(); [tot.update(v) for v in tr.values()]
    kinds = [k for k, v in tot.most_common(20) if v > 0]
    ms = list(tr); h = 0.8 / max(1, len(ms))
    if not kinds: nodata(a1, f"module acceptance: {NM}")
    else:
        for j, m in enumerate(ms):
            a1.barh([i - 0.4 + h * (j + 0.5) for i in range(len(kinds))], [100 * ac[m].get(k, 0) / tr[m][k] if tr[m].get(k) else 0 for k in kinds], h * 0.92,
                    color=MODE_COL[m], edgecolor=SURF, label=m)
        a1.set_yticks(range(len(kinds))); a1.set_yticklabels(kinds, fontsize=8); a1.invert_yaxis(); a1.set_xlabel("accepted in course / tried, %")
        a1.set_title("Acceptance by module kind (20 most tried)", loc="left"); a1.legend(frameon=False, fontsize=8)
    reasons = REFUSALS + (["refused_early"] if all_have(M, "modules.csv", "refused_early") else [])
    pal = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6"]
    vals = {m: [ssum(x.get(r) for c in by_mode(M, m) for x in c["modules"]) or 0 for r in reasons] for m in ms}
    if not any(sum(v) for v in vals.values()): nodata(a2, f"refusal reasons: modules.csv not present - {NM}")
    else:
        left = [0.0] * len(ms)
        for i, r in enumerate(reasons):
            v = [vals[m][i] / len(by_mode(M, m)) for m in ms]
            a2.barh(ms, v, left=left, color=pal[i % 7], edgecolor=SURF, lw=1.2, label=r.replace("refused_", ""))
            left = [a + b for a, b in zip(left, v)]
        a2.invert_yaxis(); a2.set_xlabel("refusal-check counts per course (sum of modules.csv refused_* over refused attempts)")
        a2.set_title("Refusal reasons (a refused attempt can count several checks)", loc="left", fontsize=9.5)
        a2.legend(frameon=False, fontsize=7.5, loc="lower center", bbox_to_anchor=(0.5, -0.3), ncol=4)
    fig.tight_layout(); return fig


def chart_variety(M):
    fig, axs = plt.subplots(1, 3, figsize=(14, 4.4))
    specs = [("unique_mechanics", "unique mechanics per castle"), ("signature_entropy", "signature entropy, bits"), ("longest_signature_run", "longest same-signature run, links")]
    for ax, (f, lab) in zip(axs, specs):
        drawn = False
        for m in modes_found(M):
            v = [mc_get(M, m, k, f + "_mean") for k in range(1, 11)]
            if any(x is not None for x in v):
                nan = lambda q: [x if x is not None else float("nan") for x in q]
                ax.fill_between(range(1, 11), nan([mc_get(M, m, k, f + "_min") for k in range(1, 11)]), nan([mc_get(M, m, k, f + "_max") for k in range(1, 11)]), color=MODE_COL[m], alpha=0.12, lw=0)
                ax.plot(range(1, 11), nan(v), color=MODE_COL[m], marker=MODE_MARK[m], label=m); drawn = True
        if not drawn: nodata(ax, f"{lab}: variety_castles.csv not present - {NM}"); continue
        ax.set_xticks(range(1, 11)); ax.set_xlabel("castle"); ax.set_title(lab, loc="left", fontsize=10)
    axs[0].legend(frameon=False, fontsize=8)
    fig.suptitle("Route variety per castle (variety_castles.csv; mean, band min-max over seeds)", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(); return fig


def chart_solver(M):
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(12, 4.6))
    ms = modes_found(M)
    if all_have(M, "solver.csv", "verdict"):
        cats = VERDICTS + ["NONE"]
        pal = {"PROVEN_SOLVABLE": STATUS_COL["PASS"], "PROVEN_UNSOLVABLE": STATUS_COL["FAIL"], "UNKNOWN": STATUS_COL["WATCH"], "FAILED_VALIDATION": "#ec835a", "NONE": MUTED}
        base = [0] * len(ms)
        for v in cats:
            n = [sum((c["summary"]["verdicts"] or {}).get(v, 0) for c in by_mode(M, m)) for m in ms]
            if sum(n): a1.bar(ms, n, bottom=base, color=pal[v], edgecolor=SURF, label=v); base = [a + b for a, b in zip(base, n)]
        a1.set_title("Solver verdicts per mode (all slices)", loc="left")
    elif any_have(M, "solver.csv"):
        cats = sorted({k for c in present(M) for k in (c["summary"]["fail_classes"] or {})})
        pal = ["#0ca30c", "#d03b3b", "#fab219", "#ec835a", MUTED, "#6250d6"]
        base = [0] * len(ms)
        for i, v in enumerate(cats):
            n = [sum((c["summary"]["fail_classes"] or {}).get(v, 0) for c in by_mode(M, m)) for m in ms]
            a1.bar(ms, n, bottom=base, color=pal[i % 6], edgecolor=SURF, label=v); base = [a + b for a, b in zip(base, n)]
        a1.set_title("Solver outcome classes (legacy fail_class;\nno proof verdict column in this campaign)", loc="left", fontsize=9.5)
    else: nodata(a1, f"solver outcomes: {NM}")
    if a1.has_data(): a1.set_ylabel("slices"); a1.legend(frameon=False, fontsize=7.5)
    cols = [c for c in ("witness_setbacks", "resyncs", "fallback_moves", "waits", "unclear_moves") if all_have(M, "solver.csv", c)]
    ac = sorted({h for c in present(M) for h in c["summary"]["assist_columns"]})
    if not cols and not ac:
        nodata(a2, "witness audit counters (setbacks, resyncs, fallback moves, waits, unclear moves)\nand clean vs collision-assisted proofs: " + NM)
    else:
        allc = cols + ac; w = 0.8 / max(1, len(ms))
        for j, m in enumerate(ms):
            v = [mean([(c["summary"].get(x) if x in cols else ssum(r.get(x) for r in c["slices"])) for c in by_mode(M, m)]) or 0 for x in allc]
            a2.bar([i - 0.4 + w * (j + 0.5) for i in range(len(allc))], v, w * 0.92, color=MODE_COL[m], edgecolor=SURF, label=m)
        a2.set_xticks(range(len(allc))); a2.set_xticklabels(allc, rotation=15, fontsize=8); a2.set_ylabel("per course (mean)"); a2.legend(frameon=False, fontsize=8)
        a2.set_title("How the witness got through" + ("" if ac else "\n(no collision-assist column: clean vs assisted " + "not measured)"), loc="left", fontsize=9.5)
    fig.tight_layout(); return fig


def chart_performance(M):
    fig, axs = plt.subplots(1, 3, figsize=(15, 4.6))
    cs = present(M)
    if not cs: [nodata(a, "no courses") for a in axs]; return fig
    names = [c["run"] for c in cs]
    for ax, key, lab in ((axs[0], "gen_s", "generation s per course"), (axs[1], "s_per_m", "generation s per metre")):
        v = [c["summary"].get(key) for c in cs]
        ax.bar(range(len(cs)), [x or 0 for x in v], color=[MODE_COL.get(c["mode"], MUTED) for c in cs], edgecolor=SURF)
        for i, x in enumerate(v):
            if x is None: ax.text(i, 0, "-", ha="center", va="bottom")
        ax.set_xticks(range(len(cs))); ax.set_xticklabels(names, rotation=40, ha="right", fontsize=7.5); ax.set_title(lab, loc="left", fontsize=10)
    for m in modes_found(M):
        v = [mc_get(M, m, k, "gen_wall_s_mean") for k in range(1, 11)]
        axs[2].plot(range(1, 11), [x if x is not None else float("nan") for x in v], color=MODE_COL[m], marker=MODE_MARK[m], label=m)
    axs[2].set_xticks(range(1, 11)); axs[2].set_xlabel("castle"); axs[2].set_ylabel("s"); axs[2].set_title("generation s per castle (mode mean)", loc="left", fontsize=10)
    if axs[2].lines: mode_legend(axs[2])          # bar colours follow the same modes
    fig.suptitle("Generation performance (run.json generation_seconds; chunks.csv gen_wall_s)", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(); return fig


def chart_slowest(M):
    fig, ax = plt.subplots(figsize=(10, 5.6))
    sl = sorted((r for c in present(M) for r in c["slices"] if r["gen_wall_s"] is not None), key=lambda r: -r["gen_wall_s"])[:15]
    if not sl: nodata(ax, f"slice timings: chunks.csv not present - {NM}"); return fig
    labels = [f"{r['run']} slice {r['slice']} (castle {r['castle']})" for r in sl]
    fa = [r["failed_attempts_s"] or 0 for r in sl]; tot = [r["gen_wall_s"] for r in sl]
    ax.barh(range(len(sl)), [t - f for t, f in zip(tot, fa)], color=[MODE_COL.get(r["mode"], MUTED) for r in sl], edgecolor=SURF, label="final attempt and other")
    ax.barh(range(len(sl)), fa, left=[t - f for t, f in zip(tot, fa)], color=MUTED, hatch="///", edgecolor=SURF, label="failed (retried) attempts")
    for i, t in enumerate(tot): ax.text(t, i, f" {t:.1f} s", va="center", fontsize=7.5, color=INK2)
    ax.set_yticks(range(len(sl))); ax.set_yticklabels(labels, fontsize=7.5); ax.invert_yaxis(); ax.set_xlabel("generation wall time, s")
    ax.set_title("Slowest 15 slices (bar colour = mode)", loc="left"); ax.legend(frameon=False, fontsize=8, loc="lower right"); fig.tight_layout(); return fig


def chart_controller(M):
    lanes = ["terrain", "hazards", "enemies", "interaction"]
    ok = all_have(M, "castle_control.csv", "req_terrain")
    ms = modes_found(M) or ["NORMAL"]
    fig, axs = plt.subplots(1, len(ms), figsize=(4.4 * len(ms) + 1, 4.6), sharey=True, squeeze=False)
    if not ok:
        for ax in axs[0]: ax.axis("off")
        axs[0][0].text(0, 0.5, "Requested vs applied controller factors and saturation:\ncastle_control.csv req_* / saturated columns not present in this campaign\n- " + NM,
                       fontsize=11, color=INK2, transform=axs[0][0].transAxes)
        return fig
    for ax, m in zip(axs[0], ms):
        for lane in lanes:
            req = [mean([c["castles"][k].get("ctl_req_" + lane) for c in by_mode(M, m)]) for k in range(1, 11)]
            app = [mean([c["castles"][k].get("ctl_factor_" + lane) for c in by_mode(M, m)]) for k in range(1, 11)]
            nan = lambda q: [x if x is not None else float("nan") for x in q]
            ax.plot(range(1, 11), nan(app), color=LANE_COL[lane], marker="o", ms=4, label=f"{lane} applied")
            ax.plot(range(1, 11), nan(req), color=LANE_COL[lane], ls=":", marker="o", mfc="none", ms=5, label=f"{lane} requested")
            sat = [k for k in range(1, 11) if any(lane[0].upper() + "-" in (c["castles"][k].get("ctl_saturated") or "") for c in by_mode(M, m))]
            if sat: ax.scatter(sat, [app[k - 1] if app[k - 1] is not None else 1 for k in sat], marker="x", s=60, color=INK, zorder=4)
        ax.set_title(m, loc="left"); ax.set_xticks(range(1, 11)); ax.set_xlabel("castle")
    axs[0][0].set_ylabel("controller factor (mean over seeds)")
    axs[0][-1].legend(handles=axs[0][-1].get_lines()[:8] + [Line2D([], [], marker="x", ls="none", color=INK, label="saturated (any seed)")], frameon=False, fontsize=7)
    fig.suptitle("Requested (dotted, hollow) vs applied (solid) controller factors; x = factor on a bound", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(); return fig


def chart_mechanics(M):
    ms = modes_found(M) or ["NORMAL"]
    fig, axs = plt.subplots(1, len(ms), figsize=(4.6 * len(ms) + 1, 4.8), sharey=True, squeeze=False)
    if not any(c["mechanics"] for c in present(M)):
        nodata(axs[0][0], f"mechanic utilization: elements.csv / links.csv not present - {NM}"); return fig
    specs = [("placed", "#2a78d6", "", "placed"), ("main_route", "#1baf7a", "", "on the main route"), ("route_links", "#eda100", "", "route links touching it"),
             ("solver_traversed", "#6250d6", "//", "of those, solver-traversed")]
    for ax, m in zip(axs[0], ms):
        n = max(1, len(by_mode(M, m))); w = 0.2
        for j, (f, col, hat, lab) in enumerate(specs):
            v = [sum(r[f] for c in by_mode(M, m) for r in c["mechanics"] if r["mechanic"] == t) / n for t in MECHANICS]
            ax.bar([i - 0.4 + w * (j + 0.5) for i in range(len(MECHANICS))], v, w * 0.92, color=col, hatch=hat, edgecolor=SURF, label=lab)
        ax.set_xticks(range(len(MECHANICS))); ax.set_xticklabels([t.lower() for t in MECHANICS], rotation=40, ha="right", fontsize=8); ax.set_title(m, loc="left")
        ax.set_yscale("symlog", linthresh=10)
    axs[0][0].set_ylabel("per course (mean, symlog)"); axs[0][-1].legend(frameon=False, fontsize=7.5)
    fig.suptitle("Advanced movement utilization: placed, on the main route, route links, solver-traversed", x=0.01, ha="left", fontsize=11)
    fig.tight_layout(); return fig


def make_charts(M, kpis, pkg, title):
    d = os.path.join(pkg, "charts"); os.makedirs(d, exist_ok=True)
    figs = OrderedDict()
    figs["01_kpi_scorecard"] = chart_scorecard(kpis, title)
    for m in MODES: figs[f"02_progression_{m}"] = chart_progression_mode(M, m)
    figs["03_progression_combined"] = chart_progression_combined(M)
    figs["04_castle_to_castle_change"] = chart_castle_change(M)
    figs["05_composition"] = chart_composition(M)
    figs["06_target_vs_actual"] = chart_target_vs_actual(M)
    figs["07_inter_difficulty_separation"] = chart_separation(M)
    figs["08_hazard_density"] = chart_density(M, "HAZARDS", "Hazards placed", "hazards per castle (500 m)")
    figs["09_enemy_density"] = chart_density(M, "ENEMIES", "Enemies placed", "enemies per castle (500 m)")
    figs["10_avalanches"] = chart_avalanches(M)
    figs["11_enforcers"] = chart_enforcers(M)
    figs["12_module_distribution"] = chart_module_distribution(M)
    figs["13_module_acceptance_refusals"] = chart_module_acceptance(M)
    figs["14_route_variety"] = chart_variety(M)
    figs["15_solver_outcomes"] = chart_solver(M)
    figs["16_generation_performance"] = chart_performance(M)
    figs["17_slowest_slices"] = chart_slowest(M)
    figs["18_controller_factors"] = chart_controller(M)
    figs["19_mechanic_utilization"] = chart_mechanics(M)
    for name, fig in figs.items():
        fig.savefig(os.path.join(d, name + ".png"), dpi=130)
        fig.savefig(os.path.join(d, name + ".svg"))
    return figs


# ---------------------------------------------------------------- reports (one model, rendered to Markdown and PDF)
def report_sections(M, kpis, meta, ov, exceptions):
    cs = M["courses"]
    S = []
    S.append(("Methodology", [("p", "Source: the campaign's per-course LaneReport folders (one job per difficulty and seed). Every number below is computed "
                                   "from those files by tools/ci/cert_package.py; the evidence column names the file and columns. Lanes are measured per 25 m window "
                                   "and summed per castle (500 m); a castle counts only when complete. Mode figures are means over the mode's seeds, with the "
                                   "min-max band over seeds."),
                              ("p", "Statuses: PASS / FAIL / WATCH / NOT VERIFIED (docs/KPI_FRAMEWORK.md). An automatic status is set only where the framework gives "
                                    "an objective rule: KPI 2 (completion), the difficulty ordering and separation-erosion checks (7B), NORMAL Enforcers (12) and NORMAL "
                                    "avalanches (11), solvability (10), identity (1), full CI (14) and platform evidence (15). An objective check can establish a FAIL; "
                                    "a KPI passes automatically only when its rule decides the whole KPI and every input is present. Everything that needs judgement is "
                                    "NOT VERIFIED until an analyst evaluation (evaluation_overrides.json) assigns it; an analyst PASS is refused where inputs are missing."),
                              ("p", f"Separation erosion = (A1 - A10) / A1, where Ak is the harder mode's percentage advantage over the easier mode at castle k "
                                    f"(mode-mean totals). Growth = castle k over castle k-1. Spike windows = windows above q3 + 1.5 IQR of their castle (LaneReport).")]))
    rows = [[c["run"], c["mode"], c["seed"], "yes" if c["present"] else "MISSING", fmt(c["summary"]["reached_m"], 0), c["summary"]["castles_complete"], fmt(c["summary"]["slices"]),
             str(c["summary"]["rules_version"] or "-"), str(c["summary"]["commit"] or "-")[:12], fmt(c["summary"]["gen_s"], 0), fmt(c["summary"]["solve_s"], 0),
             str(c["summary"]["gate_passes"]) if c["summary"]["gate_passes"] is not None else "-"] for c in cs]
    S.append(("Seed panel", [("p", f"Expected: {meta['expected']['courses'] or 'not supplied'}. Found: {len(present(M))} course folders."),
                             ("table", ["run", "mode", "seed", "present", "reached m", "castles", "slices", "rules", "commit", "gen s", "solve s", "gate"], rows)]))
    sc = [[kid, d["name"], f"{STATUS_ICON[d['status']]} {d['status']}", d["status_source"], d["automatic_status"], len(d["missing_inputs"])] for kid, d in kpis.items()]
    S.append(("KPI scorecard", [("table", ["KPI", "name", "status", "decided by", "automatic", "missing inputs"], sc), ("img", "01_kpi_scorecard")]))
    for kid, d in kpis.items():
        blocks = [("p", f"Question: {d['question']}  Status: {d['status']} ({d['status_source']}; automatic {d['automatic_status']}). {d['automatic_rule']}.")]
        if d["checks"]: blocks.append(("table", ["check", "rule", "measured", "status", "evidence"],
                                       [[c["name"], c["rule"], c["measured"], c["status"], (c["evidence"]["file"] or "") + (" [" + ", ".join(c["evidence"]["columns"]) + "]" if c["evidence"]["columns"] else "")] for c in d["checks"]]))
        if d["measurements"]: blocks.append(("table", ["measurement", "value", "evidence", "worst case"],
                                             [[mm["name"] + (f" ({mm['unit']})" if mm["unit"] else ""), vstr(mm["value"], mm["unit"]) if mm["measured"] else "NOT MEASURED: " + mm["note"],
                                               (mm["evidence"]["file"] or "") + (" [" + ", ".join(mm["evidence"]["columns"]) + "]" if mm["evidence"]["columns"] else ""), worst_str(mm["worst_case"])]
                                              for mm in d["measurements"]]))
        for n in d["notes"]: blocks.append(("p", n))
        blocks.append(("p", "Analyst interpretation: " + (d["interpretation"] or "analyst evaluation pending") + ("  Recommended action: " + d["recommended_action"] if d["recommended_action"] else "")))
        S.append((f"KPI {kid}: {d['name']}", blocks))
    for c in present(M):
        rows = []
        for k in range(1, 11):
            r = c["castles"][k]
            rows.append([k, "yes" if r.get("complete") else "no", fmt(r.get("terrain"), 0), fmt(r.get("hazards"), 0), fmt(r.get("enemies"), 0), fmt(r.get("interaction"), 0),
                         fmt(r.get("mitigation"), 0), fmt(r.get("total"), 0), fmt(r.get("growth_pct"), 1, pct=True, signed=True), fmt(r.get("ctl_target_total"), 0),
                         fmt(r.get("win_sd"), 1), fmt(r.get("spike_windows")), fmt(r.get("hazards_placed")), fmt(r.get("enemies_placed")), fmt(r.get("avalanches"), 0),
                         "/".join(fmt(r.get(t[0]), 0) for t in TIERS), fmt(r.get("unique_mechanics"), 0), fmt(r.get("gen_wall_s"), 0)])
        S.append((f"Castle table: {c['run']}", [("table", ["castle", "complete", "terrain", "hazards", "enemies", "inter.", "mitig.", "total", "growth", "target", "window sd", "spikes",
                                                           "haz. placed", "enem. placed", "avl", "enf Y/O/R", "uniq. mech", "gen s"], rows)]))
    chart_sec = [("Generator", ["12_module_distribution", "13_module_acceptance_refusals"]), ("Difficulty progression", ["02_progression_NORMAL", "02_progression_PUNISHER", "02_progression_NGH",
                 "03_progression_combined", "04_castle_to_castle_change", "05_composition", "06_target_vs_actual", "07_inter_difficulty_separation", "18_controller_factors"]),
                 ("Hazards and enemies", ["08_hazard_density", "09_enemy_density"]), ("Avalanches", ["10_avalanches"]), ("Enforcers", ["11_enforcers"]),
                 ("Variety and movement", ["14_route_variety", "19_mechanic_utilization"]), ("Solver", ["15_solver_outcomes"]), ("Performance", ["16_generation_performance", "17_slowest_slices"])]
    for t, imgs in chart_sec: S.append((f"Charts: {t}", [("img", i) for i in imgs]))
    S.append(("Exceptions", [("list", exceptions or ["none"])]))
    S.append(("Limits of this package", [("list", meta_limits(M))]))
    return S


def meta_limits(M):
    lim = []
    for c in present(M):
        if c["summary"]["missing_files"]: lim.append(f"{c['run']}: files absent: {', '.join(c['summary']['missing_files'])}")
    missing_cols = defaultdict(set)
    for c in present(M):
        for f, cols in (("solver.csv", ["verdict", "witness_setbacks", "resyncs"]), ("castle_control.csv", ["req_terrain", "saturated", "slope_error", "modules_refused_early"]),
                        ("modules.csv", ["refused_early"]), ("encounters.csv", ["enforcers_planned", "avalanches_planned", "avl_ricochets", "enf_hops"])):
            for col in cols:
                if not has(c, f, col): missing_cols[f"{f}:{col}"].add(c["run"])
    for k, v in sorted(missing_cols.items()): lim.append(f"column {k} absent in {len(v)} course(s)")
    ml =next((c["runj"].get("measurement_limits") for c in present(M) if c["runj"].get("measurement_limits")), None)
    if ml: lim += ["LaneReport: " + x for x in ml]
    lim.append("KPI 14 (determinism, replays, saves) and KPI 15 (release, platforms) are outside the campaign; they need --evidence inputs.")
    lim.append("Enforcer pursuit distance, platforms followed, knock-offs and disengagements have no counter in the course outputs.")
    return lim


def md_render(title, meta, sections):
    L = [f"# {title}", "", f"Generated {meta['generated_utc']} from {meta['candidate']['sha'] or 'unknown commit'}; package tool tools/ci/cert_package.py.", ""]
    for t, blocks in sections:
        L += [f"## {t}", ""]
        for b in blocks:
            if b[0] == "p": L += [b[1], ""]
            elif b[0] == "list": L += [f"- {x}" for x in b[1]] + [""]
            elif b[0] == "img": L += [f"![{b[1]}](charts/{b[1]}.png)", ""]
            elif b[0] == "table":
                L.append("| " + " | ".join(str(h) for h in b[1]) + " |"); L.append("|" + "---|" * len(b[1]))
                for r in b[2]: L.append("| " + " | ".join(str(x).replace("|", "/").replace("\n", " ") for x in r) + " |")
                L.append("")
    return "\n".join(L) + "\n"


def text_lines_of(blocks, width=155):
    out = []
    for b in blocks:
        if b[0] == "p": out += textwrap.wrap(b[1], width) + [""]
        elif b[0] == "list":
            for x in b[1]: out += textwrap.wrap(x, width, initial_indent="- ", subsequent_indent="  ")
            out.append("")
        elif b[0] == "table":
            hdr, rows = [str(h) for h in b[1]], [[str(x) for x in r] for r in b[2]]
            cap = max(12, (width - 2 * len(hdr)) // max(1, len(hdr)))
            wid = [min(max([len(h)] + [len(r[i]) for r in rows]), cap if len(hdr) > 6 else 70) for i, h in enumerate(hdr)]
            if sum(wid) + 2 * len(wid) > width:          # wrap the widest columns
                while sum(wid) + 2 * len(wid) > width and max(wid) > 10: wid[wid.index(max(wid))] -= 1
            def wrow(r):
                parts = [textwrap.wrap(x, w) or [""] for x, w in zip(r, wid)]
                return ["  ".join(p[i].ljust(w) if i < len(p) else " " * w for p, w in zip(parts, wid)) for i in range(max(len(p) for p in parts))]
            out += wrow(hdr) + ["  ".join("-" * w for w in wid)]
            for r in rows: out += wrow(r)
            out.append("")
    return out


def pdf_text_pages(pdf, title, lines, per_page=64):
    for i in range(0, max(1, len(lines)), per_page):
        fig = plt.figure(figsize=(11.69, 8.27))
        fig.text(0.04, 0.955, title + (f" (continued)" if i else ""), fontsize=13, weight="bold", va="top")
        fig.text(0.04, 0.915, "\n".join(lines[i:i + per_page]), fontsize=7, family="monospace", va="top", linespacing=1.15)
        pdf.savefig(fig); plt.close(fig)


def write_pdfs(pkg, names, figs, sections, kpis, meta, ov, M):
    # executive
    with PdfPages(os.path.join(pkg, names["exec"])) as pdf:
        fig = plt.figure(figsize=(11.69, 8.27))
        fig.text(0.05, 0.9, meta["title"] + " - Executive summary", fontsize=20, weight="bold")
        ident = [f"Candidate commit: {meta['candidate']['sha'] or 'unknown'}", f"Campaign run: {meta['run']['url'] or meta['run']['id'] or 'local'}",
                 f"Courses: {len(present(M))} found / {meta['expected']['count'] or 'expected panel not supplied'}  (modes {', '.join(modes_found(M))}; seeds {', '.join(sorted({c['seed'] for c in present(M)}))})",
                 f"Rules version(s): {', '.join(meta['rules_versions']) or 'not recorded'};  fingerprint: {', '.join(meta['fingerprints']) or 'not recorded'}",
                 f"Generated: {meta['generated_utc']};  analyst evaluation: {(ov or {}).get('analyst') or 'pending'}"]
        fig.text(0.05, 0.84, "\n".join(ident), fontsize=10, va="top", linespacing=1.6)
        cnt = Counter(d["status"] for kid, d in kpis.items() if kid not in ("7A", "7B"))
        fig.text(0.05, 0.6, "Scorecard (16 KPIs): " + "   ".join(f"{STATUS_ICON[s]} {s}: {cnt.get(s, 0)}" for s in STATUSES), fontsize=12, weight="bold")
        y = 0.54
        for q in QUESTIONS + ["Is it better than the previous build?"]:
            ks = [kid for kid, d in kpis.items() if d["question"] == q and kid not in ("7A", "7B")] if q in QUESTIONS else [k for k in kpis if k not in ("7A", "7B")]
            line = ", ".join(f"{k} {kpis[k]['status']}" for k in ks) if q in QUESTIONS else "comparison with the previous build: " + ((ov or {}).get("comparison") or "analyst evaluation pending")
            fig.text(0.05, y, q, fontsize=11, weight="bold"); fig.text(0.05, y - 0.035, textwrap.fill(line, 150), fontsize=9, va="top"); y -= 0.1
        verdict = (ov or {}).get("verdict") or "analyst evaluation pending - no automatic recommendation is made"
        fig.text(0.05, 0.12, "Recommendation: " + verdict, fontsize=11, weight="bold", wrap=True)
        pdf.savefig(fig); plt.close(fig)
        pdf.savefig(figs["01_kpi_scorecard"])
        L = []
        for key, lab in (("findings", "Findings"), ("risks", "Risks"), ("recommendations", "Recommendations")):
            L += [lab, "-" * len(lab)]
            items = (ov or {}).get(key) or []
            L += [x for it in items for x in textwrap.wrap(str(it), 150, initial_indent="- ", subsequent_indent="  ")] if items else ["analyst evaluation pending"]
            L.append("")
        L += ["Automatic FAILs and missing evidence", "-" * 36]
        for kid, d in kpis.items():
            for c in d["checks"]:
                if c["status"] in ("FAIL", "NOT VERIFIED"): L += textwrap.wrap(f"KPI {kid} {c['status']}: {c['name']} - {c['measured']}", 150, initial_indent="- ", subsequent_indent="  ")
        pdf_text_pages(pdf, "Findings, risks and recommendations", L)
        for n in ("03_progression_combined", "07_inter_difficulty_separation", "05_composition", "06_target_vs_actual", "10_avalanches", "11_enforcers", "15_solver_outcomes", "16_generation_performance"):
            pdf.savefig(figs[n])
        d = pdf.infodict(); d["Title"] = meta["title"] + " - Executive"; d["Subject"] = "KPI certification package"
    # technical
    with PdfPages(os.path.join(pkg, names["tech"])) as pdf:
        fig = plt.figure(figsize=(11.69, 8.27))
        fig.text(0.05, 0.9, meta["title"] + " - Technical report", fontsize=20, weight="bold")
        fig.text(0.05, 0.84, "\n".join([f"Candidate commit: {meta['candidate']['sha'] or 'unknown'}", f"Generated: {meta['generated_utc']}",
                                        "Sections: methodology, seed panel, scorecard, every KPI (checks, measurements, evidence, worst case), castle tables per course,",
                                        "generator / hazard / enemy / avalanche / Enforcer / solver / performance charts, exceptions, limits."]), fontsize=10, va="top", linespacing=1.6)
        pdf.savefig(fig); plt.close(fig)
        for t, blocks in sections:
            text = [b for b in blocks if b[0] != "img"]
            if text: pdf_text_pages(pdf, t, text_lines_of(text))
            for b in blocks:
                if b[0] == "img": pdf.savefig(figs[b[1]])
        d = pdf.infodict(); d["Title"] = meta["title"] + " - Technical"; d["Subject"] = "KPI certification package"


# ---------------------------------------------------------------- dashboard
def write_dashboard(pkg, blob, kpis, meta):
    with open(os.path.join(HERE, "cert_dashboard.html")) as f: tpl = f.read()
    slim = {"meta": {"title": meta["title"], "generated_utc": meta["generated_utc"], "candidate": meta["candidate"], "run": meta["run"], "expected": meta["expected"],
                     "rules_versions": meta["rules_versions"], "fingerprints": meta["fingerprints"]},
            "kpis": list(kpis.values()), "courses": blob["courses"], "castles": blob["castles"], "mode_castles": blob["mode_castles"], "hazard_types": blob["hazard_types"],
            "mechanics": blob["mechanics"], "modules": blob["modules"], "slices": blob["slices"], "separation": blob["separation"], "mechanic_types": MECHANICS,
            "enf_counters": ENF_COUNTERS, "avl_counters": AVL_COUNTERS, "modes": MODES, "mode_colors": MODE_COL}
    js = json.dumps(slim, default=str, separators=(",", ":")).replace("</", "<\\/").replace("<!--", "<\\!--")
    out = tpl.replace("__CERT_TITLE__", meta["title"].replace("<", "&lt;")).replace("__CERT_DATA__", js)
    with open(os.path.join(pkg, "dashboard.html"), "w") as f: f.write(out)


# ---------------------------------------------------------------- manifest, zip
def git_sha():
    if os.environ.get("GITHUB_SHA"): return os.environ["GITHUB_SHA"]
    try: return subprocess.run(["git", "-C", HERE, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10).stdout.strip() or None
    except (OSError, subprocess.SubprocessError): return None


def java_version():
    try:
        r = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=15)
        # the version line only: never the "Picked up JAVA_TOOL_OPTIONS ..." echo, which can carry host proxy settings
        lines = [x for x in (r.stderr + r.stdout).splitlines() if "version" in x and not x.startswith("Picked up")]
        return lines[0].strip() if lines else None
    except (OSError, subprocess.SubprocessError): return None


def machine():
    mem = None
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal"): mem = line.split(":", 1)[1].strip()
    except OSError: pass
    return {"platform": platform.platform(), "machine": platform.machine(), "processor": platform.processor() or None, "cpu_count": os.cpu_count(), "memory": mem,
            "runner": {k: os.environ.get(k) for k in ("RUNNER_OS", "RUNNER_ARCH", "RUNNER_NAME", "ImageOS", "ImageVersion") if os.environ.get(k)}}


def build_meta(M, args, ev, extra, label):
    cs = present(M)
    srv, repo, rid = os.environ.get("GITHUB_SERVER_URL"), os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_RUN_ID")
    dispatch = None
    if os.environ.get("DISPATCH_INPUTS"):
        try: dispatch = json.loads(os.environ["DISPATCH_INPUTS"])
        except ValueError: dispatch = {"raw": os.environ["DISPATCH_INPUTS"]}
    inputs = {}
    for c in cs:
        for f in c["files"]:
            p = os.path.join(c["dir"], f)
            inputs[f"{c['folder']}/{f}"] = {"sha256": sha256(p), "bytes": os.path.getsize(p)}
    return OrderedDict(
        title=f"Upwardly {label} certification", build_label=label, generated_utc=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        candidate={"sha": git_sha(), "ref": os.environ.get("GITHUB_REF_NAME")},
        run={"id": rid, "attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "workflow": os.environ.get("GITHUB_WORKFLOW"),
             "url": f"{srv}/{repo}/actions/runs/{rid}" if srv and repo and rid else None},
        dispatch_inputs=dispatch, jvm_props=(dispatch or {}).get("jvm_props") if isinstance(dispatch, dict) else None,
        expected={"modes": args_expect(args)[0], "seeds": args_expect(args)[1], "count": len(M["expected"]) or None,
                  "courses": ", ".join(f"{m}-s{s}" for m, s in M["expected"]) or None},
        found={"courses": [c["run"] for c in cs], "missing": [c["run"] for c in M["courses"] if not c["present"]], "modes": modes_found(M),
               "seeds": sorted({c["seed"] for c in cs})},
        rules_versions=sorted({str(c["summary"]["rules_version"]) for c in cs if c["summary"]["rules_version"] is not None}),
        fingerprints=sorted({c["summary"]["fingerprint"] for c in cs if c["summary"]["fingerprint"]}) or ([ev["fingerprint"] + " (evidence)"] if ev.get("fingerprint") else []),
        course_commits=sorted({str(c["summary"]["commit"]) for c in cs if c["summary"]["commit"]}),
        scoring_models=sorted({str(c["summary"]["scoring_model"]) for c in cs if c["summary"]["scoring_model"]}),
        evidence=ev, overrides_file=args.overrides, overrides_sha256=sha256(args.overrides) if args.overrides and os.path.exists(args.overrides) else None,
        variety_dir=args.variety_dir, extra=extra,
        tools={"python": sys.version.split()[0], "matplotlib": matplotlib.__version__, "java (packaging host)": java_version(),
               "package_tool": "tools/ci/cert_package.py", "package_tool_sha256": sha256(os.path.abspath(__file__))},
        machine=machine(), input_files=inputs)


def args_expect(args):
    em = args.expect_modes if args.expect_modes is not None else os.environ.get("EXPECT_MODES")
    es = args.expect_seeds if args.expect_seeds is not None else os.environ.get("EXPECT_SEEDS")
    return (json.loads(em) if em else None, json.loads(es) if es else None)


def write_manifest(pkg, meta):
    files = OrderedDict()
    for root, _, fs in os.walk(pkg):
        for f in sorted(fs):
            p = os.path.join(root, f); rel = os.path.relpath(p, pkg).replace(os.sep, "/")
            if rel == "manifest.json": continue
            files[rel] = {"sha256": sha256(p), "bytes": os.path.getsize(p)}
    meta["package_files"] = OrderedDict(sorted(files.items()))
    with open(os.path.join(pkg, "manifest.json"), "w") as f: json.dump(meta, f, indent=1, default=str)


def write_zip(out, pkg, name):
    zp = os.path.join(out, name + ".zip")
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, _, fs in os.walk(pkg):
            for f in sorted(fs):
                p = os.path.join(root, f)
                z.write(p, os.path.join(name, os.path.relpath(p, pkg)))
    outer = OrderedDict(package=name, zip=name + ".zip", zip_sha256=sha256(zp), zip_bytes=os.path.getsize(zp),
                        package_manifest_sha256=sha256(os.path.join(pkg, "manifest.json")),
                        generated_utc=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    with open(os.path.join(out, "manifest.json"), "w") as f: json.dump(outer, f, indent=1)
    with open(zp + ".sha256", "w") as f: f.write(f"{outer['zip_sha256']}  {name}.zip\n")
    return outer


# ---------------------------------------------------------------- self-check
def selftest(out_dir):
    problems, notes = [], []
    # 1. synthetic: no courses at all, and a course folder without its newer columns, must never pass automatically
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        empty = os.path.join(tmp, "empty"); os.makedirs(empty)
        M = build_model(empty, ["0", "1", "2"], ["1", "2", "3"], None)
        k = eval_kpis(M, {}, "0" * 40)
        apply_overrides(k, {"kpis": {kid: {"status": "PASS"} for kid in KPIS}})
        for kid, d in k.items():
            if d["status"] == "PASS" or d["automatic_status"] == "PASS": problems.append(f"synthetic empty campaign: KPI {kid} passed")
        legacy = os.path.join(tmp, "legacy"); d0 = os.path.join(legacy, "lanes-mode-0-seed-1"); os.makedirs(d0)
        with open(os.path.join(d0, "gate.json"), "w") as f: json.dump({"run": "NORMAL-s1", "passes": True, "generation": "COMPLETE", "geometric_integrity": "CLEAN (2 slices)"}, f)
        with open(os.path.join(d0, "run.json"), "w") as f: json.dump({"run": "NORMAL-s1", "mode": "NORMAL", "seed": 1, "reached_m": 5001.0, "slices": 2, "commit": "abc1234", "generator_rules_version": 1}, f)
        with open(os.path.join(d0, "solver.csv"), "w") as f: f.write("run,slice,y0,y1,completed\nNORMAL-s1,0,0,10,true\nNORMAL-s1,1,10,20,true\n")
        with open(os.path.join(d0, "integrity.csv"), "w") as f: f.write("run,slice,violation\n")
        with open(os.path.join(d0, "castles.csv"), "w") as f:
            f.write("run,mode,seed,castle,lane,windows,adj_sum\n" + "".join(f"NORMAL-s1,NORMAL,1,{k},TOTAL_REFERENCE,20,{100 + k}\n" for k in range(1, 11)))
        M = build_model(legacy, ["0"], ["1"], None)
        k = eval_kpis(M, {}, "abc1234" + "0" * 33)
        apply_overrides(k, {"kpis": {"2": {"status": "PASS"}, "10": {"status": "PASS"}}})
        if k["2"]["automatic_status"] == "PASS" or k["2"]["status"] == "PASS": problems.append("legacy solver.csv without 'verdict' let KPI 2 pass")
        if k["10"]["status"] == "PASS": problems.append("legacy solver.csv without 'verdict' let KPI 10 pass by analyst override")
        if k["14"]["status"] != "NOT VERIFIED": problems.append("KPI 14 without CI evidence is not NOT VERIFIED")
        k = eval_kpis(M, {"ci_conclusion": "success", "ci_sha": "abc1234"}, "abc1234" + "0" * 33)
        if k["14"]["automatic_status"] != "PASS": problems.append("KPI 14 with matching CI success evidence did not pass")
        k = eval_kpis(M, {"ci_conclusion": "failure", "ci_sha": "abc1234"}, "abc1234" + "0" * 33)
        if k["14"]["automatic_status"] != "FAIL": problems.append("KPI 14 with CI failure evidence did not fail")
        notes.append("synthetic campaigns: empty, legacy columns, CI evidence (pass/fail/missing) checked")
    if out_dir:
        outer = read_json(os.path.join(out_dir, "manifest.json"))
        pkg = out_dir
        if outer and "zip_sha256" in outer:
            zp = os.path.join(out_dir, outer["zip"])
            if not os.path.exists(zp) or sha256(zp) != outer["zip_sha256"]: problems.append("ZIP checksum does not match the outer manifest")
            else: notes.append(f"ZIP {outer['zip']} sha256 verified")
            pkg = os.path.join(out_dir, outer["package"])
            with zipfile.ZipFile(zp) as z:
                if z.testzip() is not None: problems.append("ZIP has a corrupt member")
        man = read_json(os.path.join(pkg, "manifest.json"))
        if not man: problems.append("package manifest.json missing"); return report_selftest(problems, notes)
        listed = man.get("package_files", {})
        for rel, info in listed.items():
            p = os.path.join(pkg, rel)
            if not os.path.exists(p): problems.append(f"manifest lists a missing file: {rel}")
            elif sha256(p) != info["sha256"]: problems.append(f"checksum mismatch: {rel}")
        for root, _, fs in os.walk(pkg):
            for f in fs:
                rel = os.path.relpath(os.path.join(root, f), pkg).replace(os.sep, "/")
                if rel != "manifest.json" and rel not in listed: problems.append(f"file not in the manifest: {rel}")
        notes.append(f"{len(listed)} package files verified against manifest.json")
        kj = read_json(os.path.join(pkg, "kpi_measurements.json")) or {}
        ids = [d["id"] for d in kj.get("kpis", [])]
        for kid in [str(i) for i in range(1, 17)] + ["7A", "7B"]:
            if kid not in ids: problems.append(f"KPI {kid} missing from kpi_measurements.json")
        for d in kj.get("kpis", []):
            if d["automatic_status"] == "PASS" and (d["missing_inputs"] or any(c["status"] != "PASS" for c in d["checks"])): problems.append(f"KPI {d['id']}: automatic PASS with missing inputs")
            if d["status"] == "PASS" and d["missing_inputs"]: problems.append(f"KPI {d['id']}: PASS with missing inputs")
            if d["status"] not in STATUSES: problems.append(f"KPI {d['id']}: unknown status {d['status']}")
        with open(os.path.join(pkg, "kpi_measurements.csv"), newline="") as f:
            csv_ids = {r["kpi"] for r in csv.DictReader(f)}
        if not set(ids) <= csv_ids: problems.append("kpi_measurements.csv lacks KPIs present in the JSON")
        notes.append(f"{len(ids)} KPI entries; statuses " + ", ".join(f"{k}={d['status']}" for k, d in ((d['id'], d) for d in kj.get('kpis', []))))
        h = open(os.path.join(pkg, "dashboard.html")).read()
        for pat in (r"<script[^>]+src=", r"<link[^>]+href=", r"@import", r"url\(\s*['\"]?https?:", r"<img[^>]+src=['\"]?https?:", r"<iframe", r"fetch\(", r"XMLHttpRequest"):
            if re.search(pat, h, re.I): problems.append(f"dashboard.html has an external reference or network call: {pat}")
        from html.parser import HTMLParser
        class P(HTMLParser):
            def __init__(s): super().__init__(); s.stack = []; s.err = []
            def handle_starttag(s, t, a):
                if t in ("script", "style", "div", "table", "section", "svg", "body", "html", "head", "select", "button"): s.stack.append(t)
            def handle_endtag(s, t):
                if t in ("script", "style", "div", "table", "section", "svg", "body", "html", "head", "select", "button"):
                    if not s.stack or s.stack[-1] != t: s.err.append(t)
                    else: s.stack.pop()
        p = P(); p.feed(h); p.close()
        if p.err or p.stack: problems.append(f"dashboard.html tag nesting problems: {p.err[:5]} open {p.stack[:5]}")
        m = re.search(r'<script type="application/json" id="cert-data">(.*?)</script>', h, re.S)
        try: json.loads(m.group(1)); notes.append("dashboard parses; embedded data is valid JSON; no external references")
        except (AttributeError, ValueError): problems.append("dashboard embedded data is not valid JSON")
        for f in os.listdir(pkg):
            if f.endswith(".pdf"):
                with open(os.path.join(pkg, f), "rb") as fh:
                    if fh.read(5) != b"%PDF-": problems.append(f"{f} is not a PDF")
    return report_selftest(problems, notes)


def report_selftest(problems, notes):
    for n in notes: print("selftest ok:", n)
    for p in problems: print("selftest FAIL:", p)
    print("selftest:", "PASSED" if not problems else f"FAILED ({len(problems)} problems)")
    return 0 if not problems else 1


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out", nargs="?"); ap.add_argument("parts", nargs="?")
    ap.add_argument("--variety-dir"); ap.add_argument("--overrides"); ap.add_argument("--evidence", action="append", default=[])
    ap.add_argument("--manifest-extra", action="append", default=[]); ap.add_argument("--expect-modes"); ap.add_argument("--expect-seeds")
    ap.add_argument("--build-label", default="Build 83"); ap.add_argument("--selftest", action="store_true"); ap.add_argument("--no-zip", action="store_true")
    a = ap.parse_args()
    if a.selftest: sys.exit(selftest(a.out))
    if not a.out or not a.parts: ap.error("OUT_DIR and PARTS_DIR are required")
    ev = kv_list(a.evidence); extra = kv_list(a.manifest_extra)
    if os.environ.get("CERT_EVIDENCE"): ev = OrderedDict(list(kv_list(os.environ["CERT_EVIDENCE"].split()).items()) + list(ev.items()))
    em, es = args_expect(a)
    ov = read_json(a.overrides) if a.overrides else None
    if a.overrides and ov is None: raise SystemExit(f"cannot read overrides file {a.overrides}")
    label = a.build_label
    name = "Upwardly_" + re.sub(r"[^A-Za-z0-9]+", "", label) + "_Certification"
    os.makedirs(a.out, exist_ok=True)
    pkg = os.path.join(a.out, name)
    if os.path.isdir(pkg): shutil.rmtree(pkg)
    os.makedirs(pkg)
    M = build_model(a.parts, em, es, a.variety_dir)
    meta = build_meta(M, a, ev, extra, label)
    kpis = eval_kpis(M, ev, meta["candidate"]["sha"])
    exceptions = apply_overrides(kpis, ov)
    for c in M["courses"]:
        s = c["summary"]
        if not c["present"]: exceptions.append(f"{c['run']}: course folder missing (kept as a row; nothing is measured for it)")
        if s["gate_passes"] is False: exceptions.append(f"{c['run']}: validation gate failed (solver: {s['gate_solver']}; generation: {s['gate_generation']}; integrity: {s['gate_integrity']})")
        if s["completed_false"]: exceptions.append(f"{c['run']}: {s['completed_false']} slice(s) not completed by the solver ({s['fail_classes']})")
        if s["verdicts"] and any(v != "PROVEN_SOLVABLE" for v in s["verdicts"]): exceptions.append(f"{c['run']}: non-proven verdicts {s['verdicts']}")
        if s["integrity_rows"]: exceptions.append(f"{c['run']}: {s['integrity_rows']} geometry integrity violation(s)")
    for kid, d in kpis.items():
        for ch in d["checks"]:
            if ch["status"] == "FAIL": exceptions.append(f"KPI {kid} FAIL: {ch['name']} - {ch['measured']}")
    blob = write_data(M, pkg)
    write_kpis(pkg, kpis)
    if ov is not None: shutil.copyfile(a.overrides, os.path.join(pkg, "evaluation_overrides.json"))
    figs = make_charts(M, kpis, pkg, f"{meta['title']}: KPI scorecard")
    sections = report_sections(M, kpis, meta, ov, exceptions)
    names = {"exec": f"Upwardly_{re.sub(r'[^A-Za-z0-9]+', '', label)}_Certification_Executive.pdf", "tech": f"Upwardly_{re.sub(r'[^A-Za-z0-9]+', '', label)}_Certification_Technical.pdf"}
    with open(os.path.join(pkg, "TECHNICAL_REPORT.md"), "w") as f: f.write(md_render(meta["title"] + " - Technical report", meta, sections))
    write_pdfs(pkg, names, figs, sections, kpis, meta, ov, M)
    for fg in figs.values(): plt.close(fg)
    write_dashboard(pkg, blob, kpis, meta)
    with open(os.path.join(pkg, "exceptions.txt"), "w") as f: f.write("\n".join(exceptions or ["none"]) + "\n")
    meta["statuses"] = OrderedDict((kid, {"status": d["status"], "source": d["status_source"], "automatic": d["automatic_status"]}) for kid, d in kpis.items())
    write_manifest(pkg, meta)
    outer = write_zip(a.out, pkg, name) if not a.no_zip else None
    print(f"package: {pkg}")
    for kid, d in kpis.items(): print(f"  KPI {kid:>3} {d['status']:<13} ({d['status_source']}) {d['name']}")
    if outer: print(f"zip: {outer['zip']} sha256 {outer['zip_sha256']} ({outer['zip_bytes']} bytes)")
    print(f"exceptions: {len(exceptions)} (exceptions.txt)")


if __name__ == "__main__":
    main()
