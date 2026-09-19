package com.futo.platformplayer.queue

import kotlin.random.Random

/**
 * The play queue as a plain data structure, free of Android and player concerns.
 *
 * - The playing item is tracked as an entry, never as an index, so it can't drift when the queue changes.
 * - Shuffle is a second ordering over the same entries. Every operation keeps both orderings in step,
 *   so turning shuffle off simply continues in the original order from the same item.
 * - [items] is the order the user sees and plays through: the shuffled order while shuffled.
 * - Removing the playing item keeps it as [current] (it is still playing) without it being listed;
 *   the item that followed it stays next.
 * - Items are identified by [keyOf]; an item is never queued twice.
 */
class PlayQueue<T : Any>(private val keyOf: (T) -> String, private val random: Random = Random.Default) {
    private class Entry<T>(val item: T) {
        var detached = false
    }

    private val original = mutableListOf<Entry<T>>()
    private var shuffled: MutableList<Entry<T>>? = null
    private var currentEntry: Entry<T>? = null

    var repeat = false

    val isShuffled: Boolean get() = shuffled != null

    private val order: List<Entry<T>> get() = shuffled ?: original

    /** The queue in play order, as shown to the user. */
    val items: List<T> get() = order.filter { !it.detached }.map { it.item }

    val size: Int get() = order.count { !it.detached }

    val isEmpty: Boolean get() = size == 0

    val current: T? get() = currentEntry?.item

    /** Index of [current] in [items], or -1 when there is none or it has been removed while playing. */
    val currentIndex: Int get() = currentEntry?.takeIf { !it.detached }?.let { visible().indexOf(it) } ?: -1

    fun contains(item: T): Boolean = find(item) != null

    fun indexOf(item: T): Int = visible().indexOfFirst { keyOf(it.item) == keyOf(item) }

    //Replacing
    fun set(items: List<T>, startAt: Int = 0, shuffle: Boolean = false) {
        clear()
        val seen = hashSetOf<String>()
        items.filter { seen.add(keyOf(it)) }.mapTo(original) { Entry(it) }
        if (original.isEmpty())
            return

        if (shuffle) {
            shuffled = original.shuffled(random).toMutableList()
            currentEntry = shuffled!!.first()
        } else {
            //startAt refers to [items]; duplicates dropped above may have shifted positions
            val startKey = items.getOrNull(startAt)?.let(keyOf)
            currentEntry = original.firstOrNull { keyOf(it.item) == startKey } ?: original.first()
        }
    }

    fun clear() {
        original.clear()
        shuffled = null
        currentEntry = null
        repeat = false
    }

    //Navigation
    fun peekNext(wrap: Boolean = repeat): T? = nextEntry(wrap)?.item

    fun peekPrevious(wrap: Boolean = repeat): T? = previousEntry(wrap)?.item

    /** Moves on to the next item and returns it, or returns null (staying put) at the end of the queue. */
    fun advance(wrap: Boolean = repeat, removeCurrent: Boolean = false): T? {
        val leaving = currentEntry
        val next = nextEntry(wrap)
        if (removeCurrent && leaving != null)
            drop(leaving)
        else
            dropIfDetached(leaving, next)
        if (next == null || next === leaving) {
            currentEntry = if (removeCurrent) null else currentEntry
            return null
        }
        currentEntry = next
        return next.item
    }

    fun goBack(wrap: Boolean = repeat, removeCurrent: Boolean = false): T? {
        val leaving = currentEntry
        val previous = previousEntry(wrap)
        if (removeCurrent && leaving != null)
            drop(leaving)
        else
            dropIfDetached(leaving, previous)
        if (previous == null || previous === leaving) {
            currentEntry = if (removeCurrent) null else currentEntry
            return null
        }
        currentEntry = previous
        return previous.item
    }

    /** Makes a queued item the current one. Returns false when it isn't queued. */
    fun jumpTo(item: T): Boolean {
        val entry = find(item) ?: return false
        dropIfDetached(currentEntry, entry)
        currentEntry = entry
        return true
    }

    //Adding and moving
    /** Appends an item; refuses (returns false) when it is already queued. */
    fun addLast(item: T): Boolean {
        if (find(item) != null)
            return false
        val entry = Entry(item)
        original.add(entry)
        shuffled?.add(entry)
        if (currentEntry == null)
            currentEntry = entry
        return true
    }

    /** Places an item directly after the current one, moving it there if it is already queued. */
    fun addNext(item: T) {
        val existing = find(item)
        if (existing != null && existing === currentEntry)
            return
        val entry = existing?.also { unlink(it) } ?: Entry(item)
        if (currentEntry == null) {
            insertAt(entry, 0)
            currentEntry = entry
            return
        }
        insertAfterCurrent(entry)
    }

    /** Plays an item now: jumps to it when queued, otherwise slots it in after the current item first. */
    fun playNow(item: T) {
        if (jumpTo(item))
            return
        addNext(item)
        jumpTo(item)
    }

    fun moveToFirst(item: T) {
        val entry = find(item) ?: return
        unlink(entry)
        insertAt(entry, 0)
    }

    fun moveToLast(item: T) {
        val entry = find(item) ?: return
        unlink(entry)
        original.add(entry)
        shuffled?.add(entry)
    }

    /**
     * Applies an order chosen by the user (drag and drop). Items that are no longer queued are ignored and
     * items queued in the meantime are kept at the end, so a stale view can't resurrect or lose anything.
     */
    fun reorder(newOrder: List<T>) {
        val target = shuffled ?: original
        val byKey = target.filter { !it.detached }.associateBy { keyOf(it.item) }
        val reordered = newOrder.mapNotNull { byKey[keyOf(it)] }.distinct().toMutableList()
        reordered += target.filter { !it.detached && it !in reordered }

        //A removed-but-playing item stays in front of whatever followed it, so what plays next doesn't change
        val detached = target.filter { it.detached }.map { it to successorOf(target, it) }
        target.clear()
        target.addAll(reordered)
        for ((entry, successor) in detached) {
            val at = successor?.let { target.indexOf(it) } ?: -1
            if (at >= 0) target.add(at, entry) else target.add(entry)
        }
    }

    //Removing
    /** Removes an item. The current item keeps playing, unlisted, and what followed it stays next. */
    fun remove(item: T) {
        val entry = find(item) ?: return
        if (entry === currentEntry)
            entry.detached = true
        else
            unlink(entry)
    }

    //Shuffle
    /**
     * Turning shuffle on keeps what has already been played (everything before the current item) in place and
     * shuffles only what is still to come. Turning it off continues in the original order from the current item.
     */
    fun setShuffle(on: Boolean) {
        if (on == isShuffled)
            return
        if (!on) {
            shuffled = null
            return
        }
        val index = currentEntry?.let { original.indexOf(it) } ?: -1
        val played = original.take(index + 1)
        shuffled = (played + original.drop(index + 1).shuffled(random)).toMutableList()
    }

    //Internals
    private fun visible(): List<Entry<T>> = order.filter { !it.detached }

    private fun find(item: T): Entry<T>? = order.firstOrNull { !it.detached && keyOf(it.item) == keyOf(item) }

    private fun nextEntry(wrap: Boolean): Entry<T>? {
        val o = order
        val current = currentEntry ?: return o.firstOrNull { !it.detached }
        val index = o.indexOf(current)
        if (index < 0)
            return null
        for (i in index + 1 until o.size)
            if (!o[i].detached) return o[i]
        if (wrap)
            for (i in 0 until index)
                if (!o[i].detached) return o[i]
        return null
    }

    private fun previousEntry(wrap: Boolean): Entry<T>? {
        val o = order
        val current = currentEntry ?: return null
        val index = o.indexOf(current)
        if (index < 0)
            return null
        for (i in index - 1 downTo 0)
            if (!o[i].detached) return o[i]
        if (wrap)
            for (i in o.size - 1 downTo index + 1)
                if (!o[i].detached) return o[i]
        return null
    }

    private fun insertAfterCurrent(entry: Entry<T>) {
        val current = currentEntry!!
        original.add(original.indexOf(current) + 1, entry)
        shuffled?.let { it.add(it.indexOf(current) + 1, entry) }
    }

    private fun insertAt(entry: Entry<T>, index: Int) {
        original.add(index.coerceIn(0, original.size), entry)
        shuffled?.let { it.add(index.coerceIn(0, it.size), entry) }
    }

    private fun successorOf(list: List<Entry<T>>, entry: Entry<T>): Entry<T>? =
        list.drop(list.indexOf(entry) + 1).firstOrNull { !it.detached }

    private fun unlink(entry: Entry<T>) {
        original.remove(entry)
        shuffled?.remove(entry)
    }

    private fun drop(entry: Entry<T>) {
        unlink(entry)
        if (currentEntry === entry)
            currentEntry = null
    }

    private fun dropIfDetached(leaving: Entry<T>?, arriving: Entry<T>?) {
        if (leaving != null && leaving.detached && leaving !== arriving)
            unlink(leaving)
    }
}
