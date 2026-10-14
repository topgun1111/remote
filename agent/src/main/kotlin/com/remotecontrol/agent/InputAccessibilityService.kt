package com.remotecontrol.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotecontrol.shared.InputGeometry

/**
 * Executes [InputCommand]s on this phone. The user must enable it manually in
 * Settings > Accessibility (there is no workaround). Everything runs on the main thread;
 * [execute] may be called from any thread.
 */
class InputAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Input service connected")
        AgentLog.add("Input control enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        AgentLog.add("Input control disabled")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Queues [cmd] on the main thread. [onDone] gets true if it was performed/accepted by the system. */
    fun execute(cmd: InputCommand, onDone: ((Boolean) -> Unit)? = null) {
        main.post {
            val ok = try { perform(cmd, onDone) } catch (e: Exception) {
                Log.w(TAG, "Input failed: ${e.message}"); false
            }
            // Gestures report asynchronously via their callback; everything else is done here.
            if (!isGesture(cmd) || !ok) onDone?.invoke(ok)
        }
    }

    private fun isGesture(c: InputCommand) =
        c is InputCommand.Tap || c is InputCommand.LongPress || c is InputCommand.Swipe || c is InputCommand.Scroll

    private fun perform(cmd: InputCommand, onDone: ((Boolean) -> Unit)?): Boolean {
        val (sw, sh) = screenSize()
        if (isGesture(cmd) && !InputGeometry.orientationMatches(cmd.frameW, cmd.frameH, sw, sh)) {
            Log.w(TAG, "Dropped ${cmd::class.simpleName}: frame ${cmd.frameW}x${cmd.frameH} vs screen ${sw}x$sh")
            return false
        }
        return when (cmd) {
            is InputCommand.Tap -> {
                val (x, y) = InputGeometry.toScreen(cmd.x, cmd.y, sw, sh)
                gesture(Path().apply { moveTo(x, y) }, 50, onDone)
            }
            is InputCommand.LongPress -> {
                val (x, y) = InputGeometry.toScreen(cmd.x, cmd.y, sw, sh)
                gesture(Path().apply { moveTo(x, y) }, cmd.durationMs, onDone)
            }
            is InputCommand.Swipe -> {
                val (x1, y1) = InputGeometry.toScreen(cmd.x, cmd.y, sw, sh)
                val (x2, y2) = InputGeometry.toScreen(cmd.x2, cmd.y2, sw, sh)
                gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, cmd.durationMs, onDone)
            }
            is InputCommand.Scroll -> {
                val s = InputGeometry.scrollSwipe(cmd.x, cmd.y, cmd.dx, cmd.dy)
                val (x1, y1) = InputGeometry.toScreen(s[0], s[1], sw, sh)
                val (x2, y2) = InputGeometry.toScreen(s[2], s[3], sw, sh)
                gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 250, onDone)
            }
            is InputCommand.Key -> key(cmd.key)
            is InputCommand.Text -> typeText(cmd.text)
        }
    }

    private fun gesture(path: Path, durationMs: Long, onDone: ((Boolean) -> Unit)?): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        val cb = object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { onDone?.invoke(true) }
            override fun onCancelled(d: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled"); onDone?.invoke(false)
            }
        }
        return dispatchGesture(g, cb, main)
    }

    private fun key(name: String): Boolean = when (name) {
        "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "LOCK_SCREEN" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "SCREENSHOT" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "POWER_DIALOG" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "BACKSPACE" -> editFocused { cur -> cur.dropLast(1) }
        "ENTER" -> imeEnter()
        else -> false
    }

    private fun typeText(text: String): Boolean = editFocused { cur -> cur + text }

    /** Replaces the text of the focused input field with [transform] of its current text. */
    private fun editFocused(transform: (String) -> String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { recycle(root); Log.w(TAG, "No focused input field"); return false }
        try {
            val showingHint = Build.VERSION.SDK_INT >= 26 && node.isShowingHintText
            val current = if (showingHint) "" else node.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, transform(current))
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            recycle(node); recycle(root)
        }
    }

    private fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) { Log.w(TAG, "ENTER needs Android 11+"); return false }
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { recycle(root); return false }
        try {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } finally {
            recycle(node); recycle(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) try { n.recycle() } catch (_: Exception) {} }

    /** Real display size in the current rotation (what gesture coordinates are relative to). */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics().also { d.getRealMetrics(it) }
        return dm.widthPixels to dm.heightPixels
    }

    companion object {
        private const val TAG = "RemoteAgent"

        /** The connected service, or null while the user has not enabled it. */
        @Volatile var instance: InputAccessibilityService? = null
            private set
    }
}
