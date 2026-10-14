# Session 8b — integration (the files below were not in the upload, so these edits are manual)

## Agent — `agent/src/main/AndroidManifest.xml`
Permissions (next to the existing ones):
```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
```
On `<application>` (only matters on Android 10 itself):
```xml
android:requestLegacyExternalStorage="true"
```
Inside `<application>`:
```xml
<activity android:name=".FilesSetupActivity" android:exported="true" android:label="RC Files Setup">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

## Agent — `AgentServer.onMessage`
Right after the apps hook (authenticated clients only, before `Protocol.decode`):
```kotlin
if (filesHandler.tryHandle(message) { reply -> conn.send(reply) }) return
```
with `private val filesHandler = FilesHandler(<application context>)` created once, next to `appsHandler`.
`reply` is called from a worker thread; `conn.send` is thread-safe in Java-WebSocket.

## Controller — `ConnectionManager`
Next to the `AppsHub` hook in the raw text-message handler, before `Protocol.decode`:
```kotlin
if (FilesHub.onRawMessage(text)) return
```

## Controller — UI
Add a **Files** button next to **Apps** (enabled when connected):
```kotlin
button.setOnClickListener {
    setContentView(FilesPanel(
        this,
        send = { json -> connectionManager.send(json) },
        onClose = { showConnectionScreen() },
    ))
}
```
Handle Back like the Apps panel (Back = `showConnectionScreen()`). No Controller manifest change: downloads use MediaStore (Android 10+) or the app's own folder.

## Versions
`versionName` 0.8.1 in `agent/build.gradle` and `controller/build.gradle`.

## Still open
Session 6 wiring, `SESSION7-INTEGRATION.md`, `SESSION8A-INTEGRATION.md`. Session 8c (upload + file operations) is next.
