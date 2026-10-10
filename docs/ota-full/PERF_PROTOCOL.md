# Performance protocol: packaged game vs module-delivered game

Question answered: does delivering the same game as a signed module through the host cost start-up time, frame pacing or memory compared with the game packaged normally? What is measured is only the delivery layer; the game classes are byte-identical (`ClimbModuleTest`) and run the same scripted climb.

## Apps (all installed side by side, distinct ids)
| Tag | App | Role |
|---|---|---|
| BASE | `com.hotatticgames.climbup` built from `:android` | the unmodified packaged game (cold start and memory only; its launcher is not modified, so it carries no frame recorder) |
| REF | `com.hotatticgames.climbup.otaref` (`climbref`) | the same classes packaged with no loader, the way the store edition is packaged |
| HOST | `com.hotatticgames.climbup.otaexp` (`climbhost`) | the same classes delivered as a signed module (DexClassLoader) |

## Measurements
* Cold start: `am start -S -W` TotalTime after `force-stop`, N runs, median.
* Frames: REF and HOST wrap the game in `PerfListener` (lab-only, delegating, no effect on the game). After a 12 s warm-up it records, for a fixed window, the time between consecutive `render()` calls (what the player sees) and the time `render()` takes. Reported: fps, interval p50/p90/p99/max, work p50/p99/max, count of intervals over 25 ms and 50 ms. The workload is the game's own scripted demo climb (`climb.demo`), identical in both.
* Memory: Java and native heap at the end of the window, process PSS from `dumpsys meminfo`.
* REF and HOST runs are interleaved (REF, HOST, REF, HOST, ...) so thermal and background drift hits both; the verdict uses the median across runs.

## Budgets (HOST against REF; fixed in `perf_capture.sh`, strict with `PERF_STRICT=1`)
* interval p99 <= REF p99 x 1.10 + 2 ms; fps >= 97% of REF; work p99 <= REF x 1.10 + 2 ms
* process PSS <= REF x 1.05 + 8 MB
* cold start (median) <= REF + 150 ms and <= 1.15 x REF + 50 ms

## Where it runs
* CI emulator (API 34, x86_64, software GL): proves the plumbing and catches gross regressions. Numbers are NOT representative of a phone and the verdict there is informational.
* Pixel 10 Pro XL, Android 17: run `PERF_STRICT=1 tools/otalab/perf_capture.sh out 7 60 3` from a computer with adb (7 cold-start runs, 60 s window, 3 interleaved play runs) with the three apps installed. Until that is run on the device, **no Pixel performance claim is made**.
