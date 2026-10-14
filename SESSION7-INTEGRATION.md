# Session 7 — integration (the files below were not in the upload, so these edits are manual)

## Agent — `agent/src/main/AndroidManifest.xml`
Permissions (next to the existing ones):
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```
Inside `<application>`:
```xml
<receiver
    android:name=".RcDeviceAdminReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN">
    <meta-data android:name="android.app.device_admin" android:resource="@xml/rc_device_admin" />
    <intent-filter><action android:name="android.app.action.DEVICE_ADMIN_ENABLED" /></intent-filter>
</receiver>

<activity
    android:name=".WakeUnlockActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:launchMode="singleTop"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />

<activity android:name=".WakeSetupActivity" android:exported="true" android:label="RC Wake Setup">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```
(`package` of the Agent is `com.remotecontrol.agent`, so `.Name` resolves correctly.)

## Agent — `AgentServer.onMessage`
Next to the existing `INPUT_EVENT` routing, authenticated clients only, before `Protocol.decode`:
```kotlin
if (wakeHandler.tryHandle(message) { reply -> conn.send(reply) }) return
```
with `private val wakeHandler = WakeHandler(<application context>)` created once (same place that gets the context for the input service).

## Agent — `AgentService`
```kotlin
private lateinit var keeper: ConnectivityKeeper
onCreate():  keeper = ConnectivityKeeper(this); keeper.acquire()
onDestroy(): keeper.release()
```

## Controller — `ConnectionManager`
```kotlin
// raw text-message handler, before Protocol.decode:
if (WakeHub.onRawMessage(text)) return
// wherever Listener.onFrame is invoked (network thread is fine):
WakeHub.onFrame()
```
`ConnectionManager.send(json: String): Boolean` (true only when authenticated) is the same method Session 6 needs.

## Controller — UI
`parent.addView(WakeBar(context) { json -> connectionManager.send(json) })` on the connection screen (works with no viewer open)
and in the viewer's top bar.

## Still open from Session 6 (needs ViewerScreen.kt / ConnectionManager.kt / Agent manifests)
See the Session 6 "To do" in `remote-control-app-plan.md`. Not touched in Session 7.
