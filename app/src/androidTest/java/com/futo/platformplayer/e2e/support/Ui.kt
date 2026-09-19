package com.futo.platformplayer.e2e.support

import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher

/** The app has no idling resources, so everything that depends on async work is polled. */
object Wait {
    const val DEFAULT_TIMEOUT_MS = 15_000L

    fun <T> until(description: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> T): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: Throwable? = null
        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                last = t
            }
            if (SystemClock.uptimeMillis() > deadline)
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description", last)
            Thread.sleep(250)
        }
    }
}

/** Matches only the first view (in traversal order) that satisfies [matcher]; avoids ambiguity in lists. */
fun first(matcher: Matcher<View>): Matcher<View> = object : TypeSafeMatcher<View>() {
    private var found: View? = null

    override fun describeTo(description: Description) {
        description.appendText("first view matching ")
        matcher.describeTo(description)
    }

    override fun matchesSafely(view: View): Boolean {
        if (found == null && matcher.matches(view))
            found = view
        return view === found
    }
}

fun onFirst(matcher: Matcher<View>): ViewInteraction = onView(first(matcher))
