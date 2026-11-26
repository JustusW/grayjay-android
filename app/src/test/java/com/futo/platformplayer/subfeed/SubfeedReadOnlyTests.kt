package com.futo.platformplayer.subfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bridge is read only: no code path in com.futo.platformplayer.subfeed calls an API that changes Grayjay's Watch
 * Later, playlists, subscriptions or history, except the two restores the user confirms in the settings
 * (restoreWatchLater, restorePlaylists), and those are reached from their confirmation dialogs only.
 */
class SubfeedReadOnlyTests {
    private val sources: Map<String, List<String>> by lazy {
        //Unit tests run in the module directory (app/)
        val dir = listOf(File("src/main/java/com/futo/platformplayer/subfeed"), File("app/src/main/java/com/futo/platformplayer/subfeed"))
            .first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".kt") }!!.associate { it.name to it.readLines() }
    }

    private val mutations = listOf(
        //StatePlaylists: Watch Later and playlists
        "updateWatchLater", "updateWatchLaterOrdering", "addToWatchLater", "removeFromWatchLater", "setWatchLaterAddTime",
        "createOrUpdatePlaylist", "addToPlaylist", "removePlaylist", "didPlay", "createPlaylistFromChannel", "playlistStore",
        //StateSubscriptions
        "addSubscription", "removeSubscription", "saveSubscription", "saveSubscriptionAsync", "saveSubscriptionOther",
        "saveSubscriptionOtherAsync", "applySubscriptionRemovals", "updateSubscriptions", "updateSubscriptionChannel",
        "clearSubscriptionFeed",
        //StateHistory
        "updateHistoryPosition", "markAsWatched", "removeHistory", "removeHistoryRange", "syncRemoteHistory"
    )
    private val allowed = setOf("restoreWatchLater", "restorePlaylists")
    private val funLine = Regex("^    (?:(?:private|internal|public|suspend|inline|override)\\s+)*fun\\s+(?:<[^>]+>\\s*)?(\\w+)")

    /** The class member function (4-space indent) each line belongs to, null outside one. */
    private fun enclosing(lines: List<String>): List<String?> {
        var current: String? = null
        return lines.map { line ->
            val fn = funLine.find(line)
            val indent = line.length - line.trimStart().length
            when {
                fn != null -> current = fn.groupValues[1]
                line.isBlank() || indent > 4 || line.trim() == "}" && indent == 4 -> {}
                else -> current = null //a property, an annotation, the class itself: outside any function
            }
            current
        }
    }

    private fun code(line: String): String? {
        val t = line.trimStart()
        return if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) null else line.substringBefore("//")
    }

    private fun calls(name: String) = Regex("(?<![\\w])$name\\s*[({]|\\.$name\\b")

    @Test
    fun noMutationOutsideTheRestores() {
        val offending = mutableListOf<String>()
        for ((file, lines) in sources) {
            val owners = enclosing(lines)
            for ((i, line) in lines.withIndex()) {
                val code = code(line) ?: continue
                for (m in mutations) {
                    if (calls(m).containsMatchIn(code) && funLine.find(line)?.groupValues?.get(1) != m && owners[i] !in allowed)
                        offending.add("$file:${i + 1} ${owners[i]}: ${line.trim()}")
                }
            }
        }
        assertEquals("mutation API calls outside the confirmed restores", listOf<String>(), offending)
    }

    @Test
    fun theRestoresAreReachedFromTheirConfirmationsOnly() {
        val callers = mutableMapOf<String, MutableSet<String?>>()
        for ((_, lines) in sources) {
            val owners = enclosing(lines)
            for ((i, line) in lines.withIndex()) {
                val code = code(line) ?: continue
                if (funLine.containsMatchIn(line))
                    continue
                for (r in allowed)
                    if (Regex("\\b$r\\(").containsMatchIn(code))
                        callers.getOrPut(r) { mutableSetOf() }.add(owners[i])
            }
        }
        assertEquals(setOf<String?>("confirmRestore"), callers["restoreWatchLater"])
        assertEquals(setOf<String?>("confirmPlaylistRestore"), callers["restorePlaylists"])
    }

    @Test
    fun theScanSeesTheBridge() {
        //Guards the scan itself: it reads the bridge and finds the restores' own mutation calls
        val bridge = sources["SubfeedBridge.kt"]!!
        val owners = enclosing(bridge)
        val found = bridge.indices.filter { i -> mutations.any { calls(it).containsMatchIn(code(bridge[i]) ?: "") } }.map { owners[it] }.toSet()
        assertEquals(allowed, found)
        assertTrue(sources.size >= 7)
    }

    @Test
    fun theScanCatchesAMutation() {
        val lines = listOf(
            "class X {",
            "    private fun pull(ctx: Context) {",
            "        StatePlaylists.instance.updateWatchLater(ArrayList(updated), false)",
            "    }",
            "}"
        )
        val owners = enclosing(lines)
        assertEquals("pull", owners[2])
        assertTrue(calls("updateWatchLater").containsMatchIn(lines[2]))
    }
}
