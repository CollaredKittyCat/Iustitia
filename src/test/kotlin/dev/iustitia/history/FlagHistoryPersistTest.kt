package dev.iustitia.history

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the persisted-history merge state behind
 * [dev.iustitia.persistence.PersistenceManager.onToggle] (issue #28). Persistence stays off in
 * the default config, so nothing here reads or writes the roaming store.
 */
class FlagHistoryPersistTest {

    private val uuid = UUID.randomUUID()

    @BeforeTest fun setUp() = FlagHistory.reset()
    @AfterTest fun tearDown() = FlagHistory.reset()

    private fun merge(lastAlertWallMs: Long = 0L) = FlagHistory.mergePersisted(
        uuid, "Steve", alertCount = 1, alertedChecks = setOf("reach"),
        lastAlertWallMs = lastAlertWallMs, flagCountMap = mapOf("reach" to 3),
        maxVlMap = mapOf("reach" to 4.0),
        persistedFlags = listOf(FlagHistory.Flag(10, "reach", "reach", 4.0, 1_000L, null)),
    )

    @Test
    fun `a fresh store does not hold the persisted history`() {
        assertFalse(FlagHistory.holdsPersisted())
    }

    @Test
    fun `reset clears the holds-persisted mark so the next toggle reloads`() {
        FlagHistory.markHoldsPersisted()
        assertTrue(FlagHistory.holdsPersisted())
        FlagHistory.reset()
        assertFalse(FlagHistory.holdsPersisted())
    }

    @Test
    fun `clearing one player keeps the holds-persisted mark`() {
        FlagHistory.markHoldsPersisted()
        FlagHistory.clearPlayer(uuid)
        assertTrue(FlagHistory.holdsPersisted())
    }

    @Test
    fun `merging the persisted store twice double counts, which is what the toggle guard prevents`() {
        merge()
        assertEquals(3, FlagHistory.flagCounts(uuid)["reach"])
        assertEquals(1, FlagHistory.flags(uuid).size)
        merge()
        assertEquals(6, FlagHistory.flagCounts(uuid)["reach"])
        assertEquals(2, FlagHistory.flags(uuid).size)
    }

    @Test
    fun `merging into a store that does not hold it yet adds counts on top of live flags`() {
        FlagHistory.recordFlag(uuid, "Steve", "reach", "reach", 2.0, tick = 50)
        merge()
        assertEquals(4, FlagHistory.flagCounts(uuid)["reach"])
        assertEquals(2, FlagHistory.flags(uuid).size)
        // live flag stays newest
        assertEquals(50, FlagHistory.flags(uuid).first().tick)
    }

    @Test
    fun `an older persisted decay clock does not move a newer live one back`() {
        merge(lastAlertWallMs = 5_000L)
        assertEquals(5_000L, FlagHistory.lastAlertWallMsOf(uuid))
        merge(lastAlertWallMs = 1_000L)
        assertEquals(5_000L, FlagHistory.lastAlertWallMsOf(uuid))
        merge(lastAlertWallMs = 9_000L)
        assertEquals(9_000L, FlagHistory.lastAlertWallMsOf(uuid))
    }
}
