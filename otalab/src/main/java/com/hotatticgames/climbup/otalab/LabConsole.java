package com.hotatticgames.climbup.otalab;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.hotatticgames.climbup.host.DirSnapshots;
import com.hotatticgames.climbup.host.HostInfo;
import com.hotatticgames.climbup.host.ModuleDownloader;
import com.hotatticgames.climbup.host.ModuleStore;
import com.hotatticgames.climbup.host.TrustedKeys;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Lab console (a separate screen and process from the game; lab builds only). It lets the owner run the OTA scenarios on a phone with no computer: stage the signed test releases carried
 * inside this APK through the real download/verify/stage path, restart the game cold so a staged release activates, run the deterministic equivalence check against the reference results
 * computed on a desktop JVM, and run a performance window. The game itself is untouched; this screen only starts it, kills its process, stages releases while it is not running, and reads
 * the host's log lines.
 */
public class LabConsole extends Activity {
    private static final String TAG = LabCommon.TAG, GAME = "com.hotatticgames.climbup.otalab.LabLauncher";
    private static final String EQUIV = "digest:12:7200,digest:3:7200,chaos:1:6000,chaos:3:6000,mixed:3:20000,mixed:6:20000";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView out; private volatile boolean polling; private volatile String action = "(none yet)", note = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); int p = dp(14); col.setPadding(p, p, p, p); sv.addView(col);
        TextView title = new TextView(this); title.setText("Climb Up OTA lab"); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22); title.setTypeface(Typeface.DEFAULT_BOLD); col.addView(title);
        TextView info = new TextView(this); info.setText(deviceInfo()); info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); col.addView(info);
        header(col, "Game");
        button(col, "Play the game (cold start)", () -> { mark("play"); killGame(); launch(null); });
        header(col, "Update releases carried in this app (real verify, stage; they activate at the next cold start)");
        button(col, "Check for updates now (GitHub channel)", () -> checkChannel());
        button(col, "Stage v2: same-world update", () -> stage("c2_same_world"));
        button(col, "Stage v7: code update (new executable code, same game)", () -> stage("c7_code_change"));
        button(col, "Stage v3: changes level generation (waits while a climb is in progress)", () -> stage("c3_new_generator"));
        button(col, "Stage v4: tampered file (must be refused)", () -> stage("c4_tampered"));
        button(col, "Stage v5: signed but broken (fails to load, rolls back)", () -> stage("c5_no_entry"));
        button(col, "Stage v8: next release", () -> stage("c8_after_interrupt"));
        button(col, "Stage v10: code update AND a changed game file together (the studio splash comes up inverted)", () -> stage("c10_code_and_assets"));
        header(col, "Checks");
        button(col, "Equivalence check on this phone (a few minutes)", () -> { mark("equivalence"); killGame(); Bundle x = new Bundle(); x.putString("selftest", EQUIV); launch(x); });
        button(col, "Performance run (about 80 seconds)", () -> { mark("performance"); killGame(); Bundle x = new Bundle(); x.putString("prop.climb.demo", "true"); x.putString("prop.climb.perf", "60"); x.putString("prop.climb.perfWarm", "12"); launch(x); });
        button(col, "Refresh", () -> refresh());
        header(col, "Maintenance");
        button(col, "Reset lab data (saves, host state; the baseline is reinstalled)", () -> { mark("reset"); killGame(); new Thread(() -> { wipe(getFilesDir()); note = "lab data erased"; refresh(); }).start(); });
        header(col, "Status");
        out = new TextView(this); out.setTypeface(Typeface.MONOSPACE); out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11); out.setTextIsSelectable(true); col.addView(out);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); polling = true; new Thread(() -> { while (polling) { refresh(); try { Thread.sleep(2000); } catch (InterruptedException e) { return; } } }, "console-poll").start(); }
    @Override protected void onPause() { polling = false; super.onPause(); }

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private void header(LinearLayout col, String s) { TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, dp(14), 0, dp(4)); col.addView(t); }
    private void button(LinearLayout col, String label, final Runnable r) { Button bt = new Button(this); bt.setText(label); bt.setAllCaps(false); bt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } }); col.addView(bt); }
    private void mark(String what) { action = what; note = ""; Log.i(TAG, "CONSOLE ACTION " + what); }

    private String deviceInfo() {
        String v = "?"; try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName + " (" + getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode() + ")"; } catch (Exception ignored) { }
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.SUPPORTED_ABIS[0] + ", lab build " + v;
    }

    private void killGame() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE); List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
        if (ps != null) for (ActivityManager.RunningAppProcessInfo pi : ps) if (pi.processName.equals(getPackageName())) android.os.Process.killProcess(pi.pid);
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
    }

    private void launch(Bundle extras) {
        Intent i = new Intent(); i.setClassName(getPackageName(), GAME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (extras != null) for (String k : extras.keySet()) i.putExtra(k, extras.getString(k));
        startActivity(i);
    }

    /** Stages one of the embedded releases while the game process is not running, through the same ModuleDownloader/ModuleStore code a network update uses. */
    private void stage(final String pack) {
        mark("stage " + pack);
        new Thread(new Runnable() { @Override public void run() {
            killGame();
            File root = new File(getFilesDir(), "host");
            if (!new File(root, "state.json").isFile()) { note = "Start the game once first (Play): the host installs its baseline module on its first run."; refresh(); return; }
            try {
                HostInfo host = new HostInfo(getPackageName(), HostInfo.HOST_LEVEL, "internal");
                TrustedKeys keys = new TrustedKeys(); keys.add(readAsset("lab_public_key.b64"));
                ModuleStore store = new ModuleStore(root, keys, host).withSaveGuard(new DirSnapshots(getFilesDir(), new File(root, "snap"), Collections.singleton("host")));
                String r = new ModuleDownloader(store, new AssetFetcher(getAssets()), "asset://updates/" + pack + "/").check();
                Log.i(TAG, "update check: " + r);
                note = "result: " + r + (r.startsWith("staged") ? "\nNow press 'Play the game (cold start)': the new release activates on that start." : "");
            } catch (Throwable t) { note = "stage failed: " + t; Log.i(TAG, note); }
            refresh();
        } }, "console-stage").start();
    }

    /** The real thing: ask the experimental GitHub channel whether a newer signed release exists, verify and stage it (it activates at the next cold start, like any update). */
    private void checkChannel() {
        mark("check github channel");
        new Thread(new Runnable() { @Override public void run() {
            killGame();
            File root = new File(getFilesDir(), "host");
            if (!new File(root, "state.json").isFile()) { note = "Start the game once first (Play): the host installs its baseline module on its first run."; refresh(); return; }
            try {
                String base = readAsset("update_base.txt");
                HostInfo host = new HostInfo(getPackageName(), HostInfo.HOST_LEVEL, "internal");
                TrustedKeys keys = new TrustedKeys(); keys.add(readAsset("lab_public_key.b64"));
                ModuleStore store = new ModuleStore(root, keys, host).withSaveGuard(new DirSnapshots(getFilesDir(), new File(root, "snap"), Collections.singleton("host")));
                ModuleDownloader dl = new ModuleDownloader(store, new ModuleDownloader.Http(false), base);
                String r = dl.check();
                Log.i(TAG, "update check: " + r);
                note = "channel " + base + "\nresult: " + r + (r.startsWith("staged") ? "\nNow press 'Play the game (cold start)': the new release activates on that start." : "");
                Log.i(TAG, "update status: " + com.hotatticgames.climbup.host.UpdateStatus.capture(store, host, "Climb up", dl).headline(System.currentTimeMillis()));
            } catch (Throwable t) { note = "channel check failed (is this the OTA test build, and is there a network?): " + t; Log.i(TAG, note); }
            refresh();
        } }, "console-check").start();
    }

    private String readAsset(String name) throws Exception { return new String(readAll(getAssets().open(name)), "UTF-8").trim(); }
    private static byte[] readAll(InputStream in) throws Exception { try { ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); return bo.toByteArray(); } finally { in.close(); } }
    private static void wipe(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) { wipe(c); } if (!f.getName().equals("files")) f.delete(); }

    private String stateSummary() {
        File f = new File(getFilesDir(), "host/state.json");
        if (!f.isFile()) return "no host state yet (the game has not been started)";
        try {
            String s = new String(readAll(new FileInputStream(f)), "UTF-8"); if (s.startsWith("S1:")) { int nl = s.indexOf('\n'); s = nl >= 0 ? s.substring(nl + 1) : s; }
            JSONObject j = new JSONObject(s);
            return "running/active v" + j.optInt("active") + ", last known good v" + j.optInt("lastGood") + ", unconfirmed v" + j.optInt("pending") + " (launches tried " + j.optInt("tries") + "), staged v" + j.optInt("staged")
                    + ", highest seen v" + j.optInt("highest") + ", climb in progress " + j.optBoolean("climbInProgress") + ", rolled-back/bad " + j.optJSONArray("bad") + "\nlast result: " + j.optString("lastResult") + "\nrollback note: " + j.optString("rollback");
        } catch (Throwable t) { return "state unreadable here: " + t; }
    }

    private List<String> logLines() {
        List<String> all = new ArrayList<String>();
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "brief", "-s", "OTALAB:I", "AndroidRuntime:E"});
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream())); String l; while ((l = r.readLine()) != null) all.add(l); r.close();
        } catch (Throwable t) { all.add("log unreadable: " + t); }
        int from = 0; for (int i = 0; i < all.size(); i++) if (all.get(i).contains("CONSOLE ACTION")) from = i;
        return all.subList(from, all.size());
    }

    private Map<String, String> reference() {
        Map<String, String> m = new HashMap<String, String>();
        try { String[] ls = new String(readAll(getAssets().open("reference-digests.txt")), "UTF-8").split("\n"); for (String l : ls) { int i = l.indexOf(" -> "); if (i > 0) m.put(l.substring(0, i), l.substring(i + 4).trim()); } } catch (Throwable ignored) { }
        return m;
    }

    private void refresh() {
        final StringBuilder sb = new StringBuilder();
        sb.append("STATE\n").append(stateSummary()).append("\n\nLAST ACTION: ").append(action).append('\n');
        if (!note.isEmpty()) sb.append(note).append('\n');
        List<String> lines = logLines();
        String us = null; for (String l : lines) { int i = l.indexOf("update status: "); if (i >= 0) us = l.substring(i + 15); }
        if (us != null) sb.append("UPDATE STATUS: ").append(us).append('\n');
        if (action.equals("equivalence")) {
            Map<String, String> ref = reference(); int same = 0, diff = 0, done = 0; StringBuilder det = new StringBuilder(); String devNote = "";
            for (String l : lines) {
                int a = l.indexOf("SELFTEST "); int b = l.indexOf(" -> ", a); int c = l.lastIndexOf(" ["); if (a < 0 || b < 0 || c < b) continue;
                String req = l.substring(a + 9, b), res = l.substring(b + 4, c).trim(); String exp = ref.get(req); done++;
                if (res.equals(exp)) { same++; det.append("  SAME   ").append(req).append('\n'); } else { diff++; det.append("  DIFFER ").append(req).append("\n    phone: ").append(res).append("\n    jvm  : ").append(exp).append('\n'); }
            }
            int total = EQUIV.split(",").length;
            sb.append("\nEQUIVALENCE (this phone's result vs the result computed on a desktop JVM for the same game code): ").append(done).append('/').append(total).append(" finished, ").append(same).append(" identical, ").append(diff).append(" different")
              .append(done == total ? (diff == 0 ? "\nPASS: the game code plays bit-for-bit the same simulation here as on the reference machine." : "\nDIFFERENT: report the lines below to the engineer (the simulation is not bit-exact across these two environments).") : "\n(still running...)").append('\n').append(det);
        } else if (action.equals("performance")) {
            String perf = null; for (String l : lines) { int i = l.indexOf("PERF module-host"); if (i >= 0) perf = l.substring(i); }
            sb.append("\nPERFORMANCE\n").append(perf != null ? perf + "\n(For reference, a good result is about 60 fps and interval p99 under 25 ms; the packaged-game comparison needs the reference app, see the procedure.)" : "(running: warm-up then a 60 s window...)").append('\n');
        }
        sb.append("\nLOG SINCE LAST ACTION\n");
        int shown = 0; for (int i = Math.max(0, lines.size() - 40); i < lines.size(); i++) { String l = lines.get(i); if (l.contains("SELFTEST") && l.length() > 220) l = l.substring(0, 220) + "..."; sb.append(l).append('\n'); shown++; }
        final String text = sb.toString();
        ui.post(new Runnable() { @Override public void run() { if (out != null) out.setText(text); } });
    }
}
