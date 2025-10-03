package protect.card_locker

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import protect.card_locker.screens.ScanActivity

/**
 * This test runs on the local JVM using Robolectric and interacts with the UI
 * using Espresso to verify the ScanActivity's functionality.
 */
@RunWith(AndroidJUnit4::class)
class ScanActivityTest {

    /**
     * ActivityScenarioRule is a JUnit rule that launches a given activity
     * before the test starts and terminates it after the test finishes.
     */
    @get:Rule
    val activityScenarioRule = ActivityScenarioRule(ScanActivity::class.java)

    @Test
    fun fabClick_opensOtherOptionsDialog() {
        // ARRANGE: The activity is launched by the rule.

        // ACT: Find the FloatingActionButton by its ID and perform a click.
        onView(withId(R.id.fabOtherOptions)).perform(click())

        // ASSERT: Check that a dialog appears with the correct title.
        // .inRoot(isDialog()) is crucial here to tell Espresso to look for the
        // text inside a dialog window, not the main activity window.
        onView(withText(R.string.add_a_card_in_a_different_way)).inRoot(isDialog())
            .check(matches(isDisplayed()))

        // You can also assert that an item in the dialog list is visible
        onView(withText(R.string.addManually)).inRoot(isDialog()).check(matches(isDisplayed()))
    }
}