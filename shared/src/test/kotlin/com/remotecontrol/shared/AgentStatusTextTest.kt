package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentStatusTextTest {
    @Test fun lines() {
        assertEquals("Ready, no controller", AgentStatusText.format(0, false))
        assertEquals("Ready, no controller · screen shared", AgentStatusText.format(0, true))
        assertEquals("1 controller connected · screen shared", AgentStatusText.format(1, true))
        assertEquals("2 controllers connected", AgentStatusText.format(2, false))
        assertEquals("Ready, no controller", AgentStatusText.format(-1, false))
    }
}
