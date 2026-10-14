# Handover — RemoteControl (state at end of this session)

## What this tree is
All uploaded session archives (2, 3, 4, 5, 6, 7, 8a, 8b, 8d, 9b, 10b) merged into one Gradle project, plus the pieces that
were never in any upload, rebuilt this session. Session 1, 8c, 9a and 10a source archives were NOT provided.
NOTHING Android-side has been compiled (sandbox cannot reach Maven/Google). Only `shared` was compiled and unit-tested locally:
105 of 107 tests pass; the 2 others (`PairingAuthorityTest.jsonRoundTrip`, `CaptureProtocolTest`) need the kotlinx.serialization
compiler plugin and are expected to pass in CI. The first CI run is the real compile check: expect some compile errors.

## Done this session
- shared: removed Session-1 placeholder `FileEntry/FileList/FileChunk` from Protocol.kt (clashed with FilesWire.kt);
  added `RouteLogic.kt` (+`RouteSession`), `ReconnectPolicy.kt`, `AgentStatusText.kt`; added `FilesLogic.validName/fileName/partialName/MAX_*`
  (8c members that were missing); new tests `RouteLogicTest`, `ReconnectPolicyTest`, `AgentStatusTextTest`.
- agent: new `AutoStart`, `BootReceiver`, `BatteryHelper`, `AgentNotification`, `ReliabilitySetupActivity`; `AgentServer` now takes a Context,
  hosts Wake/Apps/Files handlers, TLS factory, StreamGate (`hasViewers()` respects pause), controller-count callback;
  `AgentService` uses ConnectivityKeeper, AutoStart, AgentNotification/AgentStatusText; full merged `AndroidManifest.xml`
  (debug manifest now only holds InputTestActivity); setup buttons on the Agent main screen.
- controller: new `LocalNetwork`, `NetworkWatcher`, `RouteDialog`; `DeviceStore` encrypts tokens via `TokenVault` (+`PairedDevice.key`, `byKey`);
  `ConnectionManager` fully rewritten (routes, TLS pin + plain fallback, backoff, network watcher, hubs, capture defaults,
  viewer-state, addresses). Constructor is now `ConnectionManager(context, store, listener)`; new API: `send(String)`, `send(Message)`,
  `setViewerVisible`, `badge()`, `forget(device)`, `shutdown()`, `routeStore`, `settings`; `Listener.onInfo(text)` added.
- versions set to 0.10.1 (versionCode 1001). CI workflow `.github/workflows/build.yml` and Gradle wrapper (8.7) are from Session 2 and untouched.

## Done in Session 11 (items 1 to 3 of the old list, plus docs)
- `controller/.../MainActivity.kt` rewritten (all screens, Wake bar, Remote…/Forget, badge, file picker, Settings host, viewer visibility, `shutdown()`).
- `ViewerScreen.kt`: `send` + `badge` parameters, touch input, Back/Home/Recents, text field, WakeBar, badge; `ScreenView.frameSize` added.
- `AppsPanel.kt`: icons per `SESSION8D-INTEGRATION.md`.
- `ConnectionManager.targets(device)` added. README "What is left" and the plan's Remaining Work + Status Log updated.
- STILL NOT compiled (no Android SDK / Maven in the sandbox): any compile error is expected to show up in the first CI run.

## STILL TO DO (in this order)
1. Push to GitHub, run Actions, fix compile errors from the first build (`./gradlew :shared:test assembleDebug`).
2. On-device verification checklists in each SESSION*-INTEGRATION.md (never run for 6, 8b to 10b).
3. Notes for Sessions 1, 8c and 9a were never uploaded (their behaviour is in the plan).

## Known caveats
- Reconstructed code (RouteLogic, ReconnectPolicy, 10a agent classes, ConnectionManager) follows the documented behaviour but is not the original; tests for it were written fresh.
- `ReconnectPolicy` usage: `idx = attemptAfterDrop(counter, uptime); delay = delayMs(idx, remote, rnd); counter = idx + 1`.
- Agent: `FOREGROUND_SERVICE` start refused by Android posts a tap-to-start notification (AgentService.promote).

## Push to GitHub
git init && git add . && git commit -m "RemoteControl 0.10.1" && git branch -M main && git remote add origin <url> && git push -u origin main
APKs appear under Actions -> run -> Artifacts (`agent-debug-apk`, `controller-debug-apk`); tag `v0.10.1` also attaches them to a Release.
