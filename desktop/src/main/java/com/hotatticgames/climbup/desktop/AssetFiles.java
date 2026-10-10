package com.hotatticgames.climbup.desktop;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.files.FileHandle;
import java.io.File;

/** Resolves {@code Gdx.files.internal(...)} against the game's own assets folder (next to the jar in the packaged build), whatever the process's working directory is. Everything else is the stock LWJGL3 behaviour. */
final class AssetFiles implements Files {
    private final File root;
    private final Lwjgl3Files base = new Lwjgl3Files();
    AssetFiles(File root) { this.root = root; }

    @Override public FileHandle internal(String path) { return root == null ? base.internal(path) : new Lwjgl3FileHandle(new File(root, path), FileType.Internal); }
    @Override public FileHandle getFileHandle(String path, FileType type) { return type == FileType.Internal ? internal(path) : base.getFileHandle(path, type); }
    @Override public FileHandle classpath(String path) { return base.classpath(path); }
    @Override public FileHandle external(String path) { return base.external(path); }
    @Override public FileHandle absolute(String path) { return base.absolute(path); }
    @Override public FileHandle local(String path) { return base.local(path); }
    @Override public String getExternalStoragePath() { return base.getExternalStoragePath(); }
    @Override public boolean isExternalStorageAvailable() { return base.isExternalStorageAvailable(); }
    @Override public String getLocalStoragePath() { return base.getLocalStoragePath(); }
    @Override public boolean isLocalStorageAvailable() { return base.isLocalStorageAvailable(); }
}
