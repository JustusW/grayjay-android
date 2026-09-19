package com.futo.platformplayer

import com.futo.platformplayer.queue.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayQueueTests {
    private fun queue(vararg items: String, startAt: Int = 0, seed: Int = 1) =
        PlayQueue<String>({ it }, Random(seed)).apply { set(items.toList(), startAt) }

    //Basics
    @Test
    fun setStartsAtRequestedItem() {
        val q = queue("a", "b", "c", startAt = 1)
        assertEquals("b", q.current)
        assertEquals(1, q.currentIndex)
        assertEquals(listOf("a", "b", "c"), q.items)
    }

    @Test
    fun setDropsDuplicatesAndStillStartsAtTheRequestedItem() {
        val q = queue("a", "b", "a", "c", startAt = 3)
        assertEquals(listOf("a", "b", "c"), q.items)
        assertEquals("c", q.current)
    }

    @Test
    fun advanceWalksThroughAndStopsAtTheEnd() {
        val q = queue("a", "b")
        assertEquals("b", q.peekNext())
        assertEquals("b", q.advance())
        assertNull(q.peekNext())
        assertNull(q.advance())
        assertEquals("b", q.current)
    }

    @Test
    fun repeatWrapsAround() {
        val q = queue("a", "b", startAt = 1)
        q.repeat = true
        assertEquals("a", q.peekNext())
        assertEquals("a", q.advance())
    }

    @Test
    fun forcedWrapWorksWithoutRepeat() {
        val q = queue("a", "b", startAt = 1)
        assertEquals("a", q.advance(wrap = true))
        assertEquals("b", q.goBack(wrap = true))
    }

    @Test
    fun goBackStopsAtTheStartWithoutWrap() {
        val q = queue("a", "b")
        assertNull(q.goBack(wrap = false))
        assertEquals("a", q.current)
    }

    @Test
    fun advanceCanConsumeTheFinishedItem() {
        val q = queue("a", "b", "c")
        assertEquals("b", q.advance(removeCurrent = true))
        assertEquals(listOf("b", "c"), q.items)
        assertEquals(0, q.currentIndex)
    }

    @Test
    fun consumingTheLastItemEndsTheQueue() {
        val q = queue("a", "b", startAt = 1)
        assertNull(q.advance(removeCurrent = true))
        assertEquals(listOf("a"), q.items)
    }

    //Adding
    @Test
    fun addLastAppendsAndRefusesDuplicates() {
        val q = queue("a", "b")
        assertTrue(q.addLast("c"))
        assertFalse(q.addLast("b"))
        assertEquals(listOf("a", "b", "c"), q.items)
    }

    @Test
    fun addNextInsertsDirectlyAfterTheCurrentItem() {
        val q = queue("a", "b", "c", startAt = 1)
        q.addNext("x")
        assertEquals(listOf("a", "b", "x", "c"), q.items)
        assertEquals("b", q.current)
        assertEquals("x", q.peekNext())
    }

    @Test
    fun addNextMovesAnAlreadyQueuedItemInsteadOfDuplicating() {
        val q = queue("a", "b", "c", "d")
        q.addNext("d")
        assertEquals(listOf("a", "d", "b", "c"), q.items)
    }

    @Test
    fun addNextOfTheCurrentItemChangesNothing() {
        val q = queue("a", "b", "c", startAt = 1)
        q.addNext("b")
        assertEquals(listOf("a", "b", "c"), q.items)
        assertEquals("b", q.current)
    }

    //Tap policy: play now, slotted in after the interrupted item
    @Test
    fun playNowSlotsInAfterTheCurrentItemAndPlaysIt() {
        val q = queue("a", "b", "c")
        q.playNow("x")
        assertEquals(listOf("a", "x", "b", "c"), q.items)
        assertEquals("x", q.current)
        assertEquals("b", q.peekNext())
    }

    @Test
    fun playNowJumpsToAnAlreadyQueuedItem() {
        val q = queue("a", "b", "c")
        q.playNow("c")
        assertEquals(listOf("a", "b", "c"), q.items)
        assertEquals("c", q.current)
    }

    //Moving
    @Test
    fun moveToFirstAndLastKeepTheCurrentItem() {
        val q = queue("a", "b", "c", "d", "e", startAt = 2)
        q.moveToFirst("e")
        assertEquals(listOf("e", "a", "b", "c", "d"), q.items)
        assertEquals("c", q.current)
        q.moveToLast("a")
        assertEquals(listOf("e", "b", "c", "d", "a"), q.items)
        assertEquals("c", q.current)
        assertEquals("d", q.peekNext())
    }

    @Test
    fun reorderReplacesTheVisibleOrderAndKeepsTheCurrentItem() {
        val q = queue("a", "b", "c", startAt = 1)
        q.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), q.items)
        assertEquals("b", q.current)
        assertNull(q.peekNext())
    }

    @Test
    fun reorderIgnoresItemsThatAreNoLongerQueuedAndKeepsNewOnes() {
        val q = queue("a", "b", "c")
        val staleView = listOf("c", "b", "a")
        q.advance(removeCurrent = true) //"a" leaves the queue while the stale view still shows it
        q.addLast("d")
        q.reorder(staleView)
        assertEquals(listOf("c", "b", "d"), q.items)
    }

    //Removing
    @Test
    fun removingAnotherItemKeepsPositionOnTheCurrentOne() {
        val q = queue("a", "b", "c", "d", startAt = 2)
        q.remove("a")
        assertEquals("c", q.current)
        assertEquals("d", q.peekNext())
    }

    @Test
    fun removingTheCurrentItemKeepsItPlayingAndDoesNotSkipTheNextOne() {
        val q = queue("a", "b", "c", "d", startAt = 1)
        q.remove("b")
        assertEquals(listOf("a", "c", "d"), q.items)
        assertEquals("b", q.current)
        assertEquals(-1, q.currentIndex)
        assertEquals("c", q.peekNext())
        assertEquals("a", q.peekPrevious(wrap = false))
        assertEquals("c", q.advance())
        assertEquals(listOf("a", "c", "d"), q.items)
    }

    @Test
    fun reorderKeepsWhatFollowsARemovedPlayingItemNext() {
        val q = queue("a", "b", "c", "d", startAt = 1)
        q.remove("b")
        q.reorder(listOf("d", "a", "c"))
        assertEquals(listOf("d", "a", "c"), q.items)
        assertEquals("b", q.current)
        assertEquals("c", q.peekNext())
    }

    @Test
    fun consumingARemovedCurrentItemDoesNotConsumeAnythingElse() {
        val q = queue("a", "b", "c")
        q.remove("a")
        assertEquals("b", q.advance(removeCurrent = true))
        assertEquals(listOf("b", "c"), q.items)
    }

    //Shuffle
    @Test
    fun shufflePlayDoesNotAlwaysStartWithTheFirstItem() {
        val firsts = (1..20).map { seed ->
            PlayQueue<String>({ it }, Random(seed)).apply { set(listOf("a", "b", "c", "d", "e", "f"), shuffle = true) }.current
        }
        assertTrue("always started with ${firsts.distinct()}", firsts.distinct().size > 1)
    }

    @Test
    fun shufflePlayContainsEverythingExactlyOnce() {
        val q = PlayQueue<String>({ it }, Random(7)).apply { set(listOf("a", "b", "c", "d", "e"), shuffle = true) }
        assertEquals(listOf("a", "b", "c", "d", "e"), q.items.sorted())
        assertEquals(0, q.currentIndex)
    }

    @Test
    fun turningShuffleOnKeepsTheCurrentItemAndWhatWasAlreadyPlayed() {
        val q = queue("a", "b", "c", "d", "e", "f", startAt = 2, seed = 3)
        q.setShuffle(true)
        assertEquals("c", q.current)
        assertEquals(listOf("a", "b", "c"), q.items.take(3))
        assertEquals(listOf("d", "e", "f"), q.items.drop(3).sorted())
    }

    @Test
    fun turningShuffleOffContinuesInTheOriginalOrder() {
        val q = queue("a", "b", "c", "d", "e", seed = 5)
        q.setShuffle(true)
        q.jumpTo("c")
        q.setShuffle(false)
        assertEquals(listOf("a", "b", "c", "d", "e"), q.items)
        assertEquals("c", q.current)
        assertEquals("d", q.peekNext())
    }

    @Test
    fun jumpingWhileShuffledContinuesInTheShuffledOrder() {
        val q = queue("a", "b", "c", "d", "e", "f", seed = 11)
        q.setShuffle(true)
        val target = q.items[4]
        q.jumpTo(target)
        assertEquals(target, q.current)
        assertEquals(q.items[5], q.peekNext())
    }

    @Test
    fun consumingWhileShuffledRemovesTheRightItem() {
        val q = queue("a", "b", "c", "d", seed = 2)
        q.setShuffle(true)
        val first = q.current
        val second = q.items[1]
        assertEquals(second, q.advance(removeCurrent = true))
        assertFalse(q.items.contains(first))
        q.setShuffle(false)
        assertFalse(q.items.contains(first))
        assertEquals(3, q.items.size)
    }

    @Test
    fun itemsAddedWhileShuffledLandWhereTheyWereAskedFor() {
        val q = queue("a", "b", "c", "d", seed = 9)
        q.setShuffle(true)
        q.addNext("x")
        assertEquals("x", q.peekNext())
        q.addLast("y")
        assertEquals("y", q.items.last())
        q.setShuffle(false)
        assertTrue(q.items.containsAll(listOf("x", "y")))
    }

    @Test
    fun aNewQueueDoesNotInheritShuffleOrRepeat() {
        val q = queue("a", "b", "c", seed = 4)
        q.setShuffle(true)
        q.repeat = true
        q.set(listOf("x", "y", "z"))
        assertFalse(q.isShuffled)
        assertFalse(q.repeat)
        assertEquals(listOf("x", "y", "z"), q.items)
        assertEquals("x", q.current)
    }

    @Test
    fun clearEmptiesEverything() {
        val q = queue("a", "b")
        q.clear()
        assertTrue(q.items.isEmpty())
        assertNull(q.current)
        assertNull(q.peekNext())
        assertNotEquals(true, q.isShuffled)
    }
}
