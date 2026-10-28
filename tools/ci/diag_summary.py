#!/usr/bin/env python3
"""Generator diagnostics for the four experimental priorities (owner directive 2026-10-28). Observe-only: reads the per-course LaneReport CSVs of a
campaign and prints tables to stdout (and the step summary), so they can be read from the job log. Nothing here changes a course or a score.

  diag_summary.py <parts dir> [out.md]

P1 traversal: module attempts, acceptance and the first refusal check per kind; mechanics used by main-route links.
P2 hazards: on-route hazard families per mode.
P3 enemy pressure: lane means per castle, enemies placed / on route per castle, Enforcer and avalanche encounters per castle.
P4 castle travel: route travel per castle interval (horizontal arc, rise, drop, dips) and its spread across the ten intervals.
"""
import csv, glob, math, os, re, statistics, sys
from collections import defaultdict, Counter

MODES = {"0": "NORMAL", "1": "PUNISHER", "2": "NGH"}
CAUSES = ["layer", "hazard", "plan", "window", "conflict", "threat"]
ENEMIES = {"CRAB", "BEE", "SKULL", "BLOB", "ENFORCER"}
NOT_HAZARD = ENEMIES | {"KEY", "GATE", "CLUB", "GEM"}
CIRC = None


def rows(path):
    if not os.path.exists(path): return []
    with open(path, newline="") as f: return list(csv.DictReader(f))


def truthy(v): return str(v).strip().lower() in ("1", "true", "yes", "y")


def num(v, d=0.0):
    try: return float(v)
    except (TypeError, ValueError): return d


def courses(parts):
    out = []
    for m in sorted(glob.glob(os.path.join(parts, "**", "modules.csv"), recursive=True)):
        d = os.path.dirname(m)
        g = re.search(r"mode-(\d)-seed-(\d+)", d)
        if not g:
            w = rows(os.path.join(d, "windows.csv"))
            if not w: continue
            mode, seed = w[0]["mode"], w[0]["seed"]
        else: mode, seed = g.group(1), g.group(2)
        out.append((MODES.get(mode, mode), seed, d))
    return out


def pct(a, b): return "-" if b == 0 else f"{100.0 * a / b:.1f}%"


def table(lines, head, body):
    lines.append("| " + " | ".join(head) + " |"); lines.append("|" + "---|" * len(head))
    for r in body: lines.append("| " + " | ".join(str(x) for x in r) + " |")
    lines.append("")


def main():
    parts = sys.argv[1]; out = sys.argv[2] if len(sys.argv) > 2 else None
    cs = courses(parts)
    L = [f"# Generator diagnostics ({len(cs)} courses)", ""]
    order = [m for m in ("NORMAL", "PUNISHER", "NGH") if any(c[0] == m for c in cs)]

    # ---- P1: module acceptance and first refusal cause, per mode and kind
    L.append("## P1. Module attempts, acceptance and first refusal cause"); L.append("")
    L.append("accepted = accepted in the slice attempt that became the course; discarded = accepted, but its slice attempt was later refused as a whole."); L.append("")
    for mode in order:
        st = defaultdict(lambda: Counter())
        for m, seed, d in cs:
            if m != mode: continue
            for r in rows(os.path.join(d, "modules.csv")):
                k = r["kind"]; s = st[k]; s["tried"] += 1
                if truthy(r["accepted"]):
                    s["accepted" if truthy(r["final_attempt"]) else "discarded"] += 1; continue
                for c in CAUSES:
                    if num(r.get("refused_" + c)) > 0: s[c] += 1; break
                else: s["other"] += 1
        body = []
        for k, s in sorted(st.items(), key=lambda kv: -kv[1]["tried"]):
            body.append([k, s["tried"], pct(s["accepted"], s["tried"]), pct(s["discarded"], s["tried"])] + [pct(s[c], s["tried"]) for c in CAUSES + ["other"]])
        L.append(f"### {mode}"); table(L, ["kind", "tried", "accepted", "discarded", *CAUSES, "other"], body)

    # ---- P1: what the main route's links actually use (take-off element type and the module that built it)
    L.append("## P1. Main-route links by take-off element and by building module"); L.append("")
    for mode in order:
        by_type, by_kind, n = Counter(), Counter(), 0
        for m, seed, d in cs:
            if m != mode: continue
            kind_of = {r["id"]: r.get("module_kind", "") for r in rows(os.path.join(d, "elements.csv"))}
            for r in rows(os.path.join(d, "links.csv")):
                n += 1; by_type[r["from_type"]] += 1; by_kind[kind_of.get(r["from"], "") or "(none)"] += 1
        L.append(f"### {mode} ({n} route links)")
        table(L, ["take-off type", "links", "share"], [[k, v, pct(v, n)] for k, v in by_type.most_common()])
        table(L, ["building module", "links", "share"], [[k, v, pct(v, n)] for k, v in by_kind.most_common()])

    # ---- P2: on-route hazard families
    L.append("## P2. Hazard families (on or reaching the route)"); L.append("")
    body, fams = [], set()
    per = {}
    for mode in order:
        cnt = Counter()
        for m, seed, d in cs:
            if m != mode: continue
            for r in rows(os.path.join(d, "hazards.csv")):
                if r["type"] in NOT_HAZARD or not truthy(r["on_route"]): continue
                cnt[r["type"]] += 1
        per[mode] = cnt; fams |= set(cnt)
    tot = {m: sum(per[m].values()) for m in order}
    for f in sorted(fams, key=lambda f: -sum(per[m][f] for m in order)):
        body.append([f] + [f"{per[m][f]} ({pct(per[m][f], tot[m])})" for m in order])
    body.append(["total"] + [tot[m] for m in order])
    for m in order:
        p = [v / tot[m] for v in per[m].values() if tot[m]]
        per[m]["_H"] = -sum(x * math.log2(x) for x in p if x > 0)
    body.append(["entropy (bits)"] + [f"{per[m]['_H']:.2f}" for m in order])
    table(L, ["family", *order], body)

    # ---- P3: lanes, enemies and encounters per castle
    L.append("## P3. Lanes per castle (mean over courses of the castle's summed adjusted window values)"); L.append("")
    for mode in order:
        lane = defaultdict(lambda: defaultdict(list))
        ens = defaultdict(Counter); enc = defaultdict(Counter); ncourse = 0
        for m, seed, d in cs:
            if m != mode: continue
            ncourse += 1
            acc = defaultdict(lambda: Counter())
            for r in rows(os.path.join(d, "windows.csv")):
                c = int(num(r["castle"])); a = acc[c]
                for k in ("terrain_adj", "hazard_adj", "enemy_adj", "interaction", "mitigation", "total_reference"): a[k] += num(r[k])
            for c, a in acc.items():
                for k, v in a.items(): lane[c][k].append(v)
            for r in rows(os.path.join(d, "hazards.csv")):
                if r["type"] not in ENEMIES: continue
                c = int(num(r["castle"])); ens[c]["placed"] += 1; ens[c][r["type"]] += 1
                if truthy(r["on_route"]): ens[c]["on_route"] += 1
            for r in rows(os.path.join(d, "encounters.csv")):
                c = int(num(r["castle"]))
                for k in ("enforcers_yellow", "enforcers_orange", "enforcers_red", "enforcer_unfair", "enforcer_dropped", "avalanches", "avalanche_unfair", "avalanche_dropped"): enc[c][k] += num(r.get(k))
        body = []
        for c in sorted(lane):
            mean = lambda k: statistics.mean(lane[c][k]) if lane[c][k] else 0.0
            e, q = ens[c], enc[c]
            body.append([c, f"{mean('terrain_adj'):.1f}", f"{mean('hazard_adj'):.1f}", f"{mean('enemy_adj'):.2f}", f"{mean('interaction'):.1f}", f"{mean('total_reference'):.1f}",
                         f"{e['placed'] / max(1, ncourse):.1f}", f"{e['on_route'] / max(1, ncourse):.1f}",
                         " ".join(f"{t[:3]}{e[t] / max(1, ncourse):.1f}" for t in sorted(ENEMIES) if e[t]),
                         f"{(q['enforcers_yellow'] + q['enforcers_orange'] + q['enforcers_red']) / max(1, ncourse):.1f}", f"{q['enforcer_dropped'] / max(1, ncourse):.1f}", f"{q['enforcer_unfair'] / max(1, ncourse):.1f}",
                         f"{q['avalanches'] / max(1, ncourse):.1f}", f"{q['avalanche_dropped'] / max(1, ncourse):.1f}"])
        L.append(f"### {mode} ({ncourse} courses; enemy and encounter counts are per course)")
        table(L, ["castle", "terrain", "hazards", "enemies", "interaction", "total", "enemies placed", "enemies on route", "by family", "Enforcers", "Enf dropped", "Enf unfair", "avalanches", "avl dropped"], body)

    # ---- P4: castle-to-castle route travel
    L.append("## P4. Castle-to-castle route travel (main route links; horizontal = arc between take-off and landing element centres)"); L.append("")
    for mode in order:
        travel, horiz, rise, drop, dips, links_n = [], [], [], [], [], []
        for m, seed, d in cs:
            if m != mode: continue
            el = {r["id"]: r for r in rows(os.path.join(d, "elements.csv"))}
            per_c = defaultdict(Counter)
            for r in rows(os.path.join(d, "links.csv")):
                a, b = el.get(r["from"]), el.get(r["to"])
                if a is None or b is None: continue
                c = int(num(a["castle"]))
                ds = abs(num(b["s"]) - num(a["s"]))
                if CIRC and ds > CIRC / 2: ds = CIRC - ds % CIRC if ds < CIRC else ds % CIRC
                dy = num(r["dy"]); p = per_c[c]
                p["h"] += ds; p["t"] += math.hypot(ds, dy); p["n"] += 1
                if dy >= 0: p["up"] += dy
                else: p["down"] += -dy; p["dips"] += 1 if dy < -0.3 else 0
            for c in range(1, 11):
                if c not in per_c: continue
                p = per_c[c]; travel.append(p["t"]); horiz.append(p["h"]); rise.append(p["up"]); drop.append(p["down"]); dips.append(p["dips"]); links_n.append(p["n"])
        if not travel: continue
        def s(v): return f"{statistics.mean(v):.0f} / {statistics.median(v):.0f} / {min(v):.0f}-{max(v):.0f} / sd {statistics.pstdev(v):.0f} / CV {statistics.pstdev(v) / statistics.mean(v):.3f}" if statistics.mean(v) else "-"
        L.append(f"### {mode} ({len(travel)} castle intervals)")
        table(L, ["measure", "mean / median / range / sd / CV"], [["route travel m", s(travel)], ["horizontal m", s(horiz)], ["rise m", s(rise)], ["drop m", s(drop)], ["dips (>0.3 m)", s(dips)], ["route links", s(links_n)]])

    # ---- generator tallies (counts.csv, experiment branch only): layer-clash kinds, launch corridors, pendulum offers and refusals
    tal = {m: Counter() for m in order}; have = False
    for m, seed, d in cs:
        for r in rows(os.path.join(d, "counts.csv")):
            have = True; tal[m][r["tally"]] += int(num(r["count"]))
    if have:
        L.append("## Generator tallies per mode (sum over courses; refusal reasons, pendulums)"); L.append("")
        keys = sorted({k for m in order for k in tal[m] if re.match(r"(clash_|pad_corridor|deck_in_hazard|pendulum_|saw_window|continuity_|encounter_reproof)", k)}, key=lambda k: -sum(tal[m][k] for m in order))
        table(L, ["tally", *order], [[k] + [tal[m][k] for m in order] for k in keys[:60]])
    text = "\n".join(L)
    print(text)
    if out:
        with open(out, "w") as f: f.write(text + "\n")
    sm = os.environ.get("GITHUB_STEP_SUMMARY")
    if sm:
        with open(sm, "a") as f: f.write(text + "\n")


if __name__ == "__main__":
    try:
        import json
        t = json.load(open(os.path.join(os.path.dirname(__file__), "..", "..", "assets", "data", "tuning.json")))
        CIRC = 2 * math.pi * float(t.get("radius") or 0) or None
    except Exception:
        CIRC = None
    main()
