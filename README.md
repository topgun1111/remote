# RemoteControl — Session 8 complete (8d: resume + app icons)

Modules: `shared` (protocol), `agent` (controlled phone, WebSocket server), `controller` (WebSocket client).

## Run
1. Open this folder in Android Studio (Hedgehog or newer, JDK 17). Let Gradle sync.
2. Install `agent` on the controlled phone, `controller` on the other. Both on the same WiFi.
3. Agent: tap **Start Agent**, allow notifications. The screen shows `IP:port` and a 6-digit **PIN**.
4. Controller: under **Add device** enter the IP (and port), the PIN, tap **Pair**.
5. From then on the Controller remembers the Agent: it appears under **Paired devices**, reconnects
   automatically on launch and after network drops (backoff 1–30 s). Use **Forget** to remove it.

The PIN is single use and changes after every pairing. On the Agent, **New PIN** rotates it and
**Forget all controllers** revokes every paired Controller.

## Screen capture (Session 3)
On the Agent tap **Start screen capture** and accept the system dialog (needed again after every stop/restart).
The persistent notification changes to "Sharing screen". Frames (JPEG, default 720 px long side, 5 fps, q60)
are sent as `SCREEN_FRAME` to authenticated Controllers.
**Save test frame** writes `test_frame.jpg` to `Android/data/com.remotecontrol.agent/files/` (path in the log).
Logcat `RemoteAgent`: `Capture started WxH @5fps q60`, then every 5 s `Streaming x fps, y KB/frame`.

## Live view (Session 4)
On the Controller, connect to the Agent and tap **Open screen** (full-screen; **‹ Back** or the system Back returns,
the connection stays up). The top bar shows `WxH · N fps · KB/frame · drop N` (received, decoded frames per second).
**Controls** opens fps (1–30), JPEG quality (20–95) and frame size (480/720/1080/1440 px long side); values are sent
as `CAPTURE_CONFIG` when you lift your finger and the Agent persists them. Overlay messages: link down (frame dimmed),
Agent not capturing, waiting for first frame. Frames are only decoded while the viewer is open and the app is in the
foreground. A Controller that joins a static screen now gets the Agent's latest frame immediately.

## Input injection (Session 5)
The Agent can now execute taps, long-presses, swipes, scrolls, Back/Home/Recents/Notifications and text input
through an **AccessibilityService** (`InputAccessibilityService`). The Controller does not send these yet (Session 6);
use the debug-only **RC Input Test** launcher icon on the Agent phone to try them.
1. Install the Agent debug APK. Open **RC Input Test** -> **Open Accessibility settings** -> *Installed apps* ->
   **Remote Control Agent input** -> turn on.
2. Android 13+ (sideloaded APK): the toggle is greyed out until **App info -> ⋮ -> Allow restricted settings**
   (second button in the test app), then enable it again.
3. Press the global-action buttons (Home/Back/...) or a gesture button and switch to another app within 4 s.
   *Run sequence* does Home -> swipe up -> Back -> Notifications -> Back. Results show at the bottom of the test screen.
Logcat `RemoteAgent`: `Input control enabled`; with the service off, incoming input logs `Input ignored: enable the Agent ...`.
Wire format (JSON, `type` = `INPUT_EVENT`) is documented in `InputCommand.kt`.

## Touch mapping (Session 6, partial)
`TouchInputHandler` + `shared/TouchGesture.kt` turn touches on the live view into `INPUT_EVENT` messages; see the plan's Status Log for the remaining wiring in `ViewerScreen`.

## Remote wake (Session 7)
Controller **Wake & unlock** / **Lock** (`WakeBar`) send `WAKE`; the Agent answers `WAKE_RESULT` (screen on? locked? secure?).
Setup on the Agent phone (manual files/edits: `SESSION7-INTEGRATION.md`): open **RC Wake Setup** ->
1. Enable Device Admin (only for the remote Lock), 2. allow full-screen notifications (Android 14+), 3. set battery to Unrestricted.
While `AgentService` runs it holds a partial wake lock + Wi-Fi lock (`ConnectivityKeeper`) so the phone stays reachable with the screen off (battery cost).
**Limitation (documented, not a bug):** with a PIN/pattern/password lock the screen wakes but stays locked; only no/swipe lock or Smart Lock / a trusted device unlocks remotely.
Self-test: **RC Wake Setup -> Test: lock now, wake in 6 s**; logcat `RemoteAgent`: `WAKE unlock=... steps=[...]`, `WAKE_RESULT ok=...`.

## App launcher (Session 8a)
Controller **Apps** opens a searchable list of the Agent's launcher apps (`AppsPanel`); tap one to start it on the Agent (`LAUNCH_APP` -> `LAUNCH_RESULT`).
Setup on the Agent phone (manual files/edits: `SESSION8A-INTEGRATION.md`): open **RC Launch Setup** -> allow **Display over other apps** (lets the Agent start apps from the background).
**Limitation (documented, not a bug):** Android 10+ blocks background starts without any error, so `ok` only means "start requested"; check the live view. Without the overlay permission the Agent also posts a notification to tap. A locked secure screen still has to be unlocked first (see Remote wake).
Self-test: **RC Launch Setup -> Test: open Settings in 6 s**; logcat `RemoteAgent`: `APP_LIST -> N apps`, `LAUNCH_APP ... steps=[...]`, `LAUNCH_RESULT ok=...`. 
## File browser (Session 8b)
Controller **Files** browses the Agent's internal storage (`FilesPanel`); tap a folder to open it, tap a file to download it to the Controller's `Downloads/RemoteControl` (`FILE_LIST`/`FILE_READ`/`FILE_CHUNK`).
Setup on the Agent phone (manual files/edits: `SESSION8B-INTEGRATION.md`): open **RC Files Setup** -> **Grant file access** (Android 11+: "All files access").
Download is chunked (96 KB per request) and cancellable. Upload and file operations: see Session 8c below.
Self-test: **RC Files Setup -> Test: list internal storage**; logcat `RemoteAgent`: `FILE_LIST <dir> -> N/M`, `FILE_READ start <file> total=...`.

## File upload & operations (Session 8c)
In **Files** (Controller): **Upload** picks a file on the Controller phone and sends it into the open folder on the Agent (chunked, 96 KB, acknowledged per chunk, **Cancel transfer**).
If the name already exists you are asked to replace it; a file is only replaced after the new one arrived completely (hidden `.name.rcpart` temp file, renamed at the end).
**New folder** creates a folder in the open folder. **Long-press** an item: Open/Download, **Rename**, **Delete** (confirmation; folders are deleted with everything in them; symlinks are removed, never followed).
Storage roots now also include an SD card / USB volume when mounted (listed next to "Internal storage").
Setup: no new permissions. The Controller Activity needs the file-picker wiring in `SESSION8C-INTEGRATION.md`. Writing needs the same "All files access" as browsing.
Self-test: **RC Files Setup -> Test: write, rename, delete** (creates and removes `RC_selftest` in the storage root); logcat `RemoteAgent`: `FILE_WRITE start/done ...`, `FILE_OP mkdir|rename|delete ...`.

## Resume & app icons (Session 8d — completes Session 8)
**Resume download:** a download is kept as a partial file on the Controller (`Android/data/com.remotecontrol.controller/files/Download/partial/`) until it is complete, then copied to `Downloads/RemoteControl`.
Cancel, a lost link or closing the panel keep the partial; tap the same file again and it continues ("Resuming ... at X"). The partial is tied to the file's path + size + modified time, so a changed file starts over. Partials older than 7 days are removed.
**Resume upload:** a timeout or lost link keeps the Agent's hidden `.name.rcpart` and a record of the file on the Controller. Pick the same file again into the same folder: the Controller asks the Agent how much it has (`FILE_OP partial`) and continues from there. A refusal, **Cancel transfer** or a read error discards the partial. The Agent also removes `.rcpart` files older than 7 days when it lists the folder.
**App icons:** `AppIconLoader` fetches launcher icons on demand (`APP_ICON` -> `APP_ICON_RESULT`, 96 px PNG, 3 requests in flight, memory cache). Needs the small `AppsPanel` edit in `SESSION8D-INTEGRATION.md`; without it the app list simply stays text-only.
Self-test: logcat `RemoteAgent`: `APP_ICON <pkg> failed` only on errors; Controller: icons appear in the Apps list while scrolling.

## Verify (logcat)
- Agent filter `RemoteAgent`: `HELLO from ... (pin|token)` then `Sent HELLO_ACK accepted=true`
- Controller filter `RemoteController`: `Paired with '...'` (first time), `Handshake OK with '...'`

## Tests
`./gradlew :shared:test` runs the pairing/protocol unit tests (pure JVM, no device needed).

## GitHub Actions
`.github/workflows/build.yml` builds both debug APKs on every push/PR and uploads them as
artifacts (Actions tab -> run -> Artifacts: `agent-debug-apk`, `controller-debug-apk`).
Push a tag like `v0.1` to also attach the APKs to a GitHub Release.
Debug APKs are signed with the auto-generated debug key, so they sideload directly.
