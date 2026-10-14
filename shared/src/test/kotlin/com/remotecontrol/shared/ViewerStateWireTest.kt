package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerStateWireTest {
    @Test fun roundTrip() {
        assertEquals(true, ViewerStateWire.parse(ViewerStateWire.build(true)))
        assertEquals(false, ViewerStateWire.parse(ViewerStateWire.build(false)))
    }

    @Test fun otherMessagesAreNotViewerState() {
        assertNull(ViewerStateWire.parse("""{"type":"ADDRESSES"}"""))
        assertNull(ViewerStateWire.parse("""{"type":"VIEWER_STATE"}"""))
        assertNull(ViewerStateWire.parse("""{"type":"VIEWER_STATE","visible":"yes"}"""))
        assertNull(ViewerStateWire.parse("VIEWER_STATE not json"))
    }
}
