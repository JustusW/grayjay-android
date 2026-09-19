package com.futo.platformplayer.e2e.support

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.futo.platformplayer.R
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.e2e.World
import com.futo.platformplayer.fragment.mainactivity.main.VideoDetailFragment
import com.futo.platformplayer.states.StatePlayer
import com.futo.platformplayer.views.adapters.feedtypes.PreviewVideoView
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalToIgnoringCase
import org.junit.Assert.assertEquals

/*
 * Screen drivers. The video player (VideoDetailFragment) is always part of the view tree, even when closed,
 * and several ids repeat between it and the main screens, so every lookup is scoped to its container.
 */

object App {
    fun activity(): MainActivity {
        var activity: MainActivity? = null
        World.activity!!.onActivity { activity = it }
        return activity!!
    }
}

object PlayerUi {
    private val inPlayer = isDescendantOfA(withId(R.id.fragment_overlay))

    /**
     * The fragment reports MAXIMIZED from startup even before any video was opened (it maximizes its empty view),
     * and StatePlayer.isOpen is never set, so "closed" also has to be read from the container's visibility.
     */
    fun state(): VideoDetailFragment.State = Backdoor.onMain {
        val activity = App.activity()
        val container = activity.findViewById<View>(R.id.fragment_overlay)
        if (container.visibility != View.VISIBLE)
            VideoDetailFragment.State.CLOSED
        else
            (activity.supportFragmentManager.findFragmentById(R.id.fragment_overlay) as VideoDetailFragment).state
    }

    fun waitUntilPlaying(name: String) {
        Wait.until("'$name' to be playing") {
            assertEquals(name, Backdoor.onMain { StatePlayer.instance.currentVideo?.name })
            onView(withId(R.id.videodetail_title_minimized)).check(matches(withText(name)))
        }
    }

    /** Gets the player out of the way of the main screens; back first closes any open sub-panel. */
    fun minimize() {
        Wait.until("the player to be minimized or closed") {
            if (state() == VideoDetailFragment.State.MAXIMIZED) {
                pressBack()
                Thread.sleep(300)
            }
            check(state() != VideoDetailFragment.State.MAXIMIZED)
        }
    }

    fun close() {
        minimize()
        if (state() == VideoDetailFragment.State.CLOSED) return
        onView(withId(R.id.minimize_close)).perform(click())
        Wait.until("the player to close") { check(state() == VideoDetailFragment.State.CLOSED) }
    }

    fun maximize() {
        if (state() == VideoDetailFragment.State.MINIMIZED)
            onView(withId(R.id.videodetail_title_minimized)).perform(click())
        Wait.until("the player to be maximized") { check(state() == VideoDetailFragment.State.MAXIMIZED) }
    }

    /** Player controls are hidden until the video surface is tapped, and hide again after 3 seconds. */
    fun pressControl(id: Int) {
        maximize()
        val button = allOf(withId(id), isDescendantOfA(withId(R.id.video_player_controller)))
        //Show the controls and click in one go; they fade and auto-hide, so the whole thing is retried
        Wait.until("player control to be pressed") {
            try {
                onView(button).perform(click())
            } catch (hidden: Throwable) {
                onView(allOf(withId(R.id.gesture_control), isDescendantOfA(withId(R.id.videodetail_player)))).perform(click())
                Thread.sleep(700)
                onView(button).perform(click())
            }
        }
    }

    /** Things below the video (up next, recommendations) scroll inside the comments list's header. */
    fun reveal(matcher: Matcher<View>) {
        maximize()
        Wait.until("$matcher to be on screen") {
            try {
                onView(matcher).check(matches(isCompletelyDisplayed()))
            } catch (notYetLoaded: NoMatchingViewException) {
                throw notYetLoaded
            } catch (offScreen: Throwable) {
                //Exists but is cut off by the bottom of the screen
                onView(allOf(withId(R.id.recycler_comments), inPlayer)).perform(swipeUp())
                throw offScreen
            }
        }
    }

    fun tapRecommended(name: String) {
        val item = allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(withId(R.id.layout_recommended)))
        reveal(item)
        onView(item).perform(click())
    }

    fun upNextButton(id: Int): Matcher<View> = allOf(withId(id), isDescendantOfA(withId(R.id.up_next)))

    fun pressUpNextButton(id: Int) {
        reveal(upNextButton(id))
        onView(upNextButton(id)).perform(click())
    }

    /** Texts of the options in the menu open inside the player, top to bottom. */
    fun menuOptionTexts(): List<String> {
        val texts = mutableListOf<String>()
        onView(allOf(withId(R.id.overlay_slide_up_menu_items), inPlayer, isDisplayed())).check { view, noView ->
            if (noView != null) throw noView
            fun collect(v: View) {
                if (v.id == R.id.slide_up_menu_item_text && v is android.widget.TextView) texts += v.text.toString()
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(view)
        }
        return texts
    }

    /** Options overlays opened from inside the player (e.g. for a queued video). */
    fun chooseMenuOption(option: String) {
        val item = allOf(withId(R.id.slide_up_menu_item_text), withText(equalToIgnoringCase(option)), inPlayer, isDisplayed())
        Wait.until("menu option '$option'") { onView(item).perform(click()) }
    }
}

object QueuePanelUi {
    private val panel = withId(R.id.videodetail_container_queue)
    val list: Matcher<View> = allOf(withId(R.id.editor), isAssignableFrom(RecyclerView::class.java), isDescendantOfA(panel))

    fun open() {
        PlayerUi.pressUpNextButton(R.id.button_view)
        Wait.until("the queue panel to open") { onView(panel).check(matches(isDisplayed())) }
    }

    fun isOpen() = onView(panel).check(matches(allOf(isDisplayed(), withEffectiveVisibility(Visibility.VISIBLE))))

    fun row(name: String): Matcher<View> = allOf(withId(R.id.root), hasDescendant(withText(name)), isDescendantOfA(list))

    /** Rows near the bottom edge are covered by other controls (Espresso can't tell), so scroll them to the top. */
    private fun bringIntoView(name: String) =
        Wait.until("'$name' in the queue panel") { onView(list).perform(scrollItemToTop(name)) }

    fun tap(name: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(row(name)))).perform(click())
    }

    fun remove(name: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.image_trash), isDescendantOfA(row(name)))).perform(click())
    }

    fun openOptions(name: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.image_settings), isDescendantOfA(row(name)))).perform(click())
    }

    fun chooseOption(name: String, option: String) {
        openOptions(name)
        PlayerUi.chooseMenuOption(option)
    }

    fun assertMarkedAsPlaying(name: String) {
        bringIntoView(name)
        onView(allOf(withContentDescription("Now playing"), isDescendantOfA(row(name)))).check(matches(isDisplayed()))
    }
}

object FeedUi {
    private val feedList = allOf(withId(R.id.list_results), isDescendantOfA(withId(R.id.fragment_main)), isDisplayed())

    private fun item(name: String): Matcher<View> =
        allOf(isAssignableFrom(PreviewVideoView::class.java), hasDescendant(withText(name)), isDescendantOfA(feedList))

    fun bringIntoView(name: String) {
        PlayerUi.minimize()
        Wait.until("'$name' in the feed", timeoutMs = 30_000) { onView(feedList).perform(scrollItemToTop(name)) }
    }

    fun tap(name: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(item(name)))).perform(click())
    }

    fun addToQueue(name: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.button_add_to_queue), isDescendantOfA(item(name)))).perform(click())
    }

    fun chooseOption(name: String, option: String) {
        bringIntoView(name)
        onView(allOf(withId(R.id.button_add_to), isDescendantOfA(item(name)))).perform(click())
        val entry = allOf(withId(R.id.slide_up_menu_item_text), withText(equalToIgnoringCase(option)),
            isDescendantOfA(withId(R.id.fragment_main)), isDisplayed())
        Wait.until("menu option '$option'") { onView(entry).perform(click()) }
    }

    fun openChannelOf(channelName: String) {
        PlayerUi.minimize()
        //Only laid-out items can be found; scroll a video of that channel into view first
        Wait.until("a video by '$channelName' in the feed", timeoutMs = 30_000) { onView(feedList).perform(scrollItemToTop(channelName)) }
        Wait.until("channel link '$channelName'") {
            onFirst(allOf(withId(R.id.text_channel_name), withText(channelName), isDescendantOfA(feedList), isDisplayed())).perform(click())
        }
        Wait.until("the channel page") {
            onView(allOf(withId(R.id.recycler_videos), isDisplayed())).check(matches(isDisplayed()))
        }
    }

    fun tapOnChannelPage(name: String) {
        val list = allOf(withId(R.id.recycler_videos), isDisplayed())
        Wait.until("'$name' on the channel page") { onView(list).perform(scrollItemToTop(name)) }
        onView(allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(list))).perform(click())
    }
}

object NavUi {
    /** Bottom bar tab, or the same entry in the "More" overlay when the bar has no room for it. */
    fun openTab(label: String) {
        PlayerUi.minimize()
        val tab = allOf(withId(R.id.text_button), withText(label), isDescendantOfA(withId(R.id.bottom_bar_buttons)), isDisplayed())
        try {
            onView(tab).perform(click())
            return
        } catch (_: Throwable) {
        }
        onView(allOf(withId(R.id.text_button), withText("More"), isDescendantOfA(withId(R.id.bottom_bar_buttons)))).perform(click())
        val tile = allOf(withId(R.id.text_name), withText(label), isDescendantOfA(withId(R.id.more_menu_buttons)), isDisplayed())
        Wait.until("'$label' in the More menu") { onView(tile).perform(click()) }
    }

    fun openWatchLater() {
        openTab("Playlists")
        Wait.until("Watch Later 'View all'") {
            onView(allOf(withId(R.id.text_view_all), isDescendantOfA(withId(R.id.fragment_main)), isDisplayed())).perform(click())
        }
        waitForListEditor("Watch Later")
    }

    fun openPlaylist(name: String) {
        openTab("Playlists")
        Wait.until("playlist '$name'") {
            onView(allOf(withId(R.id.text_name), withText(name), isDescendantOfA(withId(R.id.recycler_playlists)), isDisplayed())).perform(click())
        }
        waitForListEditor(name)
    }

    private fun waitForListEditor(title: String) = Wait.until("the '$title' screen") {
        onView(allOf(withId(R.id.text_name), withText(title), isDescendantOfA(withId(R.id.fragment_main)), isDisplayed()))
            .check(matches(isDisplayed()))
    }

    fun pressListEditorButton(id: Int) =
        onView(allOf(withId(id), isDescendantOfA(withId(R.id.fragment_main)), isDisplayed())).perform(click())

    fun pressListEditorButton(text: String) = Wait.until("button '$text'") {
        onView(allOf(withText(text), isDescendantOfA(withId(R.id.fragment_main)), isDisplayed())).perform(click())
    }

    fun tapInListEditor(name: String) {
        val list = allOf(withId(R.id.video_list_editor), isAssignableFrom(RecyclerView::class.java), isDisplayed())
        Wait.until("'$name' in the list") { onView(list).perform(scrollItemToTop(name)) }
        onView(allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(list))).perform(click())
    }

    fun tapInHistory(name: String) {
        openTab("History")
        val list = allOf(withId(R.id.recycler_history), isDisplayed())
        Wait.until("'$name' in the history") { onView(list).perform(scrollItemToTop(name)) }
        onView(allOf(withId(R.id.text_video_name), withText(name), isDescendantOfA(list))).perform(click())
    }
}
