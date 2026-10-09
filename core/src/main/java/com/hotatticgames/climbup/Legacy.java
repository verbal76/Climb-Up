package com.hotatticgames.climbup;

/**
 * Versioning and legacy runs. The game version is MAJOR.MINOR.PATCH:
 * MAJOR changes the rules of a run (castle layout, obstacle generation, finish rules), so runs started on an older MAJOR are not comparable and cannot be resumed;
 * MINOR adds features or content and PATCH fixes bugs: neither ever touches a run in progress.
 * A run in progress under an older MAJOR is offered to the player as a stamped legacy record (build, version, date, height, castles, time).
 */
public final class Legacy {
    private Legacy() {}

    /** MAJOR number of a version string ("" or malformed = 0, i.e. before versions were stamped). */
    public static int major(String version) {
        if (version == null) return 0;
        int dot = version.indexOf('.');
        try { return Integer.parseInt(dot < 0 ? version.trim() : version.substring(0, dot).trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** True if a climb is in progress that was started under a different MAJOR version than {@code current}. */
    public static boolean needsPrompt(SaveData sd, String current) {
        return sd.seed != 0 && sd.sliceJson != null && major(sd.climbVersion) != major(current);
    }

    /** "V1.1.3  BUILD 43" (build 0 = desktop / unknown). */
    public static String versionLine(String version, int build) { return "V" + version + (build > 0 ? "  BUILD " + build : "  DEV"); }

    /** Label for where a record came from: "V1.1.3 BUILD 43" or "BEFORE V1.1.3" when the climb pre-dates the stamps. */
    public static String stampLabel(String version, int build, String current) {
        if (version == null || version.isEmpty()) return "BEFORE V" + current;
        return "V" + version + (build > 0 ? " BUILD " + build : "");
    }

    /** Stamps a brand-new climb with the version/build/day it started on. */
    public static void stampNewClimb(SaveData sd, String version, int build, String today) {
        sd.climbVersion = version; sd.climbBuild = build; sd.climbDate = today; sd.climbHeight = 0f;
    }

    /** Sets the climb in progress aside as a legacy record and clears it. Records (best height, splits, finishes) are untouched. Returns the record. */
    public static SaveData.LegacyRun archive(SaveData sd, String today) {
        SaveData.LegacyRun r = new SaveData.LegacyRun();
        r.version = sd.climbVersion == null ? "" : sd.climbVersion; r.build = sd.climbBuild; r.date = sd.climbDate == null ? "" : sd.climbDate; r.savedDate = today;
        r.height = sd.climbHeight; r.towers = sd.towers; r.runClock = sd.runClock; r.finished = sd.finished; r.finishTime = sd.finishTime;
        sd.legacy.add(r);
        RunRecord.forgetClimb(sd);
        return r;
    }

    public static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()); }
}
