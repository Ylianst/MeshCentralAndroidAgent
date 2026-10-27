package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtocolValidationTest {
    @Test
    fun acceptsValidMeshServerLink() {
        assertTrue(isMeshServerLinkValid("mc://mesh.example.com,certificateHash,deviceGroup"))
    }

    @Test
    fun rejectsMalformedMeshServerLinks() {
        assertFalse(isMeshServerLinkValid("https://mesh.example.com,certificateHash,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://localhost,certificateHash,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://mesh.example.com,ab,deviceGroup"))
        assertFalse(isMeshServerLinkValid("mc://mesh.example.com,certificateHash,ab"))
    }

    @Test
    fun acceptsAbsentOrAllowedTunnelUsage() {
        assertTrue(isTunnelUsageAllowed(null, 2))
        assertTrue(isTunnelUsageAllowed(listOf(5, 10), 5))
        assertTrue(isTunnelUsageAllowed(listOf(1, 6, 8, 9, 2), 2))
    }

    @Test
    fun rejectsUsageOutsideAllowedList() {
        assertFalse(isTunnelUsageAllowed(listOf(5, 10), 2))
        assertFalse(isTunnelUsageAllowed(emptyList(), 2))
    }

    @Test
    fun resolvesPathsContainedBySdcardRoot() {
        val root = File("build/test-sdcard").canonicalFile

        assertEquals(root, resolveSdcardPath(root, "Sdcard"))
        assertEquals(File(root, "Pictures/photo.jpg"), resolveSdcardPath(root, "Sdcard/Pictures/photo.jpg"))
        assertEquals(File(root, "Pictures/photo.jpg"), resolveSdcardChild(root, "Sdcard/Pictures", "photo.jpg"))
    }

    @Test
    fun rejectsExternalStorageTraversalAndUnsafeNames() {
        val root = File("build/test-sdcard").canonicalFile

        assertNull(resolveSdcardPath(root, "Sdcard/../private.txt"))
        assertNull(resolveSdcardPath(root, "Sdcard\\..\\private.txt"))
        assertNull(resolveSdcardPath(root, "Other/photo.jpg"))
        assertNull(resolveSdcardChild(root, "Sdcard/Pictures", "../private.txt"))
        assertNull(resolveSdcardChild(root, "Sdcard/Pictures", "nested/photo.jpg"))
        assertFalse(isSafeFileName(".."))
    }

    @Test
    fun rejectsSiblingDirectorySharingRootPrefix() {
        val root = File("build/test-sdcard").canonicalFile

        // Escapes to a sibling whose path shares the root's string prefix; only the trailing
        // separator in the containment check keeps this from passing.
        assertNull(resolveSdcardPath(root, "Sdcard/../test-sdcardEvil/photo.jpg"))
    }

    @Test
    fun validatesSafeFileNames() {
        assertTrue(isSafeFileName("photo.jpg"))
        assertFalse(isSafeFileName(""))
        assertFalse(isSafeFileName("."))
        assertFalse(isSafeFileName("dir/photo.jpg"))
        assertFalse(isSafeFileName("photo\u0000.jpg"))
    }
}