# Session 10b — integration (TLS, settings, stream pause, token at rest)

Builds on `SESSION10A-INTEGRATION.md` (do that first). New wire message: `VIEWER_STATE` (handled before `Protocol.decode`,
routed through `FilesHandler`, `PROTOCOL_VERSION` stays 3, older peers ignore it). TLS is a transport change: the Agent
listens on the same port 8765 but speaks `wss://`.

**Update order:** Controller first, then the Agent. A new Controller falls back to plain `ws://` for an old Agent (while
"Require encryption" is off); an old Controller cannot talk to a TLS Agent.

## Agent
Add `TlsIdentity.kt`, `SecuritySetupActivity.kt`, `StreamGate.kt`, `ViewerStateHandler.kt`. Replace `FilesHandler.kt` (3 lines added, routes `VIEWER_STATE`: no `AgentServer` hook for that).

Manifest (agent), no new permission:
```xml
<activity android:name=".SecuritySetupActivity" android:exported="true" android:label="RC Security">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity>
```
`AgentServer` (not in the upload), where the server object is created, before `start()`:
```kotlin
if (TlsIdentity.enabled(ctx)) {
    try { setWebSocketFactory(TlsIdentity.serverFactory()) }
    catch (e: Exception) { Log.e("RemoteAgent", "TLS unavailable, staying plain: $e") }   // pinned Controllers refuse plain; unpinned ones fall back
}
StreamGate.onResume = { /* replay the cached frame to all clients, same call as after HELLO_ACK */ }   // optional
```
`ScreenCapturer` (not in the upload): wherever it decides "no authenticated Controller -> do not send (keep the ~1/s cached frame)", use
`hasViewer && !StreamGate.paused()`. Paused behaves exactly like "nobody is watching".
Logcat `RemoteAgent`: `VIEWER_STATE visible=false` / `true`.

## Controller
Add `SettingsStore.kt`, `SecureDial.kt` (also holds `PinTrust`, `PinMismatchException`, `DialAttempt`), `TokenVault.kt`, `ViewerStateClient.kt`, `SettingsPanel.kt`. Replace `RouteConnector.kt` ("LAN only" filter in the plan).
No new permission. In `ConnectionManager` (names indicative; combine with the 10a code):
```kotlin
private val settings = SettingsStore(app)
private val secure = SecureDial(settings)
private var attempt: DialAttempt? = null
private val viewerState = ViewerStateClient { send(it) }

// start of a connection cycle (next to routes.begin / restart):
attempt = secure.begin(routes.key())

// dialling route r (replace the old ws:// URL and the clientFor call):
val a = attempt!!
val client = secure.client(baseClient, r, a)               // = RouteConnector.clientFor + pinned TLS
val request = Request.Builder().url(secure.url(r, a)).build()

// handshake accepted:
val encrypted = secure.accepted(a)                         // saves the pin on first use
log(if (encrypted) "Encrypted (TLS)" else "WARNING: connection is NOT encrypted")
viewerState.onConnected()
val d = settings.captureDefaults()
if (settings.applyCaptureOnConnect) sendCaptureConfig(d.fps, d.quality, d.maxDimension)   // the call the viewer's Controls panel uses

// transport failure (before the 10a failover/backoff code):
when (val o = secure.onFailure(a, t, routes.remoteLikely())) {
    is SecureDial.Outcome.Retry -> { attempt = o.attempt; dial(sameRoute); return }       // plain fallback, same route, no backoff
    is SecureDial.Outcome.Terminal -> { setStatus(o.text); stopRetrying(); return }       // like REJECTED: no retries
    SecureDial.Outcome.Normal -> { /* 10a: FailureClassifier text, routes.failed(), backoff */ }
}
```
Status/top bar: `secure.badge(a)` -> `TLS` or `PLAIN` next to the `LAN`/`VPN` badge.
Forget: also call `settings.forget(key)` (the panel does it for Forget pressed inside Settings).

`MainActivity`: add a "Settings" button that shows `SettingsPanel(this, host)` in place of the connection screen, like the other panels. `host`:
`devices()` = paired devices as `SettingsPanel.Device("host:port", "name")`; `forget(key)` = your existing Forget; `repair(key)` = remove the stored token of that device and show the Add-device form prefilled (the PIN is entered again); `close()` = remove the panel.
Viewer: `viewerState.setVisible(true)` when the viewer is shown and the app is resumed; `setVisible(false)` when it is hidden or the app is paused/stopped.

`DeviceStore` (token at rest, optional but recommended): save `TokenVault.encrypt(token)`; read `TokenVault.decrypt(stored)` (null = key lost -> treat the device as unpaired). Existing plain tokens keep working and are encrypted the next time they are saved.

## Verify
1. Agent: **RC Security** -> encryption ON, fingerprint shown; restart the Agent. Controller (new) connects: `Encrypted (TLS)`, badge `TLS`; Settings -> device shows `pinned 03:90:...` = the first 8 bytes of the Agent's fingerprint (compare by eye).
2. A plain client cannot talk to the Agent any more (e.g. `websocat ws://AGENT:8765` fails), `wss://` with `--insecure` works.
3. Agent: **Make a new certificate**, restart: Controller stops with `The Agent's certificate changed...` and does not retry. Settings -> Reset pin -> reconnects and pins the new certificate.
4. Old Agent (build before 10b) + new Controller: connects with badge `PLAIN` and a warning. Turn **Require encryption** on: refused with the explanation. Pinned device + Agent with encryption turned off: refused (no downgrade), message says so.
5. **LAN only** on, mobile data + Tailscale: does not connect through the VPN; off: does.
6. **Apply these when connecting** on, set fps 10 / quality 80 / 1080: after connect the Agent's `CAPTURE_STATUS` follows.
7. Open the viewer, press Home (or open Apps/Files): Agent log shows `VIEWER_STATE visible=false` and the stream stops (Agent battery/data); come back: picture within about a second. Kill the Controller while hidden: the Agent resumes sending on its own after at most 30 s.
8. Settings -> Re-pair opens the add-device form; Forget removes the device, its remote address and its pin.
9. With the `TokenVault` hook: `controller` prefs show `enc1:` for the token; an old pairing still connects.

## Limits (documented, not bugs)
- **Trust on first use:** the first connection pins whatever certificate answers. An attacker on the network at that moment could be pinned instead. Pair on your own Wi-Fi and compare the fingerprint (RC Security vs Settings). Inside Tailscale the tunnel is additionally encrypted and authenticated.
- A Controller without a pin may fall back to plain `ws://` while "Require encryption" is off. It is a migration aid: turn the switch on once all Agents are updated. A pinned device never downgrades.
- Cannot tell a plain Agent from a TLS failure with certainty: a connection reset during the handshake counts as "plain Agent" (only for an unpinned device).
- Regenerating the certificate (or clearing the Agent's app data / reinstalling it) invalidates every pin; each Controller must reset it.
- The pause is per Agent, not per Controller: the last report wins; the 30 s expiry covers vanished Controllers. Two Controllers watching one Agent can pause each other's stream (personal-use build: one Controller).
- Capture defaults need the Controls panel's send call (not in the upload); the pause needs the `ScreenCapturer` condition above.
- `TokenVault`: after restoring the app to a new phone the Keystore key is gone, devices must be paired again.

## Files in this archive (copy over the project, keep paths)
shared: `TlsPolicy.kt`, `SettingsLogic.kt`, `StreamGateLogic.kt`, `ViewerStateWire.kt` (new), `FailureClassifier.kt` (changed: `TLS_PIN`, `TLS_PLAIN`); tests `TlsPolicyTest.kt`, `SettingsLogicTest.kt` (also `StreamGateLogicTest`), `ViewerStateWireTest.kt` (new), `FailureClassifierTest.kt` (changed)
agent: `TlsIdentity.kt`, `SecuritySetupActivity.kt`, `StreamGate.kt`, `ViewerStateHandler.kt` (new), `FilesHandler.kt` (changed)
controller: `SettingsStore.kt`, `SecureDial.kt`, `TokenVault.kt`, `ViewerStateClient.kt`, `SettingsPanel.kt` (new), `RouteConnector.kt` (changed)
docs: `README.md`, `remote-control-app-plan.md`, `SESSION10B-INTEGRATION.md`

## Versions
`versionName` 0.10.1 in both `build.gradle`s (set by hand). Session 10 is complete after verification; the build is then the stable personal-use build.

## Still open
Session 6 wiring, `SESSION7-INTEGRATION.md`, `SESSION8A-INTEGRATION.md`, `SESSION9A/9B-INTEGRATION.md`, `SESSION10A-INTEGRATION.md`, on-device verification of 8b-10b. Ideas not done: per-Controller stream pause, TLS certificate rotation with automatic re-pin, a window of outstanding file reads over mobile data.
