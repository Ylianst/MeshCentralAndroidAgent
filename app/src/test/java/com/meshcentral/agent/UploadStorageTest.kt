package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStorageTest {
    @Test
    fun mapsSdcardPathsToSharedStorageDirectories() {
        assertEquals("", sdcardRelativeDirectory("Sdcard"))
        assertEquals("Download", sdcardRelativeDirectory("Sdcard/Download"))
        assertEquals("Download/sub", sdcardRelativeDirectory("Sdcard/Download/sub/"))
        assertNull(sdcardRelativeDirectory("Images"))
        assertNull(sdcardRelativeDirectory("SdcardEvil/x"))
    }

    @Test
    fun onlyStandardTopLevelFoldersAreWritable() {
        assertTrue(isStandardSharedFolder("Download"))
        assertTrue(isStandardSharedFolder("Pictures/Screenshots"))
        assertFalse(isStandardSharedFolder(""))
        assertFalse(isStandardSharedFolder("Photos"))
        assertFalse(isStandardSharedFolder("download"))
    }

    @Test
    fun mediaOnlyFoldersUseTheIndex() {
        assertTrue(isMediaOnlySharedFolder("DCIM/Camera"))
        assertTrue(isMediaOnlySharedFolder("Pictures"))
        assertFalse(isMediaOnlySharedFolder("Download"))
        assertFalse(isMediaOnlySharedFolder("Documents/Scans"))
        assertFalse(isMediaOnlySharedFolder(""))
    }

    @Test
    fun mediaFoldersAcceptMatchingMimeTypesOnly() {
        assertTrue(MediaFolder.IMAGES.accepts("image/png"))
        assertFalse(MediaFolder.IMAGES.accepts("text/plain"))
        assertTrue(MediaFolder.VIDEOS.accepts("video/mp4"))
        assertTrue(MediaFolder.AUDIO.accepts("audio/mpeg"))
        assertEquals(MediaFolder.AUDIO, MediaFolder.fromVirtualName("Audio"))
        assertNull(MediaFolder.fromVirtualName("Sdcard"))
    }
}
