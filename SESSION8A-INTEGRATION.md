# Session 8a — integration (the files below were not in the upload, so these edits are manual)

## Agent — `agent/src/main/AndroidManifest.xml`
Permission (next to the existing ones):
```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```
(`POST_NOTIFICATIONS` and `USE_FULL_SCREEN_INTENT` are already there from Sessions 2/7.)

Directly inside `<manifest>` (sibling of `<application>`), needed to see launcher apps on Android 11+:
```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```
Inside `<application>`:
```xml
<activity android:name=".LaunchSetupActivity" android:exported="true" android:label="RC Launch Setup">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

## Agent — `AgentServer.onMessage`
Right after the WAKE hook, authenticated clients only, before `Protocol.decode`:
```kotlin
if (appsHandler.tryHandle(message) { reply -> conn.send(reply) }) return
```
with `private val appsHandler = AppsHandler(<application context>)` created once, next to `wakeHandler`.
`reply` may be called from a worker thread (app list); `conn.send` is thread-safe in Java-WebSocket.

## Controller — `ConnectionManager`
Next to the `WakeHub` hook in the raw text-message handler, before `Protocol.decode`:
```kotlin
if (AppsHub.onRawMessage(text)) return
```
Optional: call `AppsClient.clearCache()` when the user switches to another paired Agent.

## Controller — UI
Add an **Apps** button to the connection screen (enabled when connected) and, optionally, to the viewer's top bar:
```kotlin
button.setOnClickListener {
    setContentView(AppsPanel(
        this,
        send = { json -> connectionManager.send(json) },
        onClose = { showConnectionScreen() },          // whatever restores the screen you came from
        onLaunched = { showViewer() },                 // optional: jump to the live view to see the app open
    ))
}
```
If your Activity handles the system Back button for the viewer, do the same for the panel (Back = `showConnectionScreen()`).

## Versions
`versionName` 0.8 in `agent/build.gradle` and `controller/build.gradle`.

## Still open
Session 6 wiring (see `remote-control-app-plan.md`) and everything in `SESSION7-INTEGRATION.md`. Session 8b (file browser + transfer) is next.
