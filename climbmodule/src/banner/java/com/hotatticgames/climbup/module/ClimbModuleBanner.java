package com.hotatticgames.climbup.module;

import com.badlogic.gdx.ApplicationListener;
import com.hotatticgames.climbup.ClimbGame;
import com.hotatticgames.climbup.spi.GameModule;
import com.hotatticgames.climbup.spi.HostEnv;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Entry class of the title-banner release: the same game as {@link ClimbModule}, constructed through {@link BannerGame}. The ribbon names the delivered module version (read from the verified manifest). */
public final class ClimbModuleBanner implements GameModule {
    @Override public int interfaceVersion() { return INTERFACE_VERSION; }

    @Override public ApplicationListener create(HostEnv env) {
        String v = version(env);
        env.diag("title-banner v" + v);
        ClimbGame.appBuild = env.appBuild();
        return new BannerGame(env, "OTA UPDATE V" + v + " - LIVE FROM GITHUB!");
    }

    @Override public String selfTest(String request) { return SelfTest.run(request); }

    private static String version(HostEnv env) {
        try {
            File d = env.moduleDir();
            if (d != null) {
                String s = new String(Files.readAllBytes(new File(d, "manifest.json").toPath()), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"moduleVersion\"\\s*:\\s*(\\d+)").matcher(s);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) { }
        return "NEW";
    }
}
