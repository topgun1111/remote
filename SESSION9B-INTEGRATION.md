# Session 9b — integration (remote access: Agent side + auto-fill)

Builds on `SESSION9A-INTEGRATION.md` (do that first). New wire messages: `ADDRESSES` / `ADDRESSES_RESULT` (handled before
`Protocol.decode`, `PROTOCOL_VERSION` stays 3). Older peers just ignore them.

## Agent
Replace `FilesHandler.kt` (two lines added: it also routes `ADDRESSES`, so **no `AgentServer` change**, same trick as `APP_ICON`).
Add `AddressesHandler.kt` and `RemoteSetupActivity.kt`.

Manifest (agent):
```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>   <!-- if not there yet -->
<queries><package android:name="com.tailscale.ipn"/></queries>               <!-- inside the existing <queries> block -->
<activity android:name=".RemoteSetupActivity" android:exported="true" android:label="RC Remote Setup">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
</activity>
```
Check once: `AgentServer` must listen on all interfaces (`InetSocketAddress(port)`, not a Wi-Fi IP); the self-test in **RC Remote Setup** tells you
(a VPN address answering `refused` while the Agent runs = bound to one interface). If the port is ever not 8765, set `AddressesHandler.listenPort`.

## Controller
Replace `FilesHub.kt`, `RouteStore.kt`, `RouteConnector.kt` (9a files, extended). Add `AddressesClient.kt`.
In `ConnectionManager` (same place as the other clients):
```kotlin
private val addresses = AddressesClient({ send(it) }, routeStore).also { it.start() }     // once; routeStore = the RouteStore from 9a
// right after the handshake was accepted (next to routes.succeeded()):
addresses.request(routes.key()) { o ->
    if (o.applied) log("Remote address saved from the Agent: ${o.suggestion!!.first}")
    // optional: if (!o.applied && o.suggestion != null && o.suggestion != routeStore.remote(routes.key())) offer it in the UI
}
```
Status/viewer: show `routes.badge()` (`LAN` / `VPN`) next to the connection status or in the viewer's top bar.
`RouteConnector.clientFor` now also sets the ping interval (10 s LAN, 15 s VPN). If your base client does not use OkHttp's `pingInterval`
(own ping timer), set that timer from `RouteConnector.PING_REMOTE_S` / `PING_LAN_S` instead.

Behaviour: the Agent's VPN address is saved only if the device has no remote address and you did not press **Clear** in the Remote dialog
(Clear switches auto-fill off for that device until you save an address again). An existing address is never overwritten.

## Verify (mobile-data test = the Session 9 deliverable)
1. Agent phone: Tailscale on, Agent started. **RC Remote Setup**: shows `VPN  100.x.y.z`, Test says `OK` for the VPN and LAN rows.
2. Controller on the home Wi-Fi: connect -> badge `LAN`; Remote dialog now holds the Agent's `100.x.y.z` (auto-filled).
3. Controller: Wi-Fi off, mobile data + Tailscale on -> reconnects -> badge `VPN`; live view, wake, Apps and Files work. Large file transfers are slower (pull-based chunks).
4. Controller Tailscale off -> all routes fail -> backoff retries (status shows the attempts); turn it on -> reconnects.
5. Agent screen off for some minutes with Tailscale + Agent battery Unrestricted: Controller can still wake it (if not, see battery notes below).
6. Press **Clear** in the Remote dialog, reconnect: stays empty.

Logcat `RemoteAgent`: `ADDRESSES -> VPN:100.x.y.z LAN:192.168.x.y port=8765`.
Battery: Tailscale must be Unrestricted and (best) Always-on VPN, or Android may stop it in Doze; the Agent's own wake lock (7) does not keep Tailscale alive.

## Files in this archive (copy over the project, keep paths)
shared: `AddressesWire.kt` (new); test `AddressesWireTest.kt` (new)
agent: `FilesHandler.kt` (changed), `AddressesHandler.kt`, `RemoteSetupActivity.kt` (new)
controller: `FilesHub.kt`, `RouteStore.kt`, `RouteConnector.kt` (changed), `AddressesClient.kt` (new)
docs: `README.md`, `remote-control-app-plan.md`, `SESSION9B-INTEGRATION.md`

## Versions
`versionName` 0.9.0 in `agent/build.gradle`; controller 0.9.0 from 9a (build files not in the upload, set by hand). Session 9 is complete after verification.

## Still open
Session 6 wiring, `SESSION7-INTEGRATION.md`, `SESSION8A-INTEGRATION.md`, `SESSION9A-INTEGRATION.md`, on-device verification of 8b-9b. Session 10 (TLS, hardening) is next.
