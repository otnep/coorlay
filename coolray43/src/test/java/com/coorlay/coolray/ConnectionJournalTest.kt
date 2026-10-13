package com.coorlay.coolray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionJournalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun ConnectionJournal.lines() = text().lines().map { it.substringAfter(' ') }

    @Test fun linesReachMemoryAndFileAfterFlush() {
        val journal = ConnectionJournal(folder.root)
        journal.add("first"); journal.add("second")
        journal.flush(2000)
        assertEquals(listOf("first", "second"), journal.lines())
        assertEquals(listOf("first", "second"), journal.file.readLines().map { it.substringAfter(' ') })
        journal.close()
    }

    @Test fun earlierRunsAppearBeforeThisRun() {
        ConnectionJournal(folder.root).file.writeText((1..500).joinToString("") { "00:00:00.000 old$it\n" })
        val journal = ConnectionJournal(folder.root)
        journal.loadHistory()
        journal.add("new")
        journal.flush(2000)
        val lines = journal.lines()
        assertEquals(401, lines.size) // The newest 400 lines of earlier runs.
        assertEquals("old101", lines.first())
        assertEquals("old500", lines[399])
        assertEquals("new", lines.last())
        journal.close()
    }

    @Test fun fileRotatesPastLimitAndKeepsWriting() {
        val journal = ConnectionJournal(folder.root, maxFileBytes = 100)
        repeat(10) { journal.add("line $it with some padding") }
        journal.flush(2000)
        assertTrue(journal.previousFile.exists())
        assertFalse(journal.file.exists())
        journal.add("after rotation")
        journal.flush(2000)
        assertTrue(journal.file.readText().contains("after rotation"))
        journal.close()
    }

    @Test fun memoryKeepsOnlyNewestLines() {
        val journal = ConnectionJournal(folder.root)
        repeat(ConnectionJournal.MAX_LINES + 5) { journal.add("l$it") }
        val lines = journal.lines()
        assertEquals(ConnectionJournal.MAX_LINES, lines.size)
        assertEquals("l5", lines.first())
        journal.close()
    }

    @Test fun linesAfterCloseStayInMemoryOnly() {
        val journal = ConnectionJournal(folder.root)
        journal.add("before")
        journal.close()
        journal.add("after")
        journal.flush(500)
        assertEquals(listOf("before", "after"), journal.lines())
    }
}
