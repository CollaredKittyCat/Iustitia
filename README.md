# Fix: persistence toggle double-merges flag history (#28)

Branch `fix/persistence-toggle-double-merge` on
[CollaredKittyCat/Iustitia](https://github.com/CollaredKittyCat/Iustitia), based on
upstream [ThoriaDevelopment/Iustitia](https://github.com/ThoriaDevelopment/Iustitia) `main` @ `24736ee`.
Fixes [ThoriaDevelopment/Iustitia#28](https://github.com/ThoriaDevelopment/Iustitia/issues/28).

> This README replaces the project README on this branch only, to document the fix.
> It is in its own commit so it can be dropped before opening an upstream PR.

## The problem

`PersistenceManager.onToggle(true)` called `loadOnStartup()` with no reset first.
`FlagHistory.mergePersisted` is not idempotent: it sums `flagCounts` and appends persisted
flags to the live deque. Loading the persisted store into a session that already holds it:

- doubles every per-check flag count (3 becomes 6),
- duplicates timeline rows in `/ius hist`,
- overwrites `lastAlertWallMs` with the older persisted value, moving the tier decay clock
  backwards so a faded tier stays up longer.

It is worse than the issue describes. `onToggle` runs from `Iustitia.onConfigReloaded()`, which
fires on **every** config save (YACL screen, preset apply, setup wizard, `ConfigManager.reload`),
not only when the toggle flips. With persistence already on, any config save doubled the history.

## Why not "reset, then load"

The issue suggests calling `FlagHistory.reset()` before `loadOnStartup()` in `onToggle`. That
drops the live session: if persistence was off since startup, the in-memory flags are not on
disk yet, and a reset would throw them away.

## The fix

Track whether the in-memory history already holds the persisted store, and only load when it
does not.

| File | Change |
|---|---|
| `history/FlagHistory.kt` | New `holdsPersisted` flag with `holdsPersisted()` / `markHoldsPersisted()`. `reset()` clears it. `clearPlayer()` leaves it alone. |
| `history/FlagHistory.kt` | `mergePersisted` merges `lastAlertWallMs` with `max` instead of assigning, so an older persisted clock cannot pull a newer live one back. |
| `persistence/PersistenceManager.kt` | `loadOnStartup()` marks the store as holding persisted data. `saveHistory()` marks it too, since the write makes disk mirror memory (covers `/ius clear all`). `onToggle(true)` skips the load when the mark is set. |
| `test/.../history/FlagHistoryPersistTest.kt` | 6 pure-JVM tests for the mark lifecycle, the merge behaviour, and the decay clock. |

How each path behaves now:

| Path | Before | After |
|---|---|---|
| Persistence on at startup, then any config save | history merged again (doubled) | load skipped |
| Off at startup, turned on mid-session | merged; decay clock moved back | merged once; newer clock kept |
| World change with persistence on (`resetAll`) | reset, then load | unchanged |
| `/ius clear all` with persistence on, then config save | could reload pre-clear flags from disk | load skipped |

## Verification

```
python scripts/verify_contribution.py --static --run-build   PASS
./gradlew test                                               PASS (46 tests, 0 failures; baseline was green)
./gradlew build                                              PASS
git diff --check                                             clean
python scripts/live_selftest.py --check pipeline             PASS (1/1; no false positives, no bypasses)
```

- The decay-clock test was checked against the old assignment: it fails without the fix.
- The live suite run was the pipeline smoke pass only, not the full three-pass suite; this
  change does not touch detection.
- **Not run:** a manual `runClient` session toggling persistence in `/ius config` and reading
  `/ius hist`. The automated suite does not cover persistence.

Tests ran on JDK 21 (Temurin 21.0.12) on macOS arm64.

## Known follow-up (not fixed here)

`Iustitia.resetAll()` calls `FlagHistory.reset()` **before** `PersistenceManager.flush()`. If a
history write is pending (likely during combat, since every flag schedules one), the flush, or the
debounced writer waking after the reset, serializes the now-empty `FlagHistory` and overwrites
`history.json`. A world or dimension change can wipe the persisted history. Worth a separate issue.

## AI assistance

Written with Claude Code (Claude Opus 5.5): issue selection, code change, tests, and this
write-up. Every changed line should still get a human review before an upstream PR.
