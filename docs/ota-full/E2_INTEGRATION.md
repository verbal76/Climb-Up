# Integrating Engineer 2's reliability work

Status at the time of writing: **E2's work is local to its container and is not integrated.** Nothing below is claimed as delivered, reviewed or CI-tested until it is on `origin/exp/ota-reliability`, has been reviewed by E1 and has passed the integrated CI.

## The blocker, stated once
E2's session runs in Auto mode. Its permission classifier denies `git commit`, `git push` and `python3 tools/otalab/check_ownership.py` ("Modify Shared Resources"). Engineer 1 may not work around a denied operation, may not copy or relay E2's files, and may not use another tool, credential or the GitHub API in E2's place.

What the platform documents as the supported ways to grant permission (these are owner actions; they cannot be done from either engineer session):
1. In the app, open the E2 session ("OTA ENGINEER 2 — UPDATE RELIABILITY") and allow the operation there. The mode dropdown next to the prompt box offers Accept edits / Plan / Auto only; there is no bypass mode in cloud sessions.
2. Commit allow rules to `.claude/settings.json` in the repository (new sessions that check the repository out read it). Suggested content, nothing broader:
```json
{ "permissions": { "allow": [
  "Bash(python3 tools/otalab/check_ownership.py:*)",
  "Bash(git add:*)", "Bash(git commit:*)",
  "Bash(git push -u origin exp/ota-reliability)"
] } }
```
Because the rule is an owner decision, E1 has not created that file. If the owner wants route 2, it needs an explicit go-ahead for E1 to commit it to `exp/ota-full` and to start a replacement E2 session from that commit (E2's current local work would be lost unless it is first pushed or pasted by the owner).

Until one of those happens E1 continues all independent work and does not wait.

## Reported E2 results (unverified by E1 until integrated)
Durable checksummed state with backup and fsync; anti-rollback counter recovery; interrupted-activation recovery; save-before-delete ordering; clean-pause forgiveness (`ModuleStore.forgiveCleanPause()`); asset path normalisation; locale-independent duplicate detection; stricter splash-image verification; crash-point fault injection (1,390 + 917 simulated deaths, JVM, local).

## Review checklist before merging (E1 does all of these on the pushed branch)
| # | Property | How it is verified |
|---|---|---|
| 1 | Recovered modules stay cryptographically authenticated | Read `ModuleStore.boot()`/recovery: every module taken from disk goes through `ModuleVerifier` (signature, hashes, exact file set) before it is returned; no code path returns a directory that was only found. Test: tamper with one byte of an installed module, destroy `state.json`, boot, expect rejection and fall back to the verified baseline. Device: K8. |
| 2 | Damaged state cannot weaken anti-rollback | JVM tests for "both state copies lost, floor rebuilt from installed modules", plus a case where the highest installed module was removed; device K8 serves an older signed release after destroying `state.json` and expects a refusal. |
| 3 | Save snapshots survive interrupted updates | Read the order of operations in activation/confirm (snapshot written and fsynced before the schema bump, deleted only after the confirmation is durable); the crash-point sweep must include deaths between each step; device K4/K7 history hash checks stay green. |
| 4 | Clean-pause forgiveness cannot hide real startup crashes | `forgiveCleanPause()` only runs from the host's `onPause` after the module rendered a first frame (E1 wires it that way: a crash dies without an `onPause`; a hang in `create()` never renders). Test: three launches that die before the first frame still roll back and blacklist; a launch that never rendered is never forgiven. Device: K5 stays green. |
| 5 | Asset normalisation cannot allow traversal or unauthorised files | Review `AssetManifest` path rules (`..`, absolute, backslash, NUL, URL-encoded and Unicode variants, case-folding, duplicates after normalisation) and the property tests; overlay serves only paths present in a signed manifest. |
| 6 | Legacy state remains readable | A fixture of the old bare-JSON `state.json` is read and rewritten in the new form (E2 states it keeps this; E1 adds a fixture test if absent). |
| 7 | All existing module and host tests stay green | `:core:test :climbmodule:test :hostkit:test` in the regression job; `otalab`, `otalab-assets`, `otalab-climb` emulator runs. |

## Integration procedure (single writer)
1. `git fetch origin exp/ota-reliability`; confirm `docs/ota-full/reliability/BRANCH_POINT.txt` names `333e980754540aacc676b4ebf79446de9e4ff22f` and that `python3 tools/otalab/check_ownership.py --owner E2 --against 333e980...` is clean on that branch.
2. Review the diff file by file against the checklist; run `:hostkit:test` locally.
3. `git merge --no-ff origin/exp/ota-reliability` into `exp/ota-full` (ordinary merge, disjoint ownership).
4. E1 wires `forgiveCleanPause()` into `LabLauncher.onPause` (guarded by "first frame rendered" through a thin delegating listener) and runs the integrated validation, including the guarded device scenarios K8 (damaged state) and K9 (interrupted install), which `climb_emulator_test.sh` runs automatically once `docs/ota-full/reliability/RELIABILITY.md` exists.
5. Defects in E2-owned files go back to E2 as a REQUEST with the failing log.
