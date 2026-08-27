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
    fun acceptsAbsentOrMatchingTunnelUsage() {
        assertTrue(isTunnelUsageAllowed(null, 2))
        assertTrue(isTunnelUsageAllowed(5, 5))
    }

    @Test
    fun rejectsMismatchedTunnelUsage() {
        assertFalse(isTunnelUsageAllowed(2, 5))
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
}