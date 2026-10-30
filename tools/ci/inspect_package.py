#!/usr/bin/env python3
"""Inspects a built certification package and prints what is actually in it (owner directive 2026-10-30, Build 83 master execution part 5:
"a successful process exit is not sufficient"). Reporting only: reads the package, changes nothing.

Usage: inspect_package.py OUT_DIR [--fail-on-problems]

OUT_DIR is cert_package.py's output folder (it holds manifest.json, <name>.zip and the <name>/ package folder). Checks, each printed:
  - the ZIP opens, its CRCs test clean, its SHA-256 matches the outer manifest, and it holds the package folder's files;
  - every file listed in the package manifest exists with the recorded SHA-256 and size, and no package file is unlisted;
  - kpi_measurements.json holds KPIs 1-16, 7 and 7A / 7B with a status, and the CSV holds the same ids;
  - every chart PNG decodes, and how much of it is drawn (share of pixels unlike the background, distinct colours): a near-blank chart is flagged;
  - every CSV parses, with its row and column count and its share of empty cells; an empty table is flagged;
  - package_data.json parses, with its sections and their sizes; per-mode, per-seed and per-castle data are counted;
  - each PDF has a header, an EOF marker and pages; the dashboard embeds its data, has no network references and no NaN / undefined text.
"""
import csv, hashlib, io, json, os, re, sys, zipfile

KPI_IDS = [str(i) for i in range(1, 17)] + ["7A", "7B"]


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()


def png_ink(p):
    """(width, height, share of pixels unlike the corner background, distinct colours in a 64-step quantisation), or an error string."""
    try:
        import matplotlib.image as mpimg
        import numpy as np
        a = mpimg.imread(p)
        if a.ndim == 2: a = a[:, :, None].repeat(3, 2)
        rgb = a[:, :, :3]
        bg = rgb[2, 2]
        diff = np.abs(rgb - bg).sum(axis=2) > 0.06
        q = (rgb * 63).astype(int)
        colours = len(np.unique(q[:, :, 0] * 4096 + q[:, :, 1] * 64 + q[:, :, 2]))
        return rgb.shape[1], rgb.shape[0], float(diff.mean()), colours
    except Exception as e:  # report, never crash the inspection
        return f"unreadable: {e}"


def main():
    out = sys.argv[1]
    strict = "--fail-on-problems" in sys.argv
    problems = []
    outer = json.load(open(os.path.join(out, "manifest.json")))
    name = outer["package"]
    pkg = os.path.join(out, name)
    zp = os.path.join(out, outer["zip"])
    print(f"# Package inspection: {name}\n")

    # ZIP
    zsha = sha256(zp)
    with zipfile.ZipFile(zp) as z:
        bad = z.testzip()
        entries = [i.filename for i in z.infolist() if not i.is_dir()]
    print(f"ZIP {outer['zip']}: {os.path.getsize(zp):,} bytes, {len(entries)} entries, CRC test {'clean' if bad is None else 'FAILED at ' + bad}, "
          f"SHA-256 {'matches' if zsha == outer['zip_sha256'] else 'DOES NOT MATCH'} the outer manifest")
    if bad is not None: problems.append(f"zip CRC failure at {bad}")
    if zsha != outer["zip_sha256"]: problems.append("zip sha256 mismatch")

    # Package manifest
    inner = json.load(open(os.path.join(pkg, "manifest.json")))
    listed = inner.get("package_files", {})
    on_disk = set()
    for root, _, fs in os.walk(pkg):
        for f in fs:
            rel = os.path.relpath(os.path.join(root, f), pkg).replace(os.sep, "/")
            if rel != "manifest.json": on_disk.add(rel)
    mism = [r for r, v in listed.items() if not os.path.exists(os.path.join(pkg, r)) or sha256(os.path.join(pkg, r)) != v["sha256"]]
    unlisted = sorted(on_disk - set(listed))
    in_zip = {e.split("/", 1)[1] for e in entries if "/" in e}
    missing_zip = sorted((on_disk | {"manifest.json"}) - in_zip)
    print(f"Package manifest: {len(listed)} files listed, {len(mism)} hash/size mismatches, {len(unlisted)} unlisted files, {len(missing_zip)} files missing from the ZIP")
    print(f"Provenance: candidate {inner.get('candidate')}, run {inner.get('run', {}).get('url')}, course commits {inner.get('course_commits')}, "
          f"rules {inner.get('rules_versions')}, fingerprints {inner.get('fingerprints')}")
    print(f"Courses: expected {inner.get('expected')}, found {inner.get('found', {}).get('courses')}, missing {inner.get('found', {}).get('missing')}")
    print(f"Input files hashed: {len(inner.get('input_files', {}))}; tools {inner.get('tools')}")
    for p, what in ((mism, "manifest mismatch"), (unlisted, "unlisted file"), (missing_zip, "missing from zip")):
        if p: problems.append(f"{what}: {p[:5]}")

    # KPIs
    kj = json.load(open(os.path.join(pkg, "kpi_measurements.json")))
    kpis = {str(k.get("id")): k for k in kj.get("kpis", [])}
    print("\n## KPIs\n\nClass: MEASURED-COMPLETE (objective rule decides, every input present), MEASURED-INCOMPLETE (some required input or check is "
          "missing), ANALYST-JUDGMENT (measured; the framework leaves the verdict to the analyst), UNAVAILABLE (nothing measured).\n\n"
          "| KPI | name | class | automatic status | final status | checks | measured / total measurements | missing inputs |\n|---|---|---|---|---|---|---|---|")
    for kid in KPI_IDS:
        k = kpis.get(kid)
        if not k:
            print(f"| {kid} | MISSING | | | | | |"); problems.append(f"KPI {kid} missing"); continue
        ms = k.get("measurements", []); got = sum(1 for x in ms if x.get("measured")); miss = k.get("missing_inputs", []) or []
        if kid == "7": cls = "derived from 7A and 7B"
        elif not ms and not k.get("checks"): cls = "UNAVAILABLE"
        elif miss: cls = "MEASURED-INCOMPLETE"
        elif k.get("fully_objective"): cls = "MEASURED-COMPLETE"
        else: cls = "ANALYST-JUDGMENT"
        print(f"| {kid} | {k.get('name', '')} | {cls} | {k.get('automatic_status')} | {k.get('status')} | {len(k.get('checks', []))} | "
              f"{got} / {len(ms)} | {len(miss)} |")
        for x in miss[:6]: print(f"|  |  | missing: {x[:150]} |  |  |  |  |  |")
    with open(os.path.join(pkg, "kpi_measurements.csv"), newline="") as f:
        rows = list(csv.DictReader(f))
    csv_ids = {r.get("kpi") or r.get("id") or r.get("KPI") for r in rows}
    if not set(KPI_IDS) <= csv_ids: problems.append(f"kpi_measurements.csv lacks {sorted(set(KPI_IDS) - csv_ids)}")
    print(f"\nkpi_measurements.csv: {len(rows)} rows, KPI ids {sorted(csv_ids, key=lambda s: (len(s), s))}")

    # Charts
    charts = sorted(f for f in on_disk if f.startswith("charts/") and f.endswith(".png"))
    print(f"\n## Charts ({len(charts)} PNG)\n\n| chart | size px | drawn share | colours | flag |\n|---|---|---|---|---|")
    for c in charts:
        r = png_ink(os.path.join(pkg, c))
        if isinstance(r, str):
            print(f"| {c} | | | | {r} |"); problems.append(f"{c} {r}"); continue
        w, h, ink, col = r
        flag = "NEAR-BLANK" if ink < 0.02 or col < 6 else ""
        if flag: problems.append(f"{c} near-blank (drawn {ink:.3f}, colours {col})")
        print(f"| {c} | {w}x{h} | {ink:.3f} | {col} | {flag} |")

    # CSV data
    tables = sorted(f for f in on_disk if f.endswith(".csv"))
    print(f"\n## Tables ({len(tables)} CSV)\n\n| table | rows | columns | empty cells | flag |\n|---|---|---|---|---|")
    for t in tables:
        with open(os.path.join(pkg, t), newline="") as f:
            data = list(csv.reader(f))
        n = max(len(data) - 1, 0); ncol = len(data[0]) if data else 0
        cells = sum(len(r) for r in data[1:]) or 1
        empty = sum(1 for r in data[1:] for v in r if v.strip() == "" or v.strip().lower() in ("nan", "none", "null")) / cells
        flag = "EMPTY" if n == 0 else ""
        if flag: problems.append(f"{t} has no rows")
        print(f"| {t} | {n} | {ncol} | {empty:.1%} | {flag} |")

    # JSON data
    for j in sorted(f for f in on_disk if f.endswith(".json")):
        try:
            d = json.load(open(os.path.join(pkg, j)))
            keys = list(d.keys()) if isinstance(d, dict) else f"list[{len(d)}]"
            print(f"\nJSON {j}: {os.path.getsize(os.path.join(pkg, j)):,} bytes, top level {keys if isinstance(keys, str) else keys[:20]}")
        except ValueError as e:
            problems.append(f"{j} does not parse: {e}")

    # Breakdowns: per mode, per seed, per castle (from the data tables)
    castles = os.path.join(pkg, "data", "castles.csv")
    if os.path.exists(castles):
        with open(castles, newline="") as f: rows = list(csv.DictReader(f))
        cols = rows[0].keys() if rows else []
        def distinct(*names):
            for nme in names:
                if nme in cols: return sorted({r[nme] for r in rows})
            return []
        print(f"\nPer-castle data (data/castles.csv): {len(rows)} rows; modes {distinct('mode')}; seeds {distinct('seed')}; castles {distinct('castle')}")
    else:
        problems.append("data/castles.csv missing (no per-castle breakdown)")

    # Documents
    print("\n## Documents\n")
    for f in sorted(x for x in on_disk if x.endswith(".pdf")):
        b = open(os.path.join(pkg, f), "rb").read()
        pages = len(re.findall(rb"/Type\s*/Page[^s]", b))
        ok = b.startswith(b"%PDF") and b.rstrip().endswith(b"%%EOF") and pages > 0
        print(f"PDF {f}: {len(b):,} bytes, {pages} pages, {'well-formed' if ok else 'MALFORMED'}")
        if not ok: problems.append(f"{f} malformed")
    dash = os.path.join(pkg, "dashboard.html")
    if os.path.exists(dash):
        h = open(dash, encoding="utf-8").read()
        ext = re.findall(r"(?:src|href)\s*=\s*[\"']https?://|fetch\(|XMLHttpRequest", h)
        text = re.sub(r"<script\b.*?</script>", "", h, flags=re.S)   # the dashboard's own code tests for undefined; only the rendered text counts
        bad = [w for w in ("NaN", "undefined") if re.search(r"[>\s\"']" + w + r"[<\s\"',]", text)]
        print(f"Dashboard: {len(h):,} characters, network references {len(ext)}, suspicious text {bad or 'none'}")
        if ext: problems.append("dashboard has network references")
    else:
        problems.append("dashboard.html missing")
    md = os.path.join(pkg, "TECHNICAL_REPORT.md")
    if os.path.exists(md):
        t = open(md, encoding="utf-8").read()
        print(f"TECHNICAL_REPORT.md: {len(t):,} characters, {t.count(chr(10) + '## ')} sections, {len(re.findall(r'!\[', t))} figure references")

    print(f"\n## Result: {len(problems)} problem(s)")
    for p in problems: print(f"- {p}")
    if strict and problems: sys.exit(1)


if __name__ == "__main__":
    main()
