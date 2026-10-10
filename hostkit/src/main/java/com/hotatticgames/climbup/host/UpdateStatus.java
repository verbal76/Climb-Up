package com.hotatticgames.climbup.host;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the player (or a diagnostics line) is told about updates, as a pure immutable value built from the store and the last check. Its one hard rule: it never claims "up to date" unless that is
 * true for everything the claim could be taken to cover. Concretely, {@link #headline} contains "up to date" only when
 * (1) a check completed successfully and found nothing newer, (2) that check covered BOTH the module (code) and its asset set, (3) nothing is waiting to be applied, (4) nothing was rolled back,
 * and (5) the version the check measured against is the version actually running (so "nothing newer than v8" is not reported while v8 was rolled back and v1 runs).
 * It also always says the app itself is not checked: the over-the-air system updates content only, never the installed app.
 */
public final class UpdateStatus {
    public enum Check { NEVER, FAILED, NO_NEWER_RELEASE, STAGED, REFUSED }
    public enum Component { APP, MODULE, ASSETS }

    public final String appName, channel;
    public final int hostLevel, activeModule, stagedModule, highestAccepted, revokeFloor;
    /** Short id of the active asset set (hash of its sorted path + sha256 list), "none" when the active module serves no files, "unreadable" if its list cannot be read. */
    public final String assetSetId; public final int assetCount;
    public final boolean unconfirmed;
    public final String lastUpdateResult, rollback;
    public final Check lastCheck; public final int lastCheckVersion; public final long lastCheckMillis; public final Set<Component> checked;

    public UpdateStatus(String appName, String channel, int hostLevel, int activeModule, int stagedModule, int highestAccepted, int revokeFloor, String assetSetId, int assetCount, boolean unconfirmed,
                        String lastUpdateResult, String rollback, Check lastCheck, int lastCheckVersion, long lastCheckMillis, Set<Component> checked) {
        this.appName = appName == null ? "" : appName; this.channel = channel == null ? "" : channel; this.hostLevel = hostLevel; this.activeModule = activeModule; this.stagedModule = stagedModule;
        this.highestAccepted = highestAccepted; this.revokeFloor = revokeFloor; this.assetSetId = assetSetId == null ? "none" : assetSetId; this.assetCount = assetCount; this.unconfirmed = unconfirmed;
        this.lastUpdateResult = lastUpdateResult == null ? "" : lastUpdateResult; this.rollback = rollback == null ? "" : rollback; this.lastCheck = lastCheck == null ? Check.NEVER : lastCheck;
        this.lastCheckVersion = lastCheckVersion; this.lastCheckMillis = lastCheckMillis;
        this.checked = Collections.unmodifiableSet(checked == null || checked.isEmpty() ? EnumSet.noneOf(Component.class) : EnumSet.copyOf(checked));
    }

    /** Reads the store (under its lock) and the downloader's last structured outcome; {@code downloader} may be null (no check has run in this process). */
    public static UpdateStatus capture(ModuleStore store, HostInfo host, String appName, ModuleDownloader downloader) {
        ModuleStore.Snapshot s = store.snapshot();
        String set = "none"; int count = 0;
        if (s.activeDir != null) {
            try { AssetManifest am = AssetManifest.read(s.activeDir); count = am.byPath.size(); set = count == 0 ? "none" : assetSetId(am); } catch (Exception e) { set = "unreadable"; }
        }
        Check c = downloader == null ? Check.NEVER : downloader.outcome;
        // The downloader's check covers the signed release as a whole: its manifest lists the module AND the asset list, so both are measured together. Only a check that completed counts.
        Set<Component> checked = (c == Check.NEVER || c == Check.FAILED) ? EnumSet.noneOf(Component.class) : EnumSet.of(Component.MODULE, Component.ASSETS);
        return new UpdateStatus(appName, host.channel, host.hostLevel, s.active, s.staged, s.highest, s.revokeFloor, set, count, s.pending != 0, s.lastResult, s.rollback, c,
                downloader == null ? 0 : downloader.outcomeVersion, downloader == null ? 0 : downloader.checkedAtMillis, checked);
    }

    /** Order-independent id of an asset set: first 12 hex digits of SHA-256 over the sorted "path NUL sha256 LF" lines. */
    public static String assetSetId(AssetManifest am) {
        List<String> lines = new ArrayList<>(); for (AssetManifest.Asset a : am.all()) lines.add(a.path + "\u0000" + a.sha256 + "\n");
        Collections.sort(lines); StringBuilder sb = new StringBuilder(); for (String l : lines) sb.append(l);
        return Hashing.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private String running() { return activeModule == 0 ? "the built-in recovery screen" : "v" + activeModule; }
    private static String names(Set<Component> s) { StringBuilder b = new StringBuilder(); for (Component c : s) { if (b.length() > 0) b.append(" + "); b.append(c.name().toLowerCase(java.util.Locale.ROOT)); } return b.length() == 0 ? "nothing" : b.toString(); }

    /** Whether {@link #headline} may say "up to date": see the class comment for the five conditions. */
    public boolean isUpToDate() {
        return lastCheck == Check.NO_NEWER_RELEASE && checked.contains(Component.MODULE) && checked.contains(Component.ASSETS) && stagedModule == 0 && rollback.isEmpty() && activeModule != 0 && lastCheckVersion == activeModule;
    }

    /** One line for a settings / About screen. {@code nowMillis} only adds the age of the last check. */
    public String headline(long nowMillis) {
        if (!rollback.isEmpty()) return "Rolled back: " + rollback + " (running " + running() + ")";
        if (stagedModule != 0) return "Update v" + stagedModule + " is downloaded and applies at the next start";
        switch (lastCheck) {
            case NEVER: return "Content updates: not checked yet";
            case FAILED: return "Could not check for content updates (offline?)";
            case REFUSED: return "Last update was refused";
            case STAGED: return "That update has since been applied or discarded; check again";
            default: break;
        }
        if (!(checked.contains(Component.MODULE) && checked.contains(Component.ASSETS)))
            return "Only part of the content was checked (" + names(checked) + "); not known to be current";
        if (!isUpToDate()) return "No newer release than v" + lastCheckVersion + " is offered, but " + running() + " is what runs here";
        String age = lastCheckMillis > 0 && nowMillis >= lastCheckMillis ? ", checked " + ageOf(nowMillis - lastCheckMillis) + " ago" : "";
        return "Content is up to date (v" + activeModule + age + "); the app itself is not checked for updates";
    }

    static String ageOf(long ms) {
        long m = ms / 60_000; if (m < 1) return "<1 min"; if (m < 120) return m + " min"; long h = m / 60; if (h < 48) return h + " h"; return (h / 24) + " days";
    }

    /** Diagnostics lines (About / Settings). Never contains key material or paths. */
    public List<String> lines(long nowMillis) {
        List<String> l = new ArrayList<>();
        l.add("App: " + appName + " (host level " + hostLevel + "), channel " + channel);
        l.add("Content: " + (activeModule == 0 ? "built-in recovery (no module installed)" : "v" + activeModule + (unconfirmed ? " (not yet confirmed)" : " (confirmed)")) + ", files " + assetSetId + (assetCount > 0 ? " (" + assetCount + ")" : ""));
        if (stagedModule != 0) l.add("Waiting for next start: v" + stagedModule);
        l.add("Last check: " + (lastCheck == Check.NEVER ? "never" : lastCheck.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + (lastCheckVersion > 0 ? " (v" + lastCheckVersion + ")" : "")
                + (lastCheckMillis > 0 && nowMillis >= lastCheckMillis ? ", " + ageOf(nowMillis - lastCheckMillis) + " ago" : "")) + "; covered " + names(checked));
        if (!lastUpdateResult.isEmpty()) l.add("Last update result: " + lastUpdateResult);
        if (!rollback.isEmpty()) l.add("Rollback: " + rollback);
        l.add("Safety: newest release ever accepted v" + highestAccepted + ", revoke floor v" + revokeFloor);
        return l;
    }
}
