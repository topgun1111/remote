package com.remotecontrol.shared

/** Session 10a: the one line of the Agent's persistent notification. */
object AgentStatusText {
    fun format(controllers: Int, capturing: Boolean): String {
        val n = controllers.coerceAtLeast(0)
        val who = when (n) {
            0 -> "Ready, no controller"
            1 -> "1 controller connected"
            else -> "$n controllers connected"
        }
        return if (capturing) "$who · screen shared" else who
    }
}
