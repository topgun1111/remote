# Android Remote Control App — Project Plan & Status

> **If you are an AI assistant picking this up in a new session**: read this entire file before writing any code. It contains full project context, not just a task list. Check the "Status Log" at the bottom first to see what's already been done, then find the next unchecked session.

## Project Summary

Building two custom Android apps, for personal use only, across two phones owned by the same person (not for distribution/Play Store):

- **Agent app** — installed on the phone that will *be controlled*.
- **Controller app** — installed on the phone *doing the controlling*.

Goal: view the Agent phone's screen live on the Controller phone, tap/control it remotely, launch apps on it, browse/transfer files, and wake its screen remotely — working both on local WiFi and from outside the home network. Both apps are manually sideloaded (no Play Store constraints to worry about), both phones belong to the same user, so no stealth/consent-bypass requirements — all permissions (Accessibility, Device Admin, MediaProjection) are granted manually and knowingly on the Agent phone during setup.

## Why custom-built

Existing tools (AirDroid, Vysor, scrcpy, TeamViewer QuickSupport) cover parts of this, but the user wants a single self-contained app combining all of screen view + input control + app launch + remote wake + file transfer + works-off-LAN, tailored to their own setup.

## Hard Constraints (don't relitigate these)

- Both devices are the same person's own phones. Manual install on both. No need to disguise permission prompts or hide the app.
- Android requires a **visible persistent notification** for MediaProjection (screen capture) and most background services — this cannot be hidden regardless of approach. Treat this as fixed, not a bug to solve around.
- Remote "wake screen" without root works via WAKE_LOCK + Device Admin, and only unlocks cleanly if there's no secure lockscreen, or a trusted-device/smart-lock setup is used. This is a documented limitation, not something to re-investigate each session.
- No root assumed unless a later session explicitly decides to require it for a specific feature.

## Architecture

- **Agent app** (controlled phone): MediaProjection (screen capture) → AccessibilityService (input injection) → Device Admin + WakeLock (remote wake) → file access → WebSocket server/client → foreground service tying it together.
- **Controller app** (your phone): decodes/renders incoming screen frames → captures local touches and maps them to Agent's coordinate space → sends commands → file browser UI → app launcher UI → connection/pairing management.
- **Relay server** (NOT built: Session 9 chose a Tailscale VPN mesh; kept only as a fallback idea): minimal WebSocket relay, deployed separately (e.g. small VPS), to allow Controller and Agent to find each other off-LAN.

## Tech Decisions

Record final decisions here as they're made — treat this list as the source of truth, overriding anything said in earlier chat conversations that isn't repeated here.

| Decision | Status | Value |
|---|---|---|
| Language | ✅ decided | Kotlin |
| Min SDK | ✅ provisional | 26 (Android 8.0), target/compile 34. Raise/lower if a phone needs it |
| Networking transport | ✅ decided | WebSocket, plain ws:// for now. Agent = Java-WebSocket 1.5.7 server, Controller = OkHttp 4.12 client. TLS in Session 10 |
| Screen encoding | ✅ decided | JPEG frames (base64 in JSON) to start |
| Remote access method | ✅ decided (Session 9) | VPN mesh (Tailscale) on both phones; no relay. The Agent is dialled at its `100.x.y.z` / MagicDNS address; Controller tries LAN first on the Agent's Wi-Fi, remote first elsewhere. Reason: a relay needs a server holding WebSockets open (InfinityFree cannot), a VPS is extra cost and code. Relay stays a fallback idea only if Tailscale is ruled out |
| Pairing/security | ✅ decided (Session 2) | 6-digit single-use PIN → Agent issues a random 256-bit token; Controller stores it and sends it in `HELLO` from then on. Agent stores only the SHA-256 of each token. PIN regenerates after each pairing and after 5 wrong guesses. Transport encryption still TBD (Session 10) |
| QR pairing | ⏭ deferred | Session 2 shipped PIN only (no camera/zxing dependency). Revisit in Session 10 if wanted |

**Do not start Session 1 until at minimum Min SDK and screen encoding are decided** — they affect code written from the first line.

## Wire Protocol (message types — expand as needed, keep this list updated)

Message types for the Controller↔Agent protocol (JSON text frames, discriminator field `type`). `PROTOCOL_VERSION` is currently **3** (bumped in Session 3); the Agent rejects a mismatched `HELLO`. An Agent closes any connection whose first message is not a valid `HELLO`.
- `HELLO` — initial handshake/auth (Controller → Agent). Fields: `protocolVersion`, `deviceName`, and exactly one of `pin` (first pairing) or `token` (already paired)
- `HELLO_ACK` — handshake reply (Agent → Controller). Fields: `accepted`, `deviceName`, `token` (only on successful PIN pairing), `error`, `needsPairing` (true = retrying with the same credentials is pointless)
- `SCREEN_FRAME` — encoded screen frame data (Agent → Controller): `width`, `height`, `jpegBase64`, `timestampMs`
- `CAPTURE_CONFIG` — (Session 3, Controller → Agent) `fps` 1–30, `quality` 20–95, `maxDimension` 240–1920 (long side, px); Agent clamps and persists
- `CAPTURE_STATUS` — (Session 3, Agent → Controller) `active`, `width`, `height`, `fps`, `quality`; sent after HELLO_ACK and on every start/stop/config change
- `INPUT_EVENT` — tap/swipe/scroll/key event; coordinates normalized 0..1 of the frame. **Session 5 wire format** (parsed by the Agent in `InputCommand.kt`, before `Protocol.decode`): `{"type":"INPUT_EVENT","action":"TAP|LONG_PRESS|SWIPE|SCROLL|KEY|TEXT", ...}` — TAP `x,y`; LONG_PRESS `x,y,durationMs?`; SWIPE `x,y,x2,y2,durationMs?`; SCROLL `x?,y?,dx,dy` (content movement, dy>0 = down); KEY `key` (BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, POWER_DIALOG, SCREENSHOT, BACKSPACE, ENTER); TEXT `text`; optional `frameW,frameH` of the frame the user touched
- `WAKE` — (Session 7, Controller → Agent) `{"type":"WAKE","unlock":true|false}` wake (+ dismiss keyguard) or `{"type":"WAKE","lock":true}` turn screen off (Device Admin). Handled before `Protocol.decode`, like `INPUT_EVENT`
- `WAKE_RESULT` — (Session 7, Agent → Controller) `ok`, `screenOn`, `locked`, `secure`, `admin`, `detail`
- `APP_LIST` — (Session 8a, Controller → Agent) `{"type":"APP_LIST"}` request the launchable apps
- `APP_LIST_RESULT` — (Session 8a, Agent → Controller) `{"type":"APP_LIST_RESULT","apps":[{"pkg","label","system"}]}` (no icons yet)
- `LAUNCH_APP` — (Session 8a, Controller → Agent) `{"type":"LAUNCH_APP","package":"com.example.app"}`; handled before `Protocol.decode`, like `WAKE`
- `LAUNCH_RESULT` — (Session 8a, Agent → Controller) `ok` (start dispatched without error, NOT proof the app is in front), `package`, `label`, `via` (`NONE`/`DIRECT`/`DIRECT+NOTIFICATION`/`NOTIFICATION`), `detail`
- `FILE_LIST` / `FILE_LIST_RESULT` — (Session 8b) directory listing request/response; path `""` = the roots; handled before `Protocol.decode`
- `FILE_READ` / `FILE_CHUNK` — (Session 8b, download) pull-based: Controller asks for `path`+`offset`+`len`, Agent answers one base64 chunk (`total`, `eof`); handled before `Protocol.decode`
- `FILE_WRITE` / `FILE_WRITE_ACK` — (Session 8c, upload) Controller pushes one base64 chunk (`path`,`offset`,`data`,`eof`,`overwrite`,`abort`), Agent acks each (`offset` = bytes stored, `done`); temp file `.name.rcpart` renamed on `eof`; handled before `Protocol.decode`
- `APP_ICON` / `APP_ICON_RESULT` — (Session 8d) `{"type":"APP_ICON","package","size"}` -> `{"type":"APP_ICON_RESULT","package","ok","png"(base64)}`; one icon per request; handled before `Protocol.decode`
- `FILE_OP` / `FILE_OP_RESULT` — (Session 8c; 8d adds `op:"partial"` and result field `size`) `op` = `mkdir` (path = parent, name = new folder) / `delete` (recursive, never follows symlinks) / `rename` (path = item, name = new name); handled before `Protocol.decode`

- Session 9a adds **no** wire messages (protocol still v3).
- `ADDRESSES` / `ADDRESSES_RESULT` — (Session 9b) `{"type":"ADDRESSES"}` -> `{"type":"ADDRESSES_RESULT","port","vpn","addrs":[{"ip","iface","kind":"LAN|VPN|OTHER"}]}`; `kind` is decided by the Agent from Android's VPN networks (not from the 100.64/10 range, which carriers also use); the Controller saves the first VPN address as the device's remote address when none is set; handled before `Protocol.decode`

## Rough Size Estimate

~5,000–8,000 total lines across both apps for a full-featured build (basis: Agent ~1,500–2,500, Controller ~1,200–2,000, relay ~300–600, plus polish). Each session below targets roughly 1,000–1,500 new lines.

---

## Session Plan

Each session is meant to be a self-contained unit of work completable in one sitting. Do not skip ahead — later sessions assume earlier ones are functionally complete, not just started.

### Session 1 — Project Scaffolding & Networking Foundation
- Create Android Studio project with two modules: `agent` and `controller`, plus a shared module for common data classes (message schemas, command types).
- Define the wire protocol concretely (see Wire Protocol section above) as serializable data classes (JSON recommended to start).
- Implement a basic WebSocket server on Agent, WebSocket client on Controller.
- Get a bare handshake working on LAN: Controller connects to Agent by IP:port, exchanges `HELLO`, logs success.
- **Deliverable**: both apps install; Controller connects to Agent on same WiFi; proof via logcat only, no real UI yet.

### Session 2 — Pairing & Connection UI
- Minimal UI: Agent displays its IP/port + a PIN or QR code; Controller has an "add device" screen to enter/scan it.
- Shared-secret pairing: PIN exchanged once, stored, used to authenticate future connections.
- Reconnect logic: Controller retries if Agent drops; Agent survives app restarts.
- Persist paired device info on both sides (SharedPreferences or simple file).
- **Deliverable**: phones pair once; Controller remembers Agent and reconnects automatically on next launch.

### Session 3 — Screen Capture (Agent Side)
- MediaProjection permission flow on Agent.
- Capture frames via MediaProjection + ImageReader/VirtualDisplay.
- Encode as JPEG (per the Tech Decisions table) at a controllable interval/quality.
- Stream frames to Controller as `SCREEN_FRAME` messages.
- Add the required foreground service + persistent notification.
- **Deliverable**: Agent captures and sends real screen frames; verified via logging or save-to-file test.

### Session 4 — Screen Rendering (Controller Side)
- Receive and decode `SCREEN_FRAME` messages; render into an ImageView/Canvas/SurfaceView.
- Handle frame rate and dropped frames gracefully.
- Scale rendered image to Controller's screen, preserving aspect ratio.
- Add adjustable FPS/quality controls.
- **Deliverable**: live (even if choppy) view of Agent's screen on Controller.

### Session 5 — Input Injection (Agent Side)
- AccessibilityService on Agent (user enables manually in Settings — required, no workaround).
- Handle tap, long-press, swipe, scroll, back/home/recents, text input commands.
- Map incoming `INPUT_EVENT` coordinates (Controller's scaled space) to Agent's real screen coordinates.
- **Deliverable**: Agent executes hardcoded test input commands successfully via AccessibilityService.

### Session 6 — Touch Mapping (Controller Side) & End-to-End Control
- Capture touch events on Controller's rendered screen view (tap/swipe/long-press gesture detection).
- Convert local coordinates to Agent's coordinate space (using scaling from Session 4).
- Send `INPUT_EVENT` messages over the live connection.
- Wire the full loop together: tap on Controller → visible action on Agent.
- **Deliverable**: full remote-control loop working on LAN.

### Session 7 — Remote Wake & Screen State
- Device Admin registration on Agent (granted manually once).
- WAKE_LOCK handling triggered by a `WAKE` command.
- Keyguard dismissal logic (document the no-secure-lockscreen / trusted-device limitation — don't re-debate it).
- "Wake & unlock" control on Controller UI, confirms success via resumed screen stream.
- **Deliverable**: Controller can wake Agent's screen from sleep/lock, within documented constraints.

### Session 8 — App Launching & File Browser (split in three: **8a** = app list + `LAUNCH_APP`, done; **8b** = file browser + chunked download, code written; **8c** = upload + file operations + SD roots, code written; **8d** = resume + app icons, code written -> Session 8 complete)
- Agent: list installed apps (PackageManager query), send to Controller. (8a)
- `LAUNCH_APP` command: Agent starts given package via Intent. (8a)
- File browser: Agent exposes directory listings (scoped-storage aware), Controller requests listings. (8b)
- Chunked file download using `FILE_LIST`/`FILE_READ`/`FILE_CHUNK`. (8b)
- Chunked upload (Controller -> Agent, `FILE_WRITE`/`FILE_WRITE_ACK`), plus polish: new folder / delete / rename with confirmation, resume of interrupted downloads, optional app icons, more storage roots (SD card). (8c)
- **Deliverable**: Controller can list + launch Agent's apps, and browse/transfer files both ways.

### Session 9 — Remote Access Beyond LAN (split in two: **9a** = Controller-side routing, code written; **9b** = Agent-side helper + auto-fill + verification, code written -> Session 9 complete after on-device verification)
Approach decided: **VPN mesh (Tailscale)**, no relay (see Tech Decisions).
- **9a (Controller):** per-device optional remote address (stored in `routes` prefs), `RouteLogic.plan` orders the routes (LAN first when the Controller is on the Agent's subnet, remote first otherwise, LAN as a 1.5 s last resort), `RouteSession` fails over on transport errors only (never after a rejected handshake), `RouteConnector` hooks into `ConnectionManager`, `RouteDialog` to set the address, `LocalNetwork` reads the phone's own Wi-Fi addresses. Pure logic + tests in `shared`. Manual wiring: `SESSION9A-INTEGRATION.md`.
- **9b (Agent + auto-fill, code written):** `ADDRESSES`/`ADDRESSES_RESULT` message (see Wire Protocol), `AddressesHandler` on the Agent (routed through `FilesHandler`, no `AgentServer` change), "RC Remote Setup" launcher (addresses, Tailscale installed?, per-address connect self-test = bind-all-interfaces check, shortcuts to VPN and battery settings), `AddressesClient` on the Controller (auto-fill of the remote address; never overwrites; respects Clear), LAN/VPN status badge, ping 15 s over the VPN. Mobile-data verification checklist in `SESSION9B-INTEGRATION.md`.
- **Deliverable**: Controller reaches Agent from outside home WiFi (mobile data test).

### Session 10 — Hardening, Security & Polish (split in two: **10a** = reliability, code written; **10b** = security + settings, code written -> Session 10 complete after on-device verification)
- **10a (reliability):** connection timeout/error handling, auto-reconnect with backoff, battery-optimization exemption prompts, unobtrusive persistent notification, cold-start recovery (boot start) with its documented limits.
- **10b (security + settings, code written):** TLS over WebSocket with a self-signed Keystore certificate pinned by the Controller on first use (plain fallback only for unpinned devices, switchable off); pairing tokens encrypted at rest; settings screen (LAN-only toggle, require-encryption toggle, capture defaults fps/quality/size, per-device pin reset / re-pair / forget); pause of the stream while the viewer is hidden.
- **Deliverable**: stable personal-use build, ready for daily use.

---

## Remaining Work (as of Session 11)

All source code is written and wired. The app is **not verified** until the items below are done. Order matters.

### 1. Missing sources — resolved
All Controller and Agent sources are in the tree now (rebuilt where an upload was missing, see Status Log).

### 2. Session 6 wiring — done in Session 11
`ViewerScreen` sets a `TouchInputHandler` on `ScreenView`, has Back / Home / Recents buttons, a text field with Backspace / Enter, `WakeBar` and the link badge; `ConnectionManager.send(String)` exists. The Agent's debug-only `<service>` problem is gone (merged manifest). `InputEvent` in `Protocol.kt` is not used on the wire (the Agent parses `INPUT_EVENT` with `org.json`); leave it or delete it.

### 3. Integration notes — applied
All `SESSION*-INTEGRATION.md` edits are in the code (`MainActivity`: Apps / Files / Settings / Wake, Remote address dialog, file picker, viewer visibility; `AppsPanel`: icons). Still to check on a device: the 8b/8c/9a notes' checklists.

### 4. Build
- Run CI: nothing Android-side has ever been compiled (only the pure `shared` logic and its tests were run locally). Expect compile errors to fix.
- Set `versionName` by hand: agent and controller 0.10.1 (build files not in the uploads).
- `./gradlew :shared:test` must pass (includes `ViewerStateWireTest`, not run locally).

### 5. On-device verification (never done for 8b to 10b)
Run the checklist in each integration note on two real phones: files (8b to 8d), remote access over mobile data (9), reliability and cold start (10a), TLS pin / settings / stream pause (10b).

### 6. Known limits (documented, not bugs)
- Screen capture cannot resume after reboot or service restart (MediaProjection consent every time).
- PIN/pattern: nothing starts before the first unlock after boot.
- Android 15+ (target SDK 35) may refuse a `dataSync` foreground service start from `BOOT_COMPLETED`; tap-to-start notification is the fallback.
- TLS is trust-on-first-use; regenerating the Agent certificate invalidates all pins.
- Stream pause is per Agent (last report wins).
- Pull-based file transfer is slow over mobile data.
- IPv4 only; release signing not set up (debug APKs only).

### 7. Optional follow-ups (not planned)
Window of 2 to 4 outstanding file reads over slow links; per-Controller stream pause; automatic re-pin on certificate rotation; Agent foreground-service type that is allowed at boot on Android 15 (e.g. `specialUse`); release signing.

---

## Status Log

> Update this section at the end of every working session — what was actually finished, any deviations from the plan above, and any new decisions made. Future sessions should treat this log as more authoritative than the session descriptions above if they ever conflict.

- **Session 1 — code written (needs on-device verification).** Project `RemoteControl/` with modules `shared` (Kotlin JVM, kotlinx.serialization protocol incl. all planned message types), `agent` (foreground service + Java-WebSocket server on port 8765, answers HELLO with HELLO_ACK), `controller` (OkHttp client, minimal IP field + log screen). Deviations: added `HELLO_ACK`; input coordinates are normalized 0..1; minimal start/connect screens added (plan said logcat-only). Plain `android.app.Activity`, no AndroidX. Next: confirm handshake in logcat, then Session 2.
- **CI / GitHub setup.** Added Gradle 8.7 wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`), `.github/workflows/build.yml` (JDK 17, builds `assembleDebug` for agent + controller on push/PR/manual, uploads `agent-debug-apk` and `controller-debug-apk` artifacts, attaches APKs to a GitHub Release on `v*` tags), `.gitattributes`, extended `.gitignore`, README CI section. Debug-signed APKs only; release signing not set up.
- **Session 1 files restored (CI zip was missing them).** The `ci-changes-1` zip only contained CI/wrapper files, so the Session 1 module sources and `gradle/wrapper/gradle-wrapper.properties` (missing from that zip, so `./gradlew` could not run) were re-created from the Session 1 description above. If you have the original Session 1 sources, diff them against these before merging. Build config: AGP 8.3.2, Kotlin 1.9.24, kotlinx.serialization 1.6.3, Gradle 8.7, JDK 17.
- **Session 2 — code written (needs on-device verification; not compiled in the authoring sandbox — first CI run is the real compile check).**
  - `shared`: `Pairing.kt` (`PairingAuthority`, `KeyValueStore`, `AuthResult`) holds all PIN/token logic as pure JVM; `Protocol.kt` bumped to v2 (`Hello.pin/token`, `HelloAck.token/error/needsPairing`). Unit tests in `shared/src/test/.../PairingAuthorityTest.kt` (run with `./gradlew :shared:test`); CI now runs them before building APKs (step added to `build.yml`).
  - `agent`: `AgentServer` enforces HELLO-first and PIN/token auth; `AgentService` is a sticky foreground service that remembers "should be running" and is restarted by `MainActivity` on next launch; UI shows `IP:port`, the PIN, paired count, New PIN, Forget all controllers, and an activity log. State persists in SharedPreferences (`agent_pairing`, `agent_settings`).
  - `controller`: `DeviceStore` (paired devices in SharedPreferences, JSON), `ConnectionManager` (pair with PIN, connect with token, exponential-backoff reconnect 1→30 s, 10 s ping to detect dead links), UI with paired-device list (Connect/Forget), Add device form (IP, port, PIN), log. Auto-connects to the last-used Agent on launch.
  - Behavior notes: a **rejected** handshake (wrong PIN, revoked token) is not retried — status shows `REJECTED` with the reason. A failed **first pairing** attempt is not retried either; only already-paired devices auto-reconnect. Controller token is stored unencrypted in app-private prefs (Keystore in Session 10). Agent does not auto-start on phone reboot (Session 10).
  - Deviations from plan: no QR code (PIN only); no separate "add device" screen — it is a section of the main screen.
  - Next: verify on devices (pair, kill/restart both apps, toggle WiFi, Forget all on Agent → Controller shows REJECTED), then Session 3 (screen capture).

- **Session 3 — code written (needs on-device verification; not compiled in the authoring sandbox — first CI run is the real compile check).**
  - `shared`: protocol v3 — `CaptureConfig`, `CaptureStatus`; `CaptureProtocolTest` added.
  - `agent`: new `ScreenCapturer` (MediaProjection → VirtualDisplay → ImageReader RGBA_8888 → JPEG → base64 `SCREEN_FRAME`). Capture size = real screen scaled to `maxDimension` (default 720), fps/quality default 5 / 60, persisted in `agent_settings`. Frames are throttled, skipped when no Controller is authenticated, and dropped per-client when the socket has buffered data (no queue growth). Rotation handled via `AgentService.onConfigurationChanged` → ImageReader swap + `VirtualDisplay.resize`.
  - `AgentService`: stays `dataSync` FGS; on the capture intent it re-calls `startForeground` with `dataSync|mediaProjection` **before** `getMediaProjection` (Android 14 requirement), and drops back to `dataSync` when capture ends. Manifest: `FOREGROUND_SERVICE_MEDIA_PROJECTION`, type `dataSync|mediaProjection`. MediaProjection token is single-use on Android 14 → each start re-prompts consent.
  - `MainActivity`: Start/Stop screen capture button (consent dialog), capture status line, **Save test frame** (writes `test_frame.jpg` to the app's external files dir; path shown in the log) — this is the save-to-file verification.
  - Behavior notes: capture does not auto-resume after app/service restart (consent can't be persisted). A static screen produces no new frames (VirtualDisplay only delivers on change) — expected. Controller (unchanged this session) ignores `SCREEN_FRAME`/`CAPTURE_*` until Session 4.
  - Next: verify (Start Agent → Start screen capture → pair Controller → logcat `RemoteAgent` shows `Streaming x fps, y KB/frame`; Save test frame → pull `test_frame.jpg`), then Session 4 (rendering on Controller).

- **Session 4 — code written (needs on-device verification; not compiled in the authoring sandbox — first CI run is the real compile check).**
  - `shared`: `Geometry.kt` (`FitRect`, `FrameGeometry.fit` / `toNormalized`) — pure-JVM letterbox math used by rendering now and by touch mapping in Session 6; `FrameGeometryTest` added (CI already runs `:shared:test`). Protocol unchanged (still v3).
  - `controller`: `ScreenView` (draws the frame fit-to-view, aspect preserved; exposes `frameFit` + `toNormalized(x, y, clamp)` for Session 6), `FrameRenderer` (latest-frame-wins: base64 + JPEG decode on one background thread, older pending frame replaced and counted as dropped, discards frames while disabled), `ViewerScreen` (full-screen viewer: overlay for link down / capture off / waiting, stats line, Controls panel for fps / quality / frame size → `CAPTURE_CONFIG` sent on slider release). `ConnectionManager` gained `Listener.onFrame` (called on the network thread, bypasses the main-thread post; `generation` is now `@Volatile`). `MainActivity` swaps between the connection screen and the viewer (Open screen button, Back, immersive mode, keep-screen-on); frames are decoded only while the viewer is shown and the activity is resumed.
  - `agent` (small fix, deviation from plan): a static screen sends no images, so a Controller joining or reconfiguring saw nothing until the screen changed. `ScreenCapturer` now caches the newest encoded frame (refreshed ~1/s even with no viewer connected) and `requestKeyframe()` returns it; `AgentServer` replays it after HELLO_ACK and after a `CAPTURE_CONFIG`, and the next real frame bypasses the fps throttle. Limitation: with no viewer connected, the replayed frame can be up to ~1 s behind the last change.
  - Behavior notes: the viewer's size button infers the current size from the long side of `CAPTURE_STATUS` width/height (snapped to the nearest preset). The Agent can't be told to pause streaming while the viewer is hidden (no such message yet) — the Controller just drops those frames; consider a pause/resume message in Session 10. Last frame is kept (dimmed) while reconnecting, cleared on `IDLE`/`REJECTED` or when capture stops. Versions bumped to 0.4 (agent + controller).
  - Next: verify (Agent: Start Agent → Start screen capture; Controller: Open screen → live view, Controls change fps/quality/size and the Agent's `CAPTURE_STATUS` follows; rotate both phones; toggle WiFi → dimmed frame + reconnect; join while the Agent screen is static → frame appears immediately), then Session 5 (input injection, Agent side).

- **Session 5 — code written (needs on-device verification; not compiled in the authoring sandbox — first CI run is the real compile check).**
  - `shared`: `InputGeometry.kt` (normalized -> screen pixels, scroll-as-swipe, rotation-mismatch check) + `InputGeometryTest`.
  - `agent`: `InputAccessibilityService` (dispatchGesture for tap/long-press/swipe/scroll, global actions for keys, `ACTION_SET_TEXT` on the focused field for TEXT/BACKSPACE, `ACTION_IME_ENTER` on Android 11+), `InputCommand` (tolerant JSON parser, never throws), `res/xml/rc_input_service_config.xml`, `res/values/rc_input_strings.xml`. `AgentServer.onMessage` routes authenticated `INPUT_EVENT` messages to the service before `Protocol.decode`; if the service is not enabled the event is dropped and a throttled log line says so. Versions 0.5.
  - Debug-only: `src/debug/AndroidManifest.xml` declares the service + the **RC Input Test** launcher (`src/debug/.../InputTestActivity.kt`) = the hardcoded-test-commands deliverable.
  - Deviations / caveats: (1) The Session 4 zip did not include `Protocol.kt` or the Agent's main manifest, so the service is declared in the **debug** manifest overlay and `INPUT_EVENT` is parsed with `org.json` rather than through the `InputEvent` class in `Protocol.kt` (whose fields were not visible). **To do:** move the `<service>` block into `agent/src/main/AndroidManifest.xml` (needed for release builds) and, in Session 6, make the Controller send the wire format above (or align `InputEvent` in `Protocol.kt` to it). (2) No `INPUT_RESULT` reply yet, so the Controller cannot be told that the service is off; Agent log only. (3) Gestures are discrete (no continuous finger-drag); fine for tap/swipe/scroll. (4) Android 13+ needs "Allow restricted settings" for sideloaded APKs. (5) Secure screens (lockscreen PIN, some banking/password UIs) block or ignore injected input — expected, covered by the Session 7 wake limitation.
  - Next: verify with RC Input Test, then Session 6 (touch mapping on the Controller using `ScreenView.toNormalized`).

- **Session 6 — PARTIAL (new files only; Controller sources were not available, so the wiring is still to do; not compiled/tested in the authoring sandbox — CI is the compile check).**
  - `shared`: `TouchGesture.kt` — `TouchGestureClassifier` (tap / long-press / swipe from down-move-up, classified on release, no timers) and `InputWire` (builds the Session 5 `INPUT_EVENT` JSON; `fromGesture` maps view px -> normalized and returns null for touches outside the letterboxed frame). `TouchGestureTest` added.
  - `controller`: `TouchInputHandler.kt` — `MotionEvent` -> classifier -> `InputWire` -> `send(json)`. Multi-touch cancels the gesture. Takes lambdas so it does not depend on `ScreenView`/`ConnectionManager` signatures.
  - **To do to finish Session 6 (needs the Controller sources):** (1) in `ViewerScreen`, create `TouchInputHandler(8dp in px, { x, y -> screenView.toNormalized(x, y, false) as Pair }, { current frame w to h }, { json -> connectionManager.send(json) }) ` and set it as the touch listener of `ScreenView`; add a `send(String): Boolean` to `ConnectionManager` if it has none (true only when authenticated). (2) Add Back / Home / Recents buttons (and optionally a text field) to the viewer, sending `InputWire.key("BACK")` / `InputWire.text(...)`. (3) Move the `<service>` block from `agent/src/debug/AndroidManifest.xml` into `agent/src/main/AndroidManifest.xml`. (4) Align `InputEvent` in `Protocol.kt` with the wire format or leave the Agent's `org.json` route (the Agent handles `INPUT_EVENT` before `Protocol.decode`).
  - Limitations: long press fires on release; a drag is sent as one start->end swipe (no live finger-follow); no pinch.

- **Session 7 — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). Built on top of the Session 6 state: Session 6 wiring is STILL OPEN (its sources were not in the upload).**
  - `shared`: `WakeWire.kt` (`WAKE`/`WAKE_RESULT` JSON build + parse with kotlinx.serialization.json, no Protocol.kt change), `WakeLogic.kt` (pure plan/outcome: which steps for a given screen state, honest result text incl. the secure-lock limitation), `WakeTracker.kt` (Controller-side confirmation: `WAKE_RESULT` ok, or a new frame after the request if the Agent never answers; 8 s timeout; uses local clocks only). `WakeTest` added.
  - `agent`: `WakeHandler` (wake lock `SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP`, `WakeUnlockActivity` for `requestDismissKeyguard`, plus a full-screen-intent notification as the way to show it from the background while locked, `lockNow()` for lock), `RcDeviceAdminReceiver` + `res/xml/rc_device_admin.xml` (force-lock only), `ConnectivityKeeper` (partial wake lock + Wi-Fi lock while the service runs, so the phone is reachable with the screen off), `WakeSetupActivity` ("RC Wake Setup" launcher: enable admin, full-screen notification permission, battery setting, lock-then-wake self-test).
  - `controller`: `WakeHub` (two one-line hooks in `ConnectionManager`), `WakeClient`, `WakeBar` (Wake & unlock / Lock buttons + status).
  - Manual edits listed in `SESSION7-INTEGRATION.md` (manifest, `AgentServer`, `AgentService`, `ConnectionManager`, UI). Versions to 0.7 in both `build.gradle`s.
  - Deviations/caveats: (1) Device Admin is not required to wake the screen; it is used for the remote Lock only. (2) Starting an activity from the background is restricted on Android 10+; the full-screen-intent notification is the fallback and on Android 14+ needs the user to allow it (setup screen). Whether the direct start works varies by device: verify on the real phones. (3) PIN/pattern/password: screen wakes, stays locked, reported as `ok=false` with an explanation. (4) Keeping the Agent reachable while asleep costs battery and may still be defeated by aggressive OEM battery managers. (5) MediaProjection continuing across screen-off/lock is expected but not verified on the user's phones.
  - Next: verify (RC Wake Setup self-test; then Controller Wake & unlock with the Agent screen off, with and without a secure lock; confirm the stream resumes), then finish the open Session 6 wiring, then Session 8.

- **Session 8a — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). First half of Session 8: app list + launching. File browser/transfer is Session 8b. Built on the Session 7 state; Session 6 wiring is STILL OPEN.**
  - `shared`: `AppsWire.kt` (`APP_LIST`, `APP_LIST_RESULT`, `LAUNCH_APP`, `LAUNCH_RESULT` JSON build + parse, `AppEntry`, `LaunchResult`; no `Protocol.kt` change, protocol version unchanged), `AppsLogic.kt` (package-name validation, search/sort/hide-system filter with Turkish i/ı/İ folding, launch plan + honest outcome text), `AppsTest` added (CI already runs `:shared:test`).
  - `agent`: `AppLister` (MAIN/LAUNCHER query, so Android 11+ needs only a `<queries>` entry, no QUERY_ALL_PACKAGES), `AppsHandler` (`tryHandle` for both messages; list on a worker thread, launch on the main thread; fallback notification with `contentIntent` + full-screen intent), `LaunchSetupActivity` ("RC Launch Setup" launcher: overlay permission, app count test, open-Settings-in-6-s test).
  - `controller`: `AppsHub` (one-line hook in `ConnectionManager`), `AppsClient` (list + launch, timeouts, process-wide list cache), `AppsPanel` (full-screen searchable list; tap = launch; optional `onLaunched` to jump to the live view).
  - Manual edits listed in `SESSION8A-INTEGRATION.md` (manifest `<queries>`/permission/activity, `AgentServer`, `ConnectionManager`, an "Apps" button). Versions to 0.8 in both `build.gradle`s.
  - Deviations/caveats: (1) No app icons in the list (text-first; the list stays small). Could be added in 8b/10 as small PNGs on demand. (2) Android 10+ blocks background activity starts **silently**: `LAUNCH_RESULT.ok` only means the start was dispatched. Exemption = "Display over other apps" granted in RC Launch Setup; without it the Agent also posts a notification (tap, or full-screen intent when the screen is off/locked; Android 14+ needs the full-screen permission from RC Wake Setup). Verify on the real phones which path works. (3) Apps without a launcher icon are not listed. (4) Launching does not unlock a secure lock screen (same documented Session 7 limitation).
  - Next: verify (RC Launch Setup self-test; Controller Apps list shows the Agent's apps, search incl. Turkish letters, tap launches, with and without the overlay permission), then Session 8b (`FILE_LIST`/`FILE_CHUNK`: scoped-storage-aware browsing, chunked download/upload), then the open Session 6 wiring.

- **Session 8b — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). Second part of Session 8: file browser + chunked DOWNLOAD. Upload is Session 8c. Built on the 8a state; Session 6 wiring is STILL OPEN.**
  - `shared`: `FilesWire.kt` (`FILE_LIST`, `FILE_LIST_RESULT`, `FILE_READ`, `FILE_CHUNK` JSON build + parse; no `Protocol.kt` change, protocol version unchanged), `FilesLogic.kt` (path normalise / root check / parent / up, folders-first Turkish-folded sort, sizes, safe local names, unique names, chunk clamp 96 KB default / 256 KB max, 5000-entry cap), `FilesTest` added (CI already runs `:shared:test`).
  - `agent`: `FilesHandler` (`tryHandle`; one worker thread; single root "Internal storage"; normalised AND canonical path must stay inside the root; `RandomAccessFile` chunk reads; clear error when access is missing), `FilesSetupActivity` ("RC Files Setup" launcher: grant All files access on Android 11+, storage permission before, listing self-test).
  - `controller`: `FilesHub` (one-line hook in `ConnectionManager`), `FilesClient` (list + one download at a time, pull-based chunks, 15 s list / 20 s chunk timeouts, decode+write on a worker thread, cancel), `DownloadSink` (Android 10+: MediaStore `Download/RemoteControl`, no permission; older: app external Downloads dir), `FilesPanel` (full-screen browser: Up/Refresh/Close, tap folder = open, tap file = download, progress, Cancel).
  - Manual edits listed in `SESSION8B-INTEGRATION.md` (manifest permissions + activity, `AgentServer`, `ConnectionManager`, a "Files" button). Versions to 0.8.1 in both `build.gradle`s.
  - Deviations/caveats: (1) Browsing is limited to shared "Internal storage"; no SD card/USB roots yet (8c). (2) Needs "All files access" on Android 11+ (Play Store would restrict this; irrelevant for a personal sideloaded app). (3) Pull-based means one round trip per chunk: fine on LAN, slower over a relay in Session 9 (a small window of 2-4 outstanding reads can be added then). (4) No resume yet, although `offset` is on the wire. (5) A file changed during download is saved anyway with a warning. (6) Chunks go through the JSON/base64 text channel (~33% overhead), same as frames.
  - Next: verify (RC Files Setup -> grant + test; Controller Files: roots -> browse -> download a small and a large file; check size in Downloads/RemoteControl; cancel; unplug WiFi mid-download shows the timeout), then Session 8c (upload + file operations), then the open Session 6 wiring.

- **Session 8c — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). Third part of Session 8: chunked UPLOAD, new folder / rename / delete, SD card roots. Built on the 8b state; Session 6 wiring is STILL OPEN.**
  - `shared`: `FilesWriteWire.kt` (`FILE_WRITE`, `FILE_WRITE_ACK`, `FILE_OP`, `FILE_OP_RESULT` build + parse; protocol version unchanged), `FilesLogic` + `validName` (no separators/control chars, <= 240 UTF-8 bytes), `fileName`, `partialName`, `MAX_CHUNK_B64`; `FilesWriteTest` added.
  - `agent`: `FilesHandler` extended: multiple roots (`getExternalFilesDirs` -> volume roots, label from `StorageManager`), `write` (stateless: the partial file's length is the state, offset must match, rename on `eof`, `abort` removes the partial), `op` (mkdir/delete/rename; targets are canonical-parent + validated name, a root itself is never deletable/renamable, `deleteTree` removes symlinks as links), `selfTestWrite`; `FilesSetupActivity` gets the write self-test button.
  - `controller`: `FilesClient` (upload with read-ahead EOF detection, ack-driven, 20 s ack timeout, abort on cancel/failure/close; `op` with 60 s timeout; one transfer at a time), `FilesHub` also routes `FILE_WRITE_ACK`/`FILE_OP_RESULT`, `FilesPanel` (Upload, New folder, long-press menu, replace/delete confirmations, listing refresh after changes).
  - Manual edits listed in `SESSION8C-INTEGRATION.md` (file-picker wiring in the Controller Activity; no manifest change). Versions to 0.8.2 in both `build.gradle`s.
  - Deviations/caveats: (1) Resume is NOT implemented (moved to 8d), although `offset` + the ack's real `offset` on mismatch are on the wire. (2) App icons not done (8d). (3) An interrupted upload (link lost) leaves a hidden `.name.rcpart` in the target folder until the same name is uploaded again or deleted by hand. (4) Delete of a large folder runs on the Agent's single file worker, so listings wait behind it (Controller timeout 60 s). (5) Rename cannot move between folders; a case-only rename on FAT/exFAT SD cards is refused as "exists". (6) Upload size is unlimited by the wire; free space on the Agent is only noticed when a write fails (error shown, partial removed by the abort). (7) SD card visibility depends on the device exposing it through `getExternalFilesDirs`; USB-OTG volumes appear only while mounted.
  - Next: verify (RC Files Setup write self-test; Controller Files: upload small + large + empty file, replace prompt, cancel mid-upload and check no `.rcpart` is left, new folder, rename, delete a file and a non-empty folder, SD card root if present, unplug WiFi mid-upload shows the timeout), then Session 8d (resume + icons) or the open Session 6 wiring, then Session 9.

- **Session 8d — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). Last part of Session 8: resume of interrupted transfers + app icons. Session 8 is complete after verification. Session 6 wiring is STILL OPEN.**
  - `shared`: `FilesWriteWire` + `OP_PARTIAL` and `FileOpResult.size` (default 0, older peers unaffected), `AppIconsWire.kt` (`APP_ICON`, `APP_ICON_RESULT`, size clamp 32..192), tests `FilesWriteTest` (partial) and `AppIconsWireTest` (new).
  - `agent`: `FilesHandler` (`partial` op, stale `.rcpart` sweep on listing, routes `APP_ICON` so the `AgentServer` hook is unchanged), `AppIconsHandler` (PackageManager icon -> PNG, one worker).
  - `controller`: `FilesClient` (download goes to a partial file named by SHA-1 of path|size|mtime and is copied to `DownloadSink` when complete; stop/cancel/timeout keep it; upload record in SharedPreferences `files_resume` (dest|uri|size|lastModified) + `partial` probe, then skip-and-continue), `FilesPanel` (resume status text, last-modified for uploads, partial sweep), `FilesHub` (routes `APP_ICON_RESULT`), `AppIconLoader` (bind(ImageView, pkg), 3 in flight, 8 s timeout, LruCache 300).
  - Manual edit listed in `SESSION8D-INTEGRATION.md` (icons in `AppsPanel`, whose source was not in the upload). Versions to 0.8.3 in both `build.gradle`s.
  - Deviations/caveats: (1) Upload resume trusts "same Uri + size + last-modified + same target"; a file edited in place without changing size/mtime (rare) could resume into a corrupt result. (2) Partial downloads are not shown anywhere besides the resume status line; there is no "discard partial" button (auto-removed after 7 days). (3) Download resume requires the Agent's listing size/mtime to be unchanged; otherwise it restarts (by design). (4) Icons are cached in memory only (per panel), flattened PNGs, no per-Agent disk cache. (5) A tiny copy step at the end of a download (partial -> Downloads) doubles the disk write for the last moment; fine for phones, noticeable for multi-GB files.
  - Next: verify (download a large file, stop it at ~50%, restart: status says resuming and the final size matches; change the file on the Agent and retry: restarts; upload a large file, switch WiFi off mid-way, back on, pick the same file: resumes and the Agent file is complete; Apps list icons), then the open Session 6 wiring, then Session 9.

- **Session 9a — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). First half of Session 9: Controller-side routing for remote access. Built on the 8d state; Session 6 wiring is STILL OPEN.**
  - **Decision:** VPN mesh (Tailscale) instead of a relay; recorded in Tech Decisions. No wire change (protocol v3), no Agent change in 9a.
  - `shared`: `RouteLogic.kt` (`Route`, `RouteKind`, `AddressClass`, `LocalAddr`; strict IPv4 parse, address classification incl. Tailscale's 100.64.0.0/10, endpoint parser for `host[:port]` with `ws://`/path tolerated, subnet check, `plan` = ordered routes with per-route connect timeouts, `describe`) and `RouteSession` (walks the routes of one cycle: `failed()` -> next or null, `succeeded()`); `RouteLogicTest` added (CI already runs `:shared:test`).
  - `controller`: `RouteStore` (per-device remote endpoint + last good route, prefs `routes`, key = `host:port` paired with), `LocalNetwork` (own Wi-Fi/Ethernet IPv4 addresses via `ConnectivityManager.allNetworks`, VPN networks skipped because with Tailscale on `activeNetwork` is the VPN), `RouteConnector` (begin / failed / succeeded / restart + status texts + `clientFor` per-attempt connect timeout), `RouteDialog` (set/clear the remote address with a live hint).
  - Manual edits listed in `SESSION9A-INTEGRATION.md` (`ACCESS_NETWORK_STATE`, `ConnectionManager` hooks, a "Remote…" item in the device list; `ConnectionManager.kt`, `MainActivity.kt`, `DeviceStore.kt` were not in the upload). Version 0.9.0 for the Controller to be set by hand (build files not in the upload).
  - Deviations/caveats: (1) The remote address is typed by hand in 9a; auto-fill comes with 9b. (2) Routes fail over on transport failures only; a rejected handshake stays terminal as in Session 2. (3) A common subnet such as 192.168.1.x on a different home network costs up to 4 s before the remote route is tried. (4) Traffic is still plain ws:// (inside Tailscale it is encrypted by the tunnel; app-level TLS is Session 10). (5) The Agent must bind all interfaces for the VPN address to work (to be checked in `AgentServer`, which was not in the upload). (6) Pull-based file transfer (8b) will be slower over mobile data/relayed Tailscale paths: a small window of outstanding reads is a possible follow-up. (7) Only IPv4; IPv6 endpoints are rejected by the parser.
  - Next: wire it in (`SESSION9A-INTEGRATION.md`), verify (same Wi-Fi -> status `Local network ...`; Wi-Fi off + Tailscale on -> `Remote (VPN address ...)`; wrong remote address -> LAN still works; Agent off -> all routes fail then backoff), then Session 9b.

- **Session 9b — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check). Second half of Session 9: Agent-side helper + automatic remote address. Session 9 is complete after verification. Session 6 wiring is STILL OPEN.**
  - `shared`: `AddressesWire.kt` (`ADDRESSES`, `ADDRESSES_RESULT`, `AgentAddress`, `AddressesResult`, tolerant parser: bad IPs dropped, unknown kinds -> `OTHER`, max 16, `suggestRemote`); `AddressesWireTest` added. Protocol still v3.
  - `agent`: `AddressesHandler` (VPN addresses from `TRANSPORT_VPN` networks, other interfaces via `NetworkInterface`; created by `FilesHandler`, so `AgentServer` is untouched), `RemoteSetupActivity` ("RC Remote Setup": addresses, Tailscale installed, connect test through every address, shortcuts), `FilesHandler` (+2 lines).
  - `controller`: `AddressesClient` (request after handshake, auto-fill rules), `RouteStore` (+ auto-fill switch: Clear turns it off for that device), `RouteConnector` (+ `key()`, `badge()`, ping 10 s LAN / 15 s VPN in `clientFor`), `FilesHub` (+ `ADDRESSES_RESULT`).
  - Manual edits in `SESSION9B-INTEGRATION.md` (agent manifest: network-state permission, `<queries>` for Tailscale, setup activity; `ConnectionManager`: one client + one call after the handshake; badge in the UI). Versions 0.9.0 (build files not in the upload).
  - Deviations/caveats: (1) The Agent decides `VPN` from Android's VPN networks, so another VPN app (e.g. WireGuard) would also be suggested; fine for a personal setup. (2) Auto-fill assumes the connected, authenticated Agent is the right device; only an unset address is filled. (3) Tailscale can be stopped by Doze/OEM battery managers; no Agent-side fix, only the setup hints. (4) Pull-based file chunks are slow over mobile data (window of outstanding reads is a possible follow-up, not done). (5) IPv4 only. (6) Still plain ws:// (encrypted inside the Tailscale tunnel; app-level TLS is Session 10).
  - Next: wire in (`SESSION9A/9B-INTEGRATION.md`), verify the mobile-data checklist, then the open Session 6 wiring, then Session 10.

- **Session 10a — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check; the pure `shared` logic and its tests were compiled and run with a local Kotlin compiler: all pass). First half of Session 10: reliability. Built on the 9b state; Session 6 wiring is STILL OPEN. No wire change.**
  - `shared`: `ReconnectPolicy.kt` (backoff 1 s doubling, +-20 % jitter, cap 30 s LAN / 60 s VPN, a link up >= 20 s resets the counter, 300 ms retry after a network change), `FailureClassifier.kt` (transport exception -> kind, retry flag, readable text with a VPN-specific hint; TLS failures are marked not retryable for 10b), `AgentStatusText.kt` (notification line); tests `ReconnectPolicyTest`, `FailureClassifierTest`, `AgentStatusTextTest`.
  - `agent`: `AutoStart` (own prefs `agent_autostart`: switch + was-running flag + last boot outcome), `BootReceiver` (`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` -> start `AgentService` only if the switch is on and the Agent was running; catches refusal and posts a tap-to-start notification), `BatteryHelper` (exemption check + request dialog + list/app-details fallbacks + best-effort vendor auto-start screens), `AgentNotification` (new minimised silent channel `agent_status_min`, hidden on the lock screen, deletes the old channel ids, status text updates, start prompt), `ReliabilitySetupActivity` ("RC Reliability Setup": battery, start-at-boot switch, notification permission and channel settings, boot self-test, cold-start checklist).
  - `controller`: `NetworkWatcher` (default-network callback, debounced: retry at once on a network change, "No network" instead of retry spam), `RouteConnector` (+ `remoteLikely()` for the backoff cap).
  - Manual edits in `SESSION10A-INTEGRATION.md` (agent manifest; three `AgentService` edits; `ConnectionManager` backoff / failure text / network hook). `AgentService`, `AgentServer`, `ConnectionManager` were not in the upload. Versions 0.10.0 (build files not in the upload).
  - Deviations/caveats: (1) Screen capture cannot resume after a reboot (MediaProjection consent), the Controller sees capture off until someone starts it on the Agent. (2) Android 15+ (target SDK 35) forbids a `dataSync` foreground service start from `BOOT_COMPLETED`: handled by the tap-to-start notification; a real fix needs another service type (not done). (3) With a PIN/pattern nothing starts before the first unlock. (4) The boot self-test runs from the foreground, so only a real reboot verifies the boot path. (5) Vendor auto-start screens are best effort. (6) The old notification channel ids must be passed to `ensureChannels` by hand (unknown in the upload). (7) Controller does not hold a foreground service; reconnect logic only runs while the app process lives.
  - Next: wire in (`SESSION10A-INTEGRATION.md`), verify (notification, battery exemption, reboot with Agent running / stopped on purpose / switch off, Wi-Fi -> mobile switch, Agent kill and restart), then Session 10b, then the open Session 6 wiring.

- **Session 10b — code written (needs on-device verification; not compiled in the authoring sandbox — CI is the compile check; the pure `shared` logic (`TlsPolicy`, `SettingsLogic`, `StreamGateLogic`, `FailureClassifier`) and its tests were compiled and run with a local Kotlin compiler: 22 tests pass; `ViewerStateWire` (kotlinx.serialization) and everything Android-side were not compiled). Second half of Session 10: security + settings. Built on the 10a state; Session 6 wiring is STILL OPEN.**
  - **Decision:** TLS over WebSocket (wss://), not payload encryption. The Agent has one self-signed EC P-256 certificate whose key lives in the Android Keystore; the Controller pins its SHA-256 fingerprint on first use (trust on first use). Plain `ws://` fallback only for a device without a pin and only while "Require encryption" is off; a pinned device never downgrades.
  - `shared`: `TlsPolicy` (fingerprint, constant-time match, display, fallback/terminal decision), `FailureClassifier` (+ `TLS_PIN`, `TLS_PLAIN`), `SettingsLogic` (ranges, size presets), `StreamGateLogic` (pause rule with 10 s heartbeat, 30 s expiry), `ViewerStateWire` (`VIEWER_STATE`, protocol still v3); tests `TlsPolicyTest`, `SettingsLogicTest` (+ `StreamGateLogicTest`), `ViewerStateWireTest`, `FailureClassifierTest` extended.
  - `agent`: `TlsIdentity` (Keystore key, certificate, fingerprint, regenerate, `DefaultSSLWebSocketServerFactory`), `SecuritySetupActivity` ("RC Security": on/off, fingerprint, new certificate), `StreamGate` + `ViewerStateHandler` (routed by `FilesHandler`, no `AgentServer` hook for it).
  - `controller`: `SecureDial` (+ `PinTrust`, `PinMismatchException`, `DialAttempt`: wss URL, pinned client, pin saved only after the handshake was accepted, plain fallback / terminal stop), `SettingsStore`, `SettingsPanel` (LAN only, Require encryption, capture defaults applied on connect if switched on, per-device pin / reset pin / re-pair / forget), `TokenVault` (AES-GCM, Keystore key, reads old plain tokens), `ViewerStateClient`, `RouteConnector` (LAN-only filter).
  - Manual edits in `SESSION10B-INTEGRATION.md` (Agent manifest + `AgentServer` TLS factory + `ScreenCapturer` pause condition; `ConnectionManager` dial/accepted/failure code; `MainActivity` settings button and viewer visibility; `DeviceStore` token vault). Versions 0.10.1.
  - Deviations/caveats: (1) Trust on first use: a man in the middle at the very first connection would be pinned; compare fingerprints (RC Security vs Settings) on your own network. (2) The plain-Agent detection is heuristic (reset or wrong-version during the handshake) and only applies to unpinned devices. (3) Regenerating the certificate or reinstalling the Agent invalidates all pins. (4) The stream pause is per Agent (last report wins). (5) "Relay vs LAN-only" became only "LAN only" since the relay was dropped in Session 9. (6) Capture defaults need the viewer's existing send call (its code was not in the upload). (7) Update the Controller before the Agent.
  - Next: wire in (`SESSION10A/10B-INTEGRATION.md`), verify on devices, then the open Session 6 wiring. Session 10 is then complete: stable personal-use build.

- **Session 11 — Controller wiring finished (not compiled: the sandbox has no Android SDK / Maven; the first CI run is the compile check).**
  - `MainActivity` rewritten for the new `ConnectionManager(context, store, listener)`: screens Connection / Viewer / Apps / Files / Settings (Back returns to the connection screen); Apps and Files buttons enabled only while connected; `WakeBar` on the connection screen; per device Connect / Remote… (`RouteDialog`) / Forget (`manager.forget`); `LAN · TLS` badge in the status line; `onInfo` goes to the log; file picker (`ACTION_OPEN_DOCUMENT` -> `FilesPanel.onFilePicked`); `SettingsPanel.Host` (devices / forget / repair / close); `manager.setViewerVisible(viewerShown && resumed)` on every screen / resume / pause change and once at start; `manager.shutdown()` in `onDestroy`.
  - `ViewerScreen`: new parameters `send` and `badge`; `TouchInputHandler` (slop 8 dp) as touch listener of `ScreenView` (new `ScreenView.frameSize`); Back / Home / Recents bar; text field + Backspace / Enter; `WakeBar` in the Controls panel; badge in the stats line; short hint when a touch is not sent. The top-left button is now "Exit" (leaves the viewer) to avoid mixing it up with the Agent's Back key.
  - `AppsPanel`: icon per row via `AppIconLoader` (start / stop on attach / detach), custom row instead of `simple_list_item_2`.
  - `ConnectionManager.targets(device)` added (used by Re-pair).
  - Still open: first CI build, on-device verification (all checklists), notes for Sessions 1 / 8c / 9a never uploaded.
- **Session 11b — one app (not compiled; CI is the check).** New `app` module (`HubActivity`: choose Controller or Agent), `agent` / `controller` turned into library modules, launcher filters removed from their activities, `settings.gradle.kts` / root plugins / CI updated (single artifact `remotecontrol-debug-apk`), version 0.11.0. New application id: old pairings are not migrated. A phone can now act as Agent and Controller. Debug-only `InputTestActivity` has no icon (start via adb, see README).
