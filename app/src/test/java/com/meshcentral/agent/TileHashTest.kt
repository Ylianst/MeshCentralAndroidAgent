package com.meshcentral.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TileHashTest {
    private fun hashOf(pixels: IntArray): Int {
        var h = TILE_HASH_SEED
        for (p in pixels) h = tileHash(p, h)
        return h
    }

    @Test
    fun detectsSmallAndStructuralChanges() {
        val base = IntArray(4096) { 0xFF102030.toInt() + (it % 7) }
        val oneLevelChange = base.copyOf().also { it[2000] = it[2000] + 1 }
        val swapped = base.copyOf().also { val t = it[10]; it[10] = it[11]; it[11] = t }
        val uniformDelta = IntArray(4096) { base[it] + 0x010101 }
        assertEquals(hashOf(base), hashOf(base.copyOf()))
        assertNotEquals(hashOf(base), hashOf(oneLevelChange))
        assertNotEquals(hashOf(base), hashOf(swapped))
        assertNotEquals(hashOf(base), hashOf(uniformDelta))
    }
}
