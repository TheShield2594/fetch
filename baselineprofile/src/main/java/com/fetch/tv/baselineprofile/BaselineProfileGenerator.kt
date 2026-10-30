package com.fetch.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Records two profiles. The startup profile covers launch to the first Home frame only, so R8 can
 * put that code first in the dex; recording the whole journey with includeInStartupProfile made it
 * a copy of the baseline profile. The journey adds Home scrolling and a detail page to the
 * baseline profile alone.
 *
 * The journey runs first (methods run in name order) so that the first-run onboarding, which only
 * a fresh install shows, is completed there and stays out of the startup profile.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateAppJourney() {
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = false) {
            inEnglish {
                launchToHome()

                // Move focus off the sidebar into the content grid.
                device.pressDPadRight()
                device.waitForIdle()

                device.scrollHome()

                // Open a title to compile the detail screen render path.
                device.pressDPadCenter()
                device.waitForIdle()
                Thread.sleep(2_500)

                // Return to the home screen.
                device.pressBack()
                device.waitForIdle()
                Thread.sleep(800)
            }
        }
    }

    @Test
    fun generateStartup() {
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            inEnglish { launchToHome() }
        }
    }
}
