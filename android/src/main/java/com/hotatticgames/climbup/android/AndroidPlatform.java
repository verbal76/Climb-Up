package com.hotatticgames.climbup.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.Screenshots;
import com.hotatticgames.climbup.SettingsScreen;
import com.hotatticgames.climbup.platform.GameInput;
import com.hotatticgames.climbup.platform.Platform;
import com.hotatticgames.climbup.ui.Ui;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Android's services around the shared game: everything the original touch platform does ({@link Platform#MOBILE}), plus where a player's screenshot
 * goes (Build 65). On Android 10 and later the PNG is handed to the gallery (MediaStore, Pictures/Upwardly: visible in Photos and Files, no storage
 * permission needed for the app's own images); on Android 8 and 9, or if the gallery refuses, it goes to the app's own Pictures folder
 * (Android/data/com.hotatticgames.climbup/files/Pictures/Screenshots, reachable over USB and in file managers), still without a permission.
 */
final class AndroidPlatform implements Platform {
    private final AndroidApplication app;
    AndroidPlatform(AndroidApplication app) { this.app = app; }

    @Override public boolean desktop() { return MOBILE.desktop(); }
    @Override public boolean touchControls() { return MOBILE.touchControls(); }
    @Override public boolean networkAllowed() { return MOBILE.networkAllowed(); }
    @Override public String title() { return MOBILE.title(); }
    @Override public GameInput gameInput() { return MOBILE.gameInput(); }
    @Override public void attach(ClimbGame game) { MOBILE.attach(game); }
    @Override public void frame(float dt) { MOBILE.frame(dt); }
    @Override public void screenChanged() { MOBILE.screenChanged(); }
    @Override public String tip(String text) { return MOBILE.tip(text); }
    @Override public void drawPlayHints(Ui ui, boolean carryingClub, float width, float height) { MOBILE.drawPlayHints(ui, carryingClub, width, height); }
    @Override public boolean drawPauseButton(Ui ui, float x, float y, float size) { return MOBILE.drawPauseButton(ui, x, y, size); }
    @Override public List<SettingsTab> settingsTabs() { return MOBILE.settingsTabs(); }
    @Override public void dispose() { MOBILE.dispose(); }

    @Override public String saveScreenshot(String name, byte[] png) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver r = app.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Upwardly");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);       // the gallery picks a new name itself if this one exists
                if (uri != null) {
                    try (OutputStream o = r.openOutputStream(uri)) { if (o == null) throw new IOException("no output stream"); o.write(png); }
                    v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0); r.update(uri, v, null, null);
                    return "PICTURES/UPWARDLY (GALLERY)";
                }
            } catch (Exception e) { Log.w("Upwardly", "gallery save failed, using the app folder", e); }
        }
        File base = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (base == null) return null;                                                          // no external storage at all: the game writes into its private folder
        File dir = new File(base, "Screenshots");
        Screenshots.writeTo(dir, name, png);
        return "ANDROID/DATA/" + app.getPackageName().toUpperCase(Locale.ROOT) + "/FILES/PICTURES/SCREENSHOTS";
    }
}
