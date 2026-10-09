# Host / game module contract (interface version 1)

Owner of the contract: the **host** (`modspi`). A module compiles against it with `compileOnly` and never ships it.

```java
public interface GameModule {
    int INTERFACE_VERSION = 1;
    int interfaceVersion();
    ApplicationListener create(HostEnv env);      // the stock libGDX listener; the backend drives it directly
}
public interface HostEnv {
    File dataDir();                 // same app-private dir the packaged game already uses (saves, history, settings)
    int appBuild(); int hostLevel();
    File moduleDir();               // verified, read-only module files (null if built into the host)
    void confirmHealthy();          // live play reached: the module becomes the rollback safety net
    void climbInProgress(boolean);  // gates module switches that would change a climb (see below)
    void diag(String line);
}
```

## Why this is small
* **No lifecycle proxy.** `create()` returns the game's `ApplicationListener`; the host passes it to `AndroidApplication.initialize`. `create/resize/render/pause/resume/dispose`, the GL context, the input queue, audio and the 60 Hz clock all run through the unmodified libGDX Android backend. There is one render loop, one clock, one set of native libraries and no per-frame indirection added by this project.
* **No new asset API.** The game keeps calling `Gdx.files.internal(...)`. Module/asset updates (Phase 5) are served by a host-installed `Files` wrapper that hands back a verified downloaded file when the active asset manifest owns the path and the unchanged APK handle otherwise.
* **Climb Up's module = `core/` unchanged** plus one factory class (`implements GameModule`, ~10 lines: `ClimbGame.appBuild = env.appBuild(); return new ClimbGame(env.dataDir());`). `AndroidLauncher` today does exactly this directly.

## Manifest (signed, schema 1) — `manifest.json` + detached `manifest.sig` (ECDSA P-256/SHA-256, DER, base64)
`app, channel, keyId, moduleName, sourceSha, entry, dex, moduleVersion, interfaceVersion, hostMin, hostMax, saveSchema, saveMin, generatorRuleset, contentVersion, revokeFloor, files[{name,sha256,size}]`.
Parsed strictly: missing, malformed or out-of-range fields reject the release. The signature is checked before any field except `keyId` is believed.

| Gate | Where | Effect |
|---|---|---|
| Signature by a pinned, un-revoked key | preflight, commit, every cold start | unsigned/forged/unknown key never runs |
| `app`, `channel` | same | a module for another app/channel never runs |
| `interfaceVersion` == host's, `hostMin ≤ hostLevel ≤ hostMax` | same | incompatible module never activates |
| every file: exact set, size, SHA-256 | commit, every cold start | tampered/corrupt/extra files rejected; checked while streaming and again at rest |
| `moduleVersion` > highest ever activated/staged; not blacklisted; ≥ `revokeFloor` | preflight, commit, activation | anti-rollback, no retry of a rolled-back version, signed revocation of older versions |
| climb gate (below) | activation | a module never takes over a climb it would change |

## Save / world compatibility
The tower is a stream of stored slices, so the seen part of a climb is immutable, but unseen slices are produced by whichever generator runs next. Each module therefore declares `generatorRuleset` and `saveSchema`/`saveMin`. While `climbInProgress(true)`, the host **keeps the running module** and leaves the update staged unless the candidate has the **same generator ruleset** and can **read the current save schema** (`saveMin ≤ current ≤ saveSchema`). The update then applies at the first cold start after the climb ends. No migration is invented, no climb is deleted, no checkpoint or altitude is touched. (Tested: `ModuleStoreTest.aGeneratorChangeWaitsWhileAClimbIsInProgress`, `aSameWorldUpdateAppliesEvenMidClimbButASaveItCannotReadDoesNot`.)

Open design point (Phase 6): rolling back across a save-schema bump. Rule to implement: a module that raises `saveSchema` must declare it backwards-compatible, or the host snapshots the save directory before its first launch. Not implemented yet; not claimed.

## Keys: rotation and revocation
`TrustedKeys` pins any number of keys by id. Rotation = ship a host pinning old+new, sign new releases with the new key, later drop the old. A key id can be revoked in the host. A signed release can raise `revokeFloor`, which makes every older module version unrunnable (including the rollback target) without a host update. Private keys never enter the repo, the APK or a module; signing is a CI-only step.

## Boot rules (all in `ModuleStore.boot`, none depend on module code)
1. discard any interrupted download; 2. verify and, if allowed, activate the staged module (cold start only); 3. count the launch of an unproven module; more than 2 unconfirmed launches ⇒ roll back; 4. re-verify the module on disk every start; failure ⇒ roll back; 5. no runnable module ⇒ reinstall the signed baseline from the APK; none ⇒ built-in recovery screen. A load/create failure falls through to the next candidate within the same launch. A proven module is the only safety net kept; an unproven one never replaces it.
