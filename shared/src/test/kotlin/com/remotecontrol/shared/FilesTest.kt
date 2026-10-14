package com.remotecontrol.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTest {
    private val roots = listOf("/storage/emulated/0")

    @Test fun normalizeResolvesDotsAndRejectsEscapes() {
        assertEquals("/a/c", FilesLogic.normalize("/a/b/../c/./"))
        assertEquals("/", FilesLogic.normalize("/"))
        assertEquals("/a/b", FilesLogic.normalize("//a///b"))
        assertNull(FilesLogic.normalize("a/b"))
        assertNull(FilesLogic.normalize("/.."))
        assertNull(FilesLogic.normalize("/a/../../b"))
        assertNull(FilesLogic.normalize("/a\u0000b"))
    }

    @Test fun rootsAreEnforced() {
        assertEquals("/storage/emulated/0/DCIM", FilesLogic.resolve(roots, "/storage/emulated/0/DCIM/"))
        assertEquals("/storage/emulated/0", FilesLogic.resolve(roots, "/storage/emulated/0"))
        assertNull(FilesLogic.resolve(roots, "/storage/emulated/01"))
        assertNull(FilesLogic.resolve(roots, "/storage/emulated/0/../../../data"))
        assertNull(FilesLogic.resolve(roots, "/data/data/x"))
        assertNull(FilesLogic.resolve(roots, ""))
        assertTrue(FilesLogic.isUnder("/", "/anything"))
    }

    @Test fun parentJoinUp() {
        assertEquals("/a", FilesLogic.parent("/a/b"))
        assertEquals("/", FilesLogic.parent("/a"))
        assertEquals("/", FilesLogic.parent("/"))
        assertEquals("/a/b", FilesLogic.join("/a", "b"))
        assertEquals("/b", FilesLogic.join("/", "b"))
        assertEquals("", FilesLogic.up("/storage/emulated/0", roots))
        assertEquals("", FilesLogic.up("", roots))
        assertEquals("/storage/emulated/0", FilesLogic.up("/storage/emulated/0/DCIM", roots))
    }

    @Test fun sortFoldersFirstTurkishFolded() {
        val l = FilesLogic.sort(listOf(
            FileEntry("zeta.txt", false, 1, 0), FileEntry("Işık", true, 0, 0),
            FileEntry("Alfa.txt", false, 1, 0), FileEntry("bravo", true, 0, 0),
        ))
        assertEquals(listOf("bravo", "Işık", "Alfa.txt", "zeta.txt"), l.map { it.name })
    }

    @Test fun sizesAndProgress() {
        assertEquals("0 B", FilesLogic.humanSize(0))
        assertEquals("1023 B", FilesLogic.humanSize(1023))
        assertEquals("1.0 KB", FilesLogic.humanSize(1024))
        assertEquals("1.5 MB", FilesLogic.humanSize(1536L * 1024))
        assertEquals("2.0 GB", FilesLogic.humanSize(2L * 1024 * 1024 * 1024))
        assertTrue(FilesLogic.progressText("a", 50, 200).contains("(25%)"))
        assertFalse(FilesLogic.progressText("a", 50, 0).contains("%"))
    }

    @Test fun localNames() {
        assertEquals("a_b_c.txt", FilesLogic.safeLocalName("a/b:c.txt"))
        assertEquals("file", FilesLogic.safeLocalName("..."))
        assertEquals("hidden", FilesLogic.safeLocalName("..hidden"))
        assertEquals("ğüşiöç.png", FilesLogic.safeLocalName("ğüşiöç.png"))
        assertEquals(120, FilesLogic.safeLocalName("x".repeat(500)).length)
        assertEquals("a.txt", FilesLogic.uniqueName("a.txt") { false })
        assertEquals("a (2).txt", FilesLogic.uniqueName("a.txt") { it == "a.txt" || it == "a (1).txt" })
        assertEquals("noext (1)", FilesLogic.uniqueName("noext") { it == "noext" })
    }

    @Test fun listWireRoundTrip() {
        val r = FileListResult(true, "/x", "", listOf(
            FileEntry("Işık \"q\"", true, 0, 5), FileEntry("a.bin", false, 123456789012L, 99, "/storage/emulated/0"),
        ), truncated = true)
        assertEquals(r, FilesWire.parseListResult(FilesWire.listResult(r)))
        val err = FileListResult(false, "/y", "no access", emptyList())
        assertEquals(err, FilesWire.parseListResult(FilesWire.listResult(err)))
        assertEquals("/a/ü", FilesWire.parseListRequest(FilesWire.listRequest("/a/ü")))
        assertEquals("", FilesWire.parseListRequest(FilesWire.listRequest("")))
    }

    @Test fun readWireRoundTripAndClamp() {
        assertEquals(FileReadRequest("/f", 10L, 4096), FilesWire.parseReadRequest(FilesWire.readRequest("/f", 10, 4096)))
        assertEquals(FilesLogic.MAX_CHUNK, FilesWire.parseReadRequest(FilesWire.readRequest("/f", 0, 99_000_000))!!.len)
        assertEquals(1, FilesWire.parseReadRequest(FilesWire.readRequest("/f", 0, -5))!!.len)
        assertEquals(0L, FilesWire.parseReadRequest(FilesWire.readRequest("/f", -7, 10))!!.offset)
        val c = FileChunk(true, "/f", 10, 100, "QUJD", false, "")
        assertEquals(c, FilesWire.parseChunk(FilesWire.chunk(c)))
        val e = FileChunk(false, "/f", 0, 0, "", false, "boom \"x\"")
        assertEquals(e, FilesWire.parseChunk(FilesWire.chunk(e)))
    }

    @Test fun parsersRejectOtherMessages() {
        val other = """{"type":"APP_LIST"}"""
        assertNull(FilesWire.parseListRequest(other)); assertNull(FilesWire.parseReadRequest(other))
        assertNull(FilesWire.parseListResult(other)); assertNull(FilesWire.parseChunk(other))
        // FILE_LIST must not match FILE_LIST_RESULT
        assertNull(FilesWire.parseListRequest(FilesWire.listResult(FileListResult(true, "", "", emptyList()))))
        assertNull(FilesWire.parseListResult("not json FILE_LIST_RESULT"))
    }
}
