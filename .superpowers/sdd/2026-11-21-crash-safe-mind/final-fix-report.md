
# Final fix wave (Task F)

## Changes
1. Episodes gain persisted completed flag (DB_VERSION 2, ALTER TABLE onUpgrade default 1; beginEpisode=0, addEpisode=1; InMemory mirrored). completeEpisode(..., outcomeOk = !echoed) always sets completed. listInterruptedEpisodes uses completed == false. JarvisService stopped/abandoned episodes complete with blank reply, echoed=false, outcomeOk=false (no sentinels). BrainEvolver ignores blank-reply bad episodes; ReflectionWorker skips blank replies.
2. JarvisService IDLE collector invokes saveWorkingStateFn on every return to IDLE.
3. Warm-up generate skipped when KV restore returned RESTORED.
4. Tool-only blank-reply turn covered by MindStoreTest.
5. Recovery journal text: "...; my learning is safe but I couldn't restore the conversation." when no working state.

## Tests
- RED: compileDebugUnitTestKotlin FAILED (unresolved completed / outcomeOk) with main changes reverted.
- GREEN: testDebugUnitTest --tests *MindStoreTest* *BrainEvolverTest* *RecoveryTest* BUILD SUCCESSFUL.
- New tests: MindStoreTest (begun episode is not completed and addEpisode is completed; tool-only turn with blank reply is completed and not listed as interrupted; completeEpisode honours explicit outcomeOk false), BrainEvolverTest (blank-reply bad episode is never used as a negative), RecoveryTest (both journal texts).
- Full: testDebugUnitTest assembleDebug BUILD SUCCESSFUL, 156 tests, 0 failures.
- No TurnController/service-level unit test (JarvisService not JVM-testable); items 2 and 3 verified on device only.

## Device <serial>
installDebug (data kept): no SQLite errors in logcat. First launch wrote files/kv/kv-6e1742f9d10ad196.bin after idle. Force-stop + relaunch: "Recovery: unclean=true interrupted=0", "KV restore: RESTORED", no warm-up afterwards (no generate logged).
