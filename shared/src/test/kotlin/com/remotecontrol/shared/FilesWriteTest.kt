package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesWriteTest {
    @Test fun validNames() {
        assertTrue(FilesLogic.validName("Işık raporu (1).txt"))
        assertTrue(FilesLogic.validName(".hidden"))
        assertFalse(FilesLogic.validName(""))
        assertFalse(FilesLogic.validName("   "))
        assertFalse(FilesLogic.validName("."))
        assertFalse(FilesLogic.validName(".."))
        assertFalse(FilesLogic.validName("a/b"))
        assertFalse(FilesLogic.validName("a\\b"))
        assertFalse(FilesLogic.validName("a\u0000b"))
        assertFalse(FilesLogic.validName("a\nb"))
        assertTrue(FilesLogic.validName("x".repeat(FilesLogic.MAX_NAME_BYTES)))
        assertFalse(FilesLogic.validName("x".repeat(FilesLogic.MAX_NAME_BYTES + 1)))
        // limit is in UTF-8 bytes: 121 x 2-byte letters = 242 bytes
        assertFalse(FilesLogic.validName("ş".repeat(121)))
        assertTrue(FilesLogic.validName("ş".repeat(120)))
    }

    @Test fun pathHelpers() {
        assertEquals("b", FilesLogic.fileName("/a/b"))
        assertEquals("b", FilesLogic.fileName("/a/b/"))
        assertEquals(".a.txt.rcpart", FilesLogic.partialName("a.txt"))
        assertTrue(FilesLogic.MAX_CHUNK_B64 >= (FilesLogic.MAX_CHUNK + 2) / 3 * 4)
    }

    @Test fun writeRoundTrip() {
        val raw = FilesWriteWire.writeRequest("/a/ü \"q\".txt", 96, "QUJD", eof = true, overwrite = true)
        assertEquals(FileWriteRequest("/a/ü \"q\".txt", 96, "QUJD", eof = true, overwrite = true, abort = false), FilesWriteWire.parseWriteRequest(raw))
        val ab = FilesWriteWire.parseWriteRequest(FilesWriteWire.abortRequest("/a/b"))!!
        assertTrue(ab.abort); assertEquals(0L, ab.offset)
        assertEquals(0L, FilesWriteWire.parseWriteRequest(FilesWriteWire.writeRequest("/f", -5, "", false, false))!!.offset)
    }

    @Test fun ackRoundTrip() {
        val a = FileWriteAck(true, "/a/b", 98304, false, "")
        assertEquals(a, FilesWriteWire.parseWriteAck(FilesWriteWire.writeAck(a)))
        val e = FileWriteAck(false, "/a/b", 10, false, "Offset mismatch \"x\"")
        assertEquals(e, FilesWriteWire.parseWriteAck(FilesWriteWire.writeAck(e)))
    }

    @Test fun opRoundTrip() {
        val r = FileOpRequest(FilesWriteWire.OP_RENAME, "/a/b", "ç.txt")
        assertEquals(r, FilesWriteWire.parseOpRequest(FilesWriteWire.opRequest(r.op, r.path, r.name)))
        assertEquals("", FilesWriteWire.parseOpRequest(FilesWriteWire.opRequest(FilesWriteWire.OP_DELETE, "/x"))!!.name)
        val res = FileOpResult(false, FilesWriteWire.OP_DELETE, "/x", "protected")
        assertEquals(res, FilesWriteWire.parseOpResult(FilesWriteWire.opResult(res)))
    }

    @Test fun partialOpCarriesSize() {
        val q = FilesWriteWire.parseOpRequest(FilesWriteWire.opRequest(FilesWriteWire.OP_PARTIAL, "/a/b.bin"))!!
        assertEquals(FilesWriteWire.OP_PARTIAL, q.op)
        val r = FileOpResult(true, FilesWriteWire.OP_PARTIAL, "/a/b.bin", "", 123456789012L)
        assertEquals(r, FilesWriteWire.parseOpResult(FilesWriteWire.opResult(r)))
        // a result without "size" (older Agent) reads as 0
        assertEquals(0L, FilesWriteWire.parseOpResult("""{"type":"FILE_OP_RESULT","ok":true,"op":"partial"}""")!!.size)
    }

    @Test fun parsersDoNotMixRequestsAndReplies() {
        val ack = FilesWriteWire.writeAck(FileWriteAck(true, "/a", 1, false, ""))
        val write = FilesWriteWire.writeRequest("/a", 0, "", false, false)
        val res = FilesWriteWire.opResult(FileOpResult(true, "mkdir", "/a", ""))
        val op = FilesWriteWire.opRequest("mkdir", "/a", "n")
        assertNull(FilesWriteWire.parseWriteRequest(ack)); assertNull(FilesWriteWire.parseWriteAck(write))
        assertNull(FilesWriteWire.parseOpRequest(res)); assertNull(FilesWriteWire.parseOpResult(op))
        val other = """{"type":"FILE_LIST","path":""}"""
        assertNull(FilesWriteWire.parseWriteRequest(other)); assertNull(FilesWriteWire.parseOpRequest(other))
        assertNull(FilesWriteWire.parseWriteAck("not json FILE_WRITE_ACK"))
    }
}
