# Session 10a — integration (reliability: reconnect, boot recovery, battery, notification)

Builds on `SESSION9B-INTEGRATION.md`. **No wire change** (protocol stays v3). Session 10b (TLS, settings screen) is next.

## Agent
Add `AutoStart.kt`, `BootReceiver.kt`, `BatteryHelper.kt`, `AgentNotification.kt`, `ReliabilitySetupActivity.kt`.

Manifest (agent):
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>   <!-- if not there yet -->
<receiver android:name=".BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
    </intent-filter>
</receiver>
<activity android:name=".ReliabilitySetupActivity" android:exported="true" android:label="RC Reliability Setup">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity>
```
`AgentService` (not in the upload, three small edits):
1. Start: `AutoStart.markRunning(this, true)`. Where the person stops the Agent (stop action / `stopSelf` from the UI): `AutoStart.markRunning(this, false)`. Do NOT clear it in `onDestroy` (the system kills the service on reboot and that must not count as "stopped on purpose").
2. Notification: replace the notification it builds today by
   `AgentNotification.ensureChannels(this, listOf("<old channel id>"))` once, then
   `startForeground(NOTIF_ID, AgentNotification.buildStatus(this, AgentStatusText.format(0, false)))`.
   Keep your existing `startForeground` type arguments. When the number of authenticated controllers or the capture state changes:
   `AgentNotification.update(this, NOTIF_ID, AgentStatusText.format(controllers, capturing))`.
   Pass your old channel id(s) so they are deleted (a channel's importance cannot be lowered in code once created; the new id is `agent_status_min`).
3. Start: `AgentNotification.clearStartPrompt(this)`.
If `AgentService` needs an action/extra to start, change the one `Intent(app, AgentService::class.java)` in `BootReceiver.startAgent`.

## Controller
Add `NetworkWatcher.kt`; replace `RouteConnector.kt` (adds `remoteLikely()`). Needs `ACCESS_NETWORK_STATE` (already from 9a).
In `ConnectionManager` (names are indicative; adapt to the real fields):
```kotlin
private var attempt = 0
private var connectedAt = 0L
private val net = NetworkWatcher(app) { online ->
    if (!online) { setStatus("No network"); cancelPendingRetry(); socket?.cancel() }
    else if (wantsConnection()) { cancelPendingRetry(); socket?.cancel(); attempt = 0; scheduleConnect(ReconnectPolicy.afterNetworkChange()) }
}.also { it.start() }          // stop() in the manager's shutdown if it has one

// handshake accepted:
connectedAt = SystemClock.elapsedRealtime()

// transport failure (onFailure / closed before or after the handshake, NOT a rejected handshake):
val remote = routes.remoteLikely()                      // before failed()
val f = FailureClassifier.classify(t, remote)           // t may be null on a plain close
log(f.text)
val next = routes.failed()
if (next != null) { dial(next); return }
val up = if (connectedAt > 0) SystemClock.elapsedRealtime() - connectedAt else 0L
connectedAt = 0
attempt = ReconnectPolicy.attemptAfterDrop(attempt, up)
scheduleConnect(ReconnectPolicy.delayMs(attempt, remote, kotlin.random.Random.nextDouble()))
```
`wantsConnection()` = a paired device is selected and the last handshake was not rejected (rejected stays terminal, as in Session 2).
This replaces the fixed 1->30 s backoff (now 1 s doubling, +-20 % jitter, 30 s cap on the LAN, 60 s over the VPN; a link that lasted 20 s or more restarts the backoff).
Show `f.text` in the status line instead of the raw exception text.

## Verify
1. **Notification**: Agent running -> status bar has no icon (or a tiny one), shade shows one silent line `Ready, no controller`; connect a Controller -> text changes; lock screen shows nothing. Long-press it -> channel `Agent status` is minimised. Old channel gone from the app's notification settings.
2. **RC Reliability Setup**: Battery shows `optimised` -> button 1 -> system dialog -> after Allow the line says `unrestricted`. Test button: `Result: Agent service started`.
3. **Cold start**: Agent running -> restart the phone -> unlock once. Within seconds: Agent service is up (Controller connects, badge `LAN`/`VPN`), capture is off (expected), screen **Last boot start** in RC Reliability Setup shows `Agent service started`. If it says `Not allowed to start from the background`: Android 15+ restriction, tap the posted notification; see note below.
4. Stop the Agent on purpose, restart the phone: stays off (no notification).
5. Switch start-at-boot off, restart: stays off.
6. Controller on Wi-Fi, connected; switch Wi-Fi off with mobile data + Tailscale on: reconnects within about 1-2 s (badge changes to `VPN`) instead of waiting for the ping. Airplane mode: status `No network`, no retry spam; off again -> reconnects.
7. Kill the Agent: Controller shows the failure text (`Agent refused the connection...` or timeout hint), retries 1, 2, 4 ... s; start the Agent -> reconnects at the next retry. Keep the link up > 20 s, kill the Agent again: backoff starts at 1 s again.

## Limits (documented, not bugs)
- Screen capture never resumes after a reboot or service restart (MediaProjection consent every time).
- PIN/pattern: nothing runs before the first unlock after boot.
- Android 15+ with target SDK 35 forbids starting a `dataSync` foreground service from `BOOT_COMPLETED`. The receiver catches the exception and posts a tap-to-start notification. A permanent fix would be another foreground-service type for the listening service (e.g. `specialUse`, manifest property + `FOREGROUND_SERVICE_SPECIAL_USE`); not done here because `AgentService` was not in the upload. `MY_PACKAGE_REPLACED` after an app update is subject to the same rule.
- The self-test button runs from the foreground, so it only proves the service can start; only a real reboot proves the boot path.
- Vendor auto-start screens are best effort (component names differ per ROM and version).
- OEM task killers may still stop the Agent; battery unrestricted + the vendor screen is all an app can ask for.

## Files in this archive (copy over the project, keep paths)
shared: `ReconnectPolicy.kt`, `FailureClassifier.kt`, `AgentStatusText.kt` (new); tests `ReconnectPolicyTest.kt`, `FailureClassifierTest.kt`, `AgentStatusTextTest.kt` (new)
agent: `AutoStart.kt`, `BootReceiver.kt`, `BatteryHelper.kt`, `AgentNotification.kt`, `ReliabilitySetupActivity.kt` (new)
controller: `NetworkWatcher.kt` (new), `RouteConnector.kt` (changed: `remoteLikely()`)
docs: `README.md`, `remote-control-app-plan.md`, `SESSION10A-INTEGRATION.md`

## Versions
`versionName` 0.10.0 in both `build.gradle`s (build files not in the upload, set by hand). Session 10 is complete after 10b.

## Still open
Session 6 wiring, `SESSION7-INTEGRATION.md`, `SESSION8A-INTEGRATION.md`, `SESSION9A/9B-INTEGRATION.md`, on-device verification of 8b-10a. Session 10b: TLS, Settings screen (JPEG quality/FPS, re-pair/forget, LAN-only toggle), optional pause of streaming while the viewer is hidden.
