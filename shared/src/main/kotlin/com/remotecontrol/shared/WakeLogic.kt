package com.remotecontrol.shared

/** Snapshot of the Agent's screen state, read from PowerManager / KeyguardManager / DevicePolicyManager. */
data class ScreenState(
    val screenOn: Boolean,
    val keyguardLocked: Boolean,
    /** true when a PIN/pattern/password (secure lock) is set: `KeyguardManager.isDeviceSecure`. */
    val deviceSecure: Boolean,
    val adminActive: Boolean,
)

enum class WakeStep { ACQUIRE_WAKE_LOCK, DISMISS_KEYGUARD, LOCK_NOW }

/**
 * Pure decision logic for `WAKE`, so the documented limitations are unit-tested instead of re-debated:
 * - screen off  -> wake it (WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP)
 * - unlock requested and keyguard showing -> ask the system to dismiss it. This only completes by itself
 *   when there is no secure lock (swipe/none) or a trusted device / Smart Lock currently unlocks it.
 *   With a PIN/pattern/password the screen wakes but stays locked: reported honestly, never faked.
 */
object WakeLogic {
    fun plan(req: WakeRequest, s: ScreenState): List<WakeStep> {
        if (req.lock) return if (s.adminActive) listOf(WakeStep.LOCK_NOW) else emptyList()
        val steps = ArrayList<WakeStep>(2)
        if (!s.screenOn) steps += WakeStep.ACQUIRE_WAKE_LOCK
        if (req.unlock && (s.keyguardLocked || !s.screenOn)) steps += WakeStep.DISMISS_KEYGUARD
        return steps
    }

    /** [before] = state when the request arrived, [after] = state a moment after the steps ran. */
    fun outcome(req: WakeRequest, before: ScreenState, after: ScreenState): WakeResult {
        fun r(ok: Boolean, detail: String) =
            WakeResult(ok, after.screenOn, after.keyguardLocked, after.deviceSecure, after.adminActive, detail)

        if (req.lock) {
            return when {
                !before.adminActive -> r(false, "Device Admin is not enabled on the Agent (needed to lock).")
                !after.screenOn || after.keyguardLocked -> r(true, "Screen locked.")
                else -> r(false, "Lock was requested but the screen is still on.")
            }
        }
        if (!after.screenOn) return r(false, "Screen did not turn on (blocked by the system or battery saver?).")
        if (!req.unlock) return r(true, if (before.screenOn) "Screen was already on." else "Screen is on.")
        return when {
            !after.keyguardLocked -> r(true, if (before.keyguardLocked || !before.screenOn) "Screen is on and unlocked." else "Screen was already on and unlocked.")
            after.deviceSecure -> r(false, "Screen is on, but a PIN/pattern/password lock is set: unlock it on the Agent (or use Smart Lock / a trusted device).")
            else -> r(false, "Screen is on, but the lock screen is still showing.")
        }
    }
}
