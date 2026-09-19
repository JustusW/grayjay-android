package com.futo.platformplayer.e2e.support

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.util.HumanReadables
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals

/**
 * Scrolls a RecyclerView so the item containing a TextView with [text] sits at the top of the list,
 * clear of anything that overlaps the list's bottom edge (the minimized player bar).
 */
fun scrollItemToTop(text: String): ViewAction = object : ViewAction {
    override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
    override fun getDescription() = "scroll item with text '$text' to the top"

    override fun perform(uiController: UiController, view: View) {
        val recycler = view as RecyclerView
        val adapter = recycler.adapter ?: error("RecyclerView has no adapter")
        for (position in 0 until adapter.itemCount) {
            recycler.scrollToPosition(position)
            uiController.loopMainThreadUntilIdle()
            val holder = recycler.findViewHolderForAdapterPosition(position) ?: continue
            if (findText(holder.itemView, text)) {
                recycler.scrollBy(0, holder.itemView.top - recycler.paddingTop)
                uiController.loopMainThreadUntilIdle()
                return
            }
        }
        throw AssertionError("No item with text '$text' in ${HumanReadables.describe(view)}")
    }
}

/** The view's own visibility, ignoring ancestors (e.g. Up Next keeps its state while a panel covers it). */
fun ownVisibility(visibility: Int): Matcher<View> = object : org.hamcrest.TypeSafeMatcher<View>() {
    override fun describeTo(description: org.hamcrest.Description) {
        description.appendText("own visibility $visibility")
    }

    override fun matchesSafely(view: View) = view.visibility == visibility
}

/** Asserts the titles (TextViews with [titleId]) of a RecyclerView's items, in adapter order. */
fun hasItemTitles(titleId: Int, expected: List<String>): ViewAssertion = ViewAssertion { view, noViewFoundException ->
    if (noViewFoundException != null) throw noViewFoundException
    assertEquals(expected, itemTitles(view as RecyclerView, titleId))
}

fun itemTitles(recycler: RecyclerView, titleId: Int): List<String> =
    (0 until recycler.childCount)
        .map { recycler.getChildAt(it) }
        .sortedBy { recycler.getChildAdapterPosition(it) }
        .mapNotNull { it.findViewById<TextView>(titleId)?.text?.toString() }

private fun findText(root: View, text: String): Boolean {
    if (root is TextView && root.text?.toString() == text) return true
    if (root is android.view.ViewGroup)
        for (i in 0 until root.childCount)
            if (findText(root.getChildAt(i), text)) return true
    return false
}
