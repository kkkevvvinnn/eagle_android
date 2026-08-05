package com.eagleviewer.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDifferTest {

    @Test
    fun `first scan indexes everything`() {
        val diff = ScanDiffer.diff(
            indexed = emptyMap(),
            mtime = mapOf("A" to 1L, "B" to 2L),
        )
        assertEquals(listOf("A", "B"), diff.toScan)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `unchanged items are skipped`() {
        val diff = ScanDiffer.diff(
            indexed = mapOf("A" to 1L, "B" to 2L),
            mtime = mapOf("A" to 1L, "B" to 2L),
        )
        assertTrue(diff.toScan.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `changed item is rescanned`() {
        val diff = ScanDiffer.diff(
            indexed = mapOf("A" to 1L),
            mtime = mapOf("A" to 9L),
        )
        assertEquals(listOf("A"), diff.toScan)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `removed item is deleted from index`() {
        val diff = ScanDiffer.diff(
            indexed = mapOf("A" to 1L, "B" to 2L),
            mtime = mapOf("A" to 1L),
        )
        assertTrue(diff.toScan.isEmpty())
        assertEquals(listOf("B"), diff.toDelete)
    }

    @Test
    fun `mixed add change remove`() {
        val diff = ScanDiffer.diff(
            indexed = mapOf("KEEP" to 1L, "CHG" to 1L, "DEL" to 1L),
            mtime = mapOf("KEEP" to 1L, "CHG" to 2L, "NEW" to 1L),
        )
        assertEquals(listOf("CHG", "NEW"), diff.toScan)
        assertEquals(listOf("DEL"), diff.toDelete)
    }
}
