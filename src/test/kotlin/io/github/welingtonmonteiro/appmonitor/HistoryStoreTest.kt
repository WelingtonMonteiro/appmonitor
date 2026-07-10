package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.MemoryHistory.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** File-backed persistence of memory sessions, exercised against a temp directory. */
class HistoryStoreTest {

    private lateinit var dir: Path
    private lateinit var store: HistoryStore

    private val appId = "app-1"
    private val session = listOf(Sample(1000L, 2048L, 10.0), Sample(3000L, 4096L, 20.0))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("appmonitor-history-test")
        store = HistoryStore(dir)
    }

    @After
    fun tearDown() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun savesAndReloadsTheCurrentSessionAndPid() {
        store.saveCurrent(appId, rootPid = 4242L, samples = session)

        val restored = store.loadCurrent(appId)
        assertEquals(2, restored.size)
        assertEquals(2048L, restored[0].rssKb)
        assertEquals(4096L, restored[1].rssKb)
        assertEquals(4242L, store.loadCurrentPid(appId))
    }

    @Test
    fun missingAppReadsAsEmptyNotError() {
        assertTrue(store.loadCurrent("never-saved").isEmpty())
        assertTrue(store.loadPrevious("never-saved").isEmpty())
        assertEquals(null, store.loadCurrentPid("never-saved"))
    }

    @Test
    fun emptySessionIsNotWritten() {
        store.saveCurrent(appId, rootPid = 1L, samples = emptyList())
        assertTrue(store.loadCurrent(appId).isEmpty())
        assertEquals(null, store.loadCurrentPid(appId))
    }

    @Test
    fun promoteMakesTheCurrentSessionThePreviousOne() {
        store.saveCurrent(appId, rootPid = 1L, samples = session)
        store.promoteToPrevious(appId)

        val previous = store.loadPrevious(appId)
        assertEquals(2, previous.size)
        assertEquals(4096L, previous[1].rssKb)
    }

    @Test
    fun deleteRemovesEveryFileOfTheApp() {
        store.saveCurrent(appId, rootPid = 1L, samples = session)
        store.promoteToPrevious(appId)

        store.deleteFor(appId)

        assertTrue(store.loadCurrent(appId).isEmpty())
        assertTrue(store.loadPrevious(appId).isEmpty())
        assertEquals(null, store.loadCurrentPid(appId))
    }
}
