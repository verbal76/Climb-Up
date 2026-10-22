# Branch ledger — Climb up

Informational inventory (studio standard §4/§7). The authoritative source line is **`ccr-cb458cd1-w4dh5c`**.
All platform builds (Android APK, Windows EXE, Linux tar.gz) are produced from this one branch; platform differences are
handled by the `android/` and `desktop/` modules and build flags, not by separate gameplay branches.

Audited 2026-10-12. Method: for every remote branch, `git rev-list ccr..<branch>` and `git diff ccr <branch> -- core/src/main`
to find unique **game-code** commits (ignoring CI/OTA-host/backup-only branches).

## Authoritative
| Branch | Role | Status |
|---|---|---|
| `ccr-cb458cd1-w4dh5c` | **Authoritative source line.** Android + Windows + Linux all build from here. | Active. Superset of all game code below. |

## Accounted-for branches (no valuable unmerged game code)
| Branch | Unique core commits | Disposition |
|---|---|---|
| `exp/upwardly-windows` | The old Windows release line (last at `e6da59c`). | **Behind ccr.** Its core/src/main is fully contained in ccr; its "ahead" commits are parallel-hash duplicates of the twitch/cannon/ham/limb fixes that ccr already carries (verified identical content earlier; ccr now adds the grip + cannon overhaul on top). Superseded. Retire after Kevin confirms. |
| `exp/windows-release` | 0 (CI only) | The old run-id → EXE republish workflow. Replaced by the manual `windows.yml` desktop release on ccr. Retire after confirmation. |
| `exp/upwardly-cleanup-ota1` | `30405f0` generator rule (no hazard gap/mortar after a wet platform) | **Integrated** — ported to the Windows line (now ccr) as `7f74fa1`. Superseded. |
| `apk-cannonfix`, `apk-twitchfix`, `apk-hamfix` | fix branches | **Integrated** into ccr (cannon aim `8b58940`, controller twitch `74fc88b`, ham paws `6907911`). Superseded. |
| `exp/win-*` (gen-fixes, render-fixes, input-fix, settings-ui, crashfix, sync, consolidated, graphics) | folded into `exp/upwardly-windows` → ccr | **Integrated.** Superseded. |
| `exp/feature-players`, `feature-records`, `feature-steam` | merged into the Windows line → ccr | **Integrated** (players/records/Steam-wrapper on ccr). |
| `exp/ota-*`, `ota-exp-channel`, `main`, `verify-secrets`, `backup/*`, `winrel-b*`, `retrigger-android-b48` | 0 game-code | CI / OTA-lab / backup / recovery references. No unique gameplay work. Preserve as references; do not build from. |

## Conclusion
No valuable unmerged **game** work exists outside `ccr`. The historical sprawl was parallel development with
duplicate-hash commits, not lost fixes. The "fixes that never shipped" (grip, cannon) were insufficient fixes on the
shared line, not work stranded on a branch — now corrected on ccr (see HANDOFF.md).

Recovery references are kept, not deleted (deletion requires owner authorization per the standard).
