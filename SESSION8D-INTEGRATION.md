# Session 8d — integration

Agent: replace `FilesHandler.kt`, add `AppIconsHandler.kt`. No `AgentServer` change (the `filesHandler.tryHandle(...)` hook
also answers `APP_ICON`; make sure it runs for authenticated clients before `Protocol.decode`, as in 8b). No manifest change:
icons use the package visibility you already set up for the app list in 8a (`<queries>`).
Controller: replace `FilesClient.kt`, `FilesHub.kt`, `FilesPanel.kt`, add `AppIconLoader.kt`. The `ConnectionManager` hook is unchanged.
The 8c file-picker wiring in the Activity is unchanged.

## Controller — icons in `AppsPanel` (the only manual edit; `AppsPanel.kt` was not in the upload)
1. Create the loader next to the other fields, with the same `send` lambda the panel already uses:
```kotlin
private val icons = AppIconLoader(send)          // `send` = the (String) -> Boolean passed to AppsPanel
```
2. Start/stop with the panel:
```kotlin
override fun onAttachedToWindow() { super.onAttachedToWindow(); icons.start(); /* existing code */ }
override fun onDetachedFromWindow() { icons.stop(); /* existing code */ super.onDetachedFromWindow() }
```
3. Give each row an `ImageView` (about 40 dp) left of the label and bind it in `getView`:
```kotlin
icons.bind(iconView, app.pkg)       // `pkg` = APP_LIST_RESULT "pkg" of that row
```
`bind` clears the view, then fills it when the icon arrives (rows recycled for another package are skipped).
Until this edit is made the app list is unchanged (text only).

## Files in this archive (copy over the project, keep paths)
shared: `FilesWriteWire.kt` (changed), `AppIconsWire.kt` (new); tests `FilesWriteTest.kt` (changed), `AppIconsWireTest.kt` (new)
agent: `FilesHandler.kt` (changed), `AppIconsHandler.kt` (new)
controller: `FilesClient.kt`, `FilesHub.kt`, `FilesPanel.kt` (changed), `AppIconLoader.kt` (new)
docs: `README.md`, `remote-control-app-plan.md`

## Versions
`versionName` 0.8.3 in `agent/build.gradle` and `controller/build.gradle`.

## Still open
Session 6 wiring, `SESSION7-INTEGRATION.md`, `SESSION8A-INTEGRATION.md`. After on-device verification of 8b-8d, Session 8 is done; Session 9 (remote access beyond LAN) is next.
