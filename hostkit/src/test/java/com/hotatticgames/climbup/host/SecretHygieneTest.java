package com.hotatticgames.climbup.host;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Guard against private key material reaching the repository, a log or an artifact. It reads the repo (tests run from the hostkit module directory, so it is at "..") and checks, for text files:
 * (1) no PEM private-key BODY is committed (a header line followed by base64), (2) no workflow or lab script prints a private key file or a secret to the log, (3) no artifact upload can include a
 * key file, and (4) the throw-away lab keys are generated inside the run and never copied into the uploaded evidence. Level: unit (static scan of the checked-out tree; it does NOT inspect
 * the contents of artifacts that CI actually uploaded).
 */
public class SecretHygieneTest {
    static final File REPO = new File("..");
    static final Set<String> TEXT = new HashSet<>(Arrays.asList("yml", "yaml", "sh", "py", "java", "kt", "gradle", "properties", "md", "txt", "json", "xml", "pem", "key", "b64", "cfg", "toml"));
    static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(".git", ".gradle", "build", "node_modules", "assets", "branding", "design-package", "game-designer"));

    static List<File> textFiles() {
        List<File> out = new ArrayList<>();
        walk(REPO, out); return out;
    }
    static void walk(File d, List<File> out) {
        File[] kids = d.listFiles(); if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { if (!SKIP_DIRS.contains(k.getName())) walk(k, out); continue; }
            String n = k.getName(); int dot = n.lastIndexOf('.');
            if (dot >= 0 && TEXT.contains(n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) && k.length() < 2_000_000) out.add(k);
        }
    }
    static String text(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static String rel(File f) { return REPO.toPath().relativize(f.toPath()).normalize().toString(); }

    @Test public void noPrivateKeyBodyIsCommittedAnywhere() throws Exception {
        java.util.regex.Pattern body = java.util.regex.Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----\\s*\\n\\s*[A-Za-z0-9+/=]{40,}");
        List<File> files = textFiles(); assertTrue("the scan must actually see the repo (" + files.size() + " files, repo at " + REPO.getAbsolutePath() + ")", files.size() > 50);
        for (File f : files) assertFalse("a private-key body is committed in " + rel(f), body.matcher(text(f)).find());
    }

    @Test public void noWorkflowOrLabScriptPrintsAPrivateKeyFileOrASecretToTheLog() throws Exception {
        List<File> files = new ArrayList<>();
        File[] wf = new File(REPO, ".github/workflows").listFiles((d, n) -> n.endsWith(".yml")); assertNotNull(wf); files.addAll(Arrays.asList(wf));
        walk(new File(REPO, "tools/otalab"), files);
        assertTrue(files.size() >= 8);
        java.util.regex.Pattern printsKey = java.util.regex.Pattern.compile("\\b(cat|echo|printf|tee|head|tail|xxd|od|hexdump|base64|openssl\\s+(pkey|ec|asn1parse|req)\\b)\\b[^\\n|;&]*\\b[\\w$\\{\\}/.-]*(lab|other|private|signing)[\\w.-]*\\.(pem|key)\\b");
        for (File f : files) {
            int n = 0;
            for (String line : text(f).split("\n")) {
                n++;
                String l = line.trim(); if (l.startsWith("#")) continue;
                if (printsKey.matcher(l).find() && !l.contains("-pubout") && !l.contains("-out ")) fail(rel(f) + ":" + n + " reads or prints a private key file: " + l);
                if (l.matches(".*\\becho\\b.*\\$\\{\\{\\s*secrets\\..*") || l.matches(".*\\bset\\s+-x\\b.*") && text(f).contains("secrets.")) fail(rel(f) + ":" + n + " could print a secret: " + l);
            }
        }
    }

    @Test public void noUploadedArtifactCanContainAKeyFileAndLabKeysAreGeneratedInsideTheRun() throws Exception {
        File[] wf = new File(REPO, ".github/workflows").listFiles((d, n) -> n.startsWith("otalab") && n.endsWith(".yml")); assertNotNull(wf); assertTrue(wf.length >= 2);
        for (File f : wf) {
            String t = text(f);
            assertTrue(rel(f) + ": must be contents: read only", t.contains("permissions:\n  contents: read"));
            assertFalse(rel(f) + ": the lab workflows use no repository secrets", t.contains("secrets."));
            assertFalse(rel(f) + ": must not publish", t.contains("gh release") || t.contains("action-gh-release") || t.contains("softprops") || t.contains("git push") || t.contains("git tag"));
            if (t.contains("lab.pem")) assertTrue(rel(f) + ": lab keys are generated inside the run", t.contains("openssl genpkey"));
            // the whole rest of the "path:" line (it may contain ${{ ... }} braces, so do not stop at a brace)
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("upload-artifact@v4[^\\n]*\\n(?:[^\\n]*\\n){0,4}?[^\\n]*?\\bpath:\\s*([^\\n]*)").matcher(t);
            int uploads = 0;
            while (m.find()) {
                uploads++; String path = m.group(1).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
                assertFalse(rel(f) + ": an artifact path may include a key: " + path, path.contains(".pem") || path.contains(".key") || path.contains("keystore") || path.contains("private") || path.matches("\"?\\$\\{\\{runner\\.temp\\}\\}\"?\\}?") || path.matches("\"?\\$\\{\\{runner\\.temp\\}\\}/?\\*+\"?\\}?"));
            }
            assertTrue(rel(f) + ": expected at least one upload step to check (found " + uploads + ")", uploads >= 1);
        }
    }

    @Test public void theEvidenceDirectoriesTheScenariosWriteNeverReceiveKeyFiles() throws Exception {
        for (File f : new File(REPO, "tools/otalab").listFiles((d, n) -> n.endsWith(".sh"))) {
            String t = text(f);
            assertFalse(rel(f) + ": copies a .pem into the evidence directory", java.util.regex.Pattern.compile("\\b(cp|mv|cat)\\b[^\\n]*\\.pem[^\\n]*\\$\\{?OUT").matcher(t).find());
        }
    }
}
