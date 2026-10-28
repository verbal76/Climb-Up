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

    # ---- KPI 16: Advanced Movement Utilization (owner amendment 2026-10-28), measured from elements, links and the solver witness, independently of scoring
    MECH = ["PAD", "SPRING", "SEESAW", "RAMP", "SWING", "MOVE_H", "MOVE_V", "MOVE_Z", "ROPE", "CABLE", "CRUMBLE"]
    KIND_TYPE = {"PAD": {"PAD"}, "SPRING": {"SPRING"}, "SEESAW": {"SEESAW"}, "RAMP": {"RAMP"}, "SWING": {"SWING"}, "MOVER_H": {"MOVE_H"}, "SLIDE_FALL": {"MOVE_H"},
                 "MOVER_V": {"MOVE_V"}, "ELEV_FALL": {"MOVE_V"}, "MOVER_Z": {"MOVE_Z"}, "ROPE": {"ROPE"}, "CABLE": {"CABLE"}, "CRUMBLE": {"CRUMBLE"}}
    L.append("## KPI 16. Advanced Movement Utilization"); L.append("")
    L.append("Distinct elements (a mechanic on several links counts once). placed = in the final course; main route / optional = route flag; on route links = "
             "a take-off or landing of a main-route link; witness = on a link the solver's hit-free witness traversed (TRAVERSED or TRAVERSED_IN_PASSING). "
             "Main-route links are all required (the route is one path); fork branches are optional and carry no witness. Ordinary link = both ends STATIC; "
             "unknown = an end missing from elements.csv (never counted as ordinary).")
    L.append("")
    for mode in order:
        placed, main, opt, onlink, wit = Counter(), Counter(), Counter(), Counter(), Counter()
        per_castle = defaultdict(Counter); win_has = Counter(); nwin = 0; ncourse = 0
        links_tot = links_ord = links_mech = links_unk = 0
        unattr = Counter(); route_el = 0; kind_owns = Counter(); kind_mods = Counter(); type_orphan = Counter()
        for m, seed, d in cs:
            if m != mode: continue
            ncourse += 1
            el = {r["id"]: r for r in rows(os.path.join(d, "elements.csv")) if re.fullmatch(r"\d+:\d+", r["id"])}          # course elements (hazards are k:hN)
            mods = defaultdict(set)
            for r in el.values():
                ty = r["type"]
                if truthy(r["route"]):
                    route_el += 1
                    if r["module_kind"] in ("UNATTRIBUTED", ""): unattr[ty] += 1
                if r.get("module"): mods[r["module"]].add((r["module_kind"], ty))
                if ty in MECH:
                    placed[ty] += 1
                    if truthy(r["route"]): main[ty] += 1
                    else: opt[ty] += 1
                    mk = r["module_kind"]
                    if truthy(r["route"]) and not any(ty in v for k_, v in KIND_TYPE.items() if k_ == mk) and mk not in ("BRIDGE", "REST", "CASTLE", "FORK", "STAIRS", "HOP", "GRAB", "LONG"):
                        type_orphan[ty + " in " + (mk or "none")] += 1
            for mid, s_ in mods.items():
                kinds = {k_ for k_, _ in s_}
                for k_ in kinds:
                    if k_ in KIND_TYPE:
                        kind_mods[k_] += 1
                        if any(ty in KIND_TYPE[k_] for _, ty in s_): kind_owns[k_] += 1
            seen_link, seen_wit = set(), set(); wins = defaultdict(set)
            last_route = {}          # a later slice's element 0 is the previous slice's last route platform, written once (LaneReport): resolve "k:0" to it
            for r in el.values():
                if truthy(r["route"]):
                    k_, i_ = r["id"].split(":"); k_, i_ = int(k_), int(i_)
                    if i_ >= last_route.get(k_, (-1, ""))[0]: last_route[k_] = (i_, r["id"])
            for k_ in list(last_route):
                if f"{k_ + 1}:0" not in el: el[f"{k_ + 1}:0"] = el[last_route[k_][1]]
            for r in rows(os.path.join(d, "links.csv")):
                links_tot += 1
                a_, b_ = el.get(r["from"]), el.get(r["to"])
                if a_ is None or b_ is None: links_unk += 1; continue
                tys = {a_["type"], b_["type"]}
                if tys & set(MECH): links_mech += 1
                elif tys == {"STATIC"}: links_ord += 1
                for e_ in (a_, b_):
                    if e_["type"] not in MECH: continue
                    seen_link.add(e_["id"])
                    if r["solver_status"].startswith("TRAVERSED"):
                        if e_["id"] not in seen_wit:
                            seen_wit.add(e_["id"]); per_castle[int(num(e_["castle"]))][e_["type"]] += 1; wins[int(num(e_["window"]))].add(e_["type"])
            for i in seen_link: onlink[el[i]["type"]] += 1
            for i in seen_wit: wit[el[i]["type"]] += 1
            wmax = max([int(num(r["window"])) for r in el.values()] or [0]); nwin += wmax + 1
            for w, s_ in wins.items(): win_has["any"] += 1
        L.append(f"### {mode} ({ncourse} courses, {links_tot} main-route links)")
        body = [[ty, placed[ty], main[ty], opt[ty], onlink[ty], wit[ty], pct(wit[ty], placed[ty]), f"{wit[ty] / max(1, ncourse):.1f}"] for ty in MECH if placed[ty]]
        table(L, ["mechanic", "placed", "main route", "optional", "on route links", "witness-traversed", "traversed / placed", "traversed per course"], body)
        L.append(f"Links: ordinary (STATIC-STATIC) {links_ord} ({pct(links_ord, links_tot)}), with a mechanic {links_mech} ({pct(links_mech, links_tot)}), "
                 f"other (castle, gem, ledge-only) {links_tot - links_ord - links_mech - links_unk}, unknown {links_unk}. "
                 f"25 m windows with a witness-traversed mechanic: {pct(win_has['any'], nwin)} of {nwin}.")
        L.append("")
        if per_castle:
            table(L, ["castle"] + [ty for ty in MECH if placed[ty]], [[c] + [f"{per_castle[c][ty] / max(1, ncourse):.1f}" for ty in MECH if placed[ty]] for c in sorted(per_castle)])
        L.append(f"Attribution: {sum(unattr.values())} of {route_el} main-route elements carry no module ({pct(sum(unattr.values()), route_el)}): "
                 + ", ".join(f"{k} {v}" for k, v in unattr.most_common(8)) + ". Modules owning an element of their defining type: "
                 + ", ".join(f"{k} {kind_owns[k]}/{kind_mods[k]}" for k in sorted(kind_mods)) + ". Main-route mechanics in a module of another kind: "
                 + (", ".join(f"{k} {v}" for k, v in type_orphan.most_common(8)) or "none") + ".")
        L.append("")

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

    # ---- P2b: Hazard Family Dominance (owner amendment 2026-10-28): placed vs route-threatening vs witness-exposed, per castle and 25 m window
    L.append("## P2b. Hazard Family Dominance"); L.append("")
    L.append("placed = every hazard of the family in the course; on route = SCORED (on or reaching a main-route platform); witness = route links the solver's "
             "hit-free witness traversed that list the family; optional = OFF_ROUTE (fork branches, decoys, rooms). Avalanche blocks are their own type (AVALANCHE) "
             "and are never counted as SPIKE_BLOCK. Spike-heavy window: at least 2 on-route spike blocks and at least half of that window's on-route hazards.")
    L.append("")
    for mode in order:
        fam_all, fam_route, fam_off, fam_wit, fam_enemy, fam_mech = Counter(), Counter(), Counter(), Counter(), Counter(), Counter()
        per_castle = defaultdict(Counter); win_spk = []; streaks = []; repeats = Counter(); longest_run = Counter(); ncourse = 0
        for m, seed, d in cs:
            if m != mode: continue
            ncourse += 1
            wins = defaultdict(Counter)
            for r in rows(os.path.join(d, "hazards.csv")):
                ty = r["type"]
                if ty in NOT_HAZARD: continue
                fam_all[ty] += 1
                if r["status"] == "SCORED":
                    fam_route[ty] += 1; per_castle[int(num(r["castle"]))][ty] += 1; wins[int(num(r["window"]))][ty] += 1
                    if r.get("same_platform_threats", "").strip() not in ("", "[]", "0"): fam_enemy[ty] += 1
                else: fam_off[ty] += 1
            if wins:
                run_ = 0
                for w in range(0, max(wins) + 1):
                    c_ = wins.get(w, Counter()); tot = sum(c_.values()); spk = c_["SPIKE_BLOCK"]; win_spk.append(spk)
                    if spk >= 2 and spk * 2 >= tot: run_ += 1
                    else:
                        if run_: streaks.append(run_)
                        run_ = 0
                if run_: streaks.append(run_)
            seq = []          # on-route hazards in route order (slice, platform index); a "repeat" is the next one being the same family
            for r in rows(os.path.join(d, "hazards.csv")):
                if r["type"] in NOT_HAZARD or r["status"] != "SCORED" or not r.get("platform"): continue
                k_, i_ = r["platform"].split(":") if ":" in r["platform"] else ("0", "0")
                try: seq.append((int(k_), int(i_), r["type"]))
                except ValueError: pass
            seq.sort(); run_f, run_n = None, 0
            for _, _, ty in seq:
                if ty == run_f: repeats[ty] += 1; run_n += 1; longest_run[ty] = max(longest_run[ty], run_n)
                else: run_f, run_n = ty, 1
            for r in rows(os.path.join(d, "links.csv")):
                hz = [x.strip() for x in r["hazards"].strip("[]").split(",") if x.strip()]
                hz = [x for x in hz if x not in NOT_HAZARD]
                if r["solver_status"].startswith("TRAVERSED"):
                    for x in set(hz): fam_wit[x] += 1
                for x in set(hz):
                    if r["from_type"] != "STATIC" or r["to_type"] != "STATIC": fam_mech[x] += 1
        tot_all, tot_route, tot_wit = sum(fam_all.values()), sum(fam_route.values()), sum(fam_wit.values())
        body = []
        for f in sorted(fam_all, key=lambda f: -fam_all[f]):
            body.append([f, fam_all[f], f"{fam_all[f] / max(1, ncourse):.0f}", pct(fam_all[f], tot_all), fam_route[f], pct(fam_route[f], tot_route), fam_off[f], fam_wit[f], pct(fam_wit[f], tot_wit), fam_enemy[f], fam_mech[f], repeats[f], longest_run[f]])
        L.append(f"### {mode} ({ncourse} courses)")
        table(L, ["family", "placed", "per course", "share placed", "on route", "share on route", "optional", "witness links", "share witness", "on route sharing a platform with other threats", "on links with a mechanic", "next on-route hazard same family", "longest same-family run"], body)
        if win_spk:
            ws = sorted(win_spk)
            L.append(f"Spike blocks per 25 m window: mean {statistics.mean(ws):.2f}, max {ws[-1]}, windows with >=1: {pct(sum(1 for x in ws if x >= 1), len(ws))}, >=2: {pct(sum(1 for x in ws if x >= 2), len(ws))}; "
                     f"spike-heavy streaks: {len(streaks)}, longest {max(streaks) if streaks else 0} windows, mean {statistics.mean(streaks) if streaks else 0:.1f}")
            L.append("")
        fams = sorted({f for c_ in per_castle.values() for f in c_}, key=lambda f: -sum(per_castle[c][f] for c in per_castle))
        cb = []
        for c in sorted(per_castle):
            tot = sum(per_castle[c].values()); p_ = [v / tot for v in per_castle[c].values() if tot]
            ent = -sum(x * math.log2(x) for x in p_ if x > 0)
            cb.append([c, tot] + [f"{100.0 * per_castle[c][f] / max(1, tot):.1f}" for f in fams] + [f"{ent:.2f}"])
        L.append(f"On-route family share by castle, % ({mode}); DOMINANCE_ROWS {mode} " + " ".join(fams))
        table(L, ["castle", "on route"] + fams + ["entropy"], cb)

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

    # ---- P3b: the enemy lane per castle by scoring rule (ledger rows): placements versus scored exposure, raw value, repetition factor
    L.append("## P3b. Enemy lane by rule per castle (ledger: rows, raw sum, final value, mean repetition factor; per course)"); L.append("")
    for mode in order:
        agg = defaultdict(lambda: defaultdict(lambda: [0, 0.0, 0.0, 0.0])); ncourse = 0
        for m, seed, d in cs:
            if m != mode: continue
            ncourse += 1
            for r in rows(os.path.join(d, "ledger.csv")):
                if r["lane"] != "ENEMIES": continue
                a_ = agg[int(num(r["castle"]))][r["rule"]]; a_[0] += 1; a_[1] += num(r["raw"]); a_[2] += num(r["value"]); a_[3] += num(r["repetition"])
        rules = sorted({k for c in agg for k in agg[c]})
        body = []
        for c in sorted(agg):
            body.append([c] + [f"{agg[c][k][0] / max(1, ncourse):.0f} / {agg[c][k][2] / max(1, ncourse):.1f} / {agg[c][k][3] / max(1, agg[c][k][0]):.2f}" if agg[c][k][0] else "-" for k in rules])
        L.append(f"### {mode} (cells: rows / value / mean repetition, per course)")
        table(L, ["castle"] + rules, body)

    # ---- P3c: enemy and hazard modules per castle: tried, accepted (final attempt), refusal causes
    L.append("## P3c. Creature, trap, hazard and Enforcer modules per castle (per course: tried / accepted, then the leading refusal causes)"); L.append("")
    KINDS = ["CREATURE", "TRAP", "HAZ", "MORTAR", "DROPS", "ENFORCER"]
    for mode in order:
        st = defaultdict(lambda: defaultdict(Counter)); ncourse = 0
        for m, seed, d in cs:
            if m != mode: continue
            ncourse += 1
            for r in rows(os.path.join(d, "modules.csv")):
                if r["kind"] not in KINDS: continue
                c = min(10, int(num(r["y"]) // 500) + 1); s = st[r["kind"]][c]; s["tried"] += 1
                if truthy(r["accepted"]):
                    if truthy(r["final_attempt"]): s["accepted"] += 1
                    continue
                for cz in CAUSES:
                    if num(r.get("refused_" + cz)) > 0: s[cz] += 1; break
        body = []
        for c in range(1, 11):
            row_ = [c]
            for k in KINDS:
                s = st[k][c]
                if not s["tried"]: row_.append("-"); continue
                top = ",".join(f"{cz[0]}{100 * s[cz] / s['tried']:.0f}" for cz in CAUSES if s[cz] * 10 >= s["tried"])
                row_.append(f"{s['tried'] / ncourse:.0f}/{s['accepted'] / ncourse:.1f} {top}")
            body.append(row_)
        L.append(f"### {mode} (refusal letters: l layer, h hazard, p plan, w window, c conflict, t threat budget; shown when >=10% of tries)")
        table(L, ["castle"] + KINDS, body)

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
        keys = sorted({k for m in order for k in tal[m] if re.match(r"(clash_|pad_corridor|deck_in_hazard|pendulum_|saw_window|continuity_|encounter_reproof|spikeblock_from_|haz_pick_|trap_pick_)", k)}, key=lambda k: -sum(tal[m][k] for m in order))
        table(L, ["tally", *order], [[k] + [tal[m][k] for m in order] for k in keys[:60]])
    text = "\n".join(L)
    print(text)
    if out:
        os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
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
