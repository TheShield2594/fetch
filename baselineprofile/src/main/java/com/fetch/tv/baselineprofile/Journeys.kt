package com.fetch.tv.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val TARGET_PACKAGE = "com.fetch.tv"

// English labels from strings.xml / strings_setup.xml; inEnglish keeps them predictable.
private val SIGN_IN_SKIP = labelPattern("Continue without account")
private val WIZARD_STEP_INDICATOR = labelPattern("1  Server")
private val WIZARD_EXPERIENCE_TITLE = labelPattern("Choose your Fetch experience")
private val WIZARD_SKIP = labelPattern("Skip for now")
private val WIZARD_CONTINUE = labelPattern("Continue")
private val WIZARD_ADVANCED = labelPattern("Advanced")

// Server, Sources, Metadata and Experience, plus one retry for a press that didn't land.
private const val WIZARD_MAX_STEPS = 5

// Forces an English locale so onboarding labels are predictable regardless of device language,
// and restores the device's own afterwards.
internal inline fun MacrobenchmarkScope.inEnglish(block: () -> Unit) {
    val originalLocale = device.executeShellCommand("getprop persist.sys.locale").trim()
    device.executeShellCommand("setprop persist.sys.locale en-US")
    device.executeShellCommand("settings put system system_locales en-US")
    try {
        block()
    } finally {
        if (originalLocale.isNotBlank()) {
            device.executeShellCommand("setprop persist.sys.locale $originalLocale")
            device.executeShellCommand("settings put system system_locales $originalLocale")
        }
    }
}

// Cold launches the app and waits for Home, stepping through first-run onboarding when a fresh
// install shows it: sign-in, then the first-run wizard, whose last step (Advanced) opens Home.
internal fun MacrobenchmarkScope.launchToHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), 5_000)
    device.waitForIdle()

    if (device.wait(Until.hasObject(By.text(SIGN_IN_SKIP)), 8_000) == true) {
        device.focusAndSelect(SIGN_IN_SKIP)
        device.wait(Until.hasObject(By.text(WIZARD_SKIP)), 10_000)
    }
    // Also covers a wizard left half-done by an earlier run that stopped after sign-in.
    device.completeFirstRunWizard()
    device.waitForIdle()
    Thread.sleep(3_000)
}

// Scrolls across the focused Home row, then down through the rows, then back up toward a title,
// which compiles the row, card, vertical list and prefetch paths. Focus must be in the content.
internal fun UiDevice.scrollHome() {
    repeat(6) {
        pressDPadRight()
        waitForIdle()
        Thread.sleep(120)
    }
    repeat(6) {
        pressDPadDown()
        waitForIdle()
        Thread.sleep(150)
    }
    repeat(2) {
        pressDPadUp()
        waitForIdle()
        Thread.sleep(120)
    }
}

// Each setup step has one Skip for now button that moves on; it reads Continue once something is
// set up (the default addons count on Sources). The Server step's pairing form has its own
// Continue, so Skip for now wins whenever it is on screen. The Experience step also has Skip for
// now, so it is checked first. A profile behind a parent PIN sees only that last step, without the
// step indicator. Anything else is not the wizard (Home can have its own Continue), so it stops.
private fun UiDevice.completeFirstRunWizard() {
    repeat(WIZARD_MAX_STEPS) {
        when {
            hasObject(By.text(WIZARD_EXPERIENCE_TITLE)) -> {
                focusAndSelect(WIZARD_ADVANCED)
                return
            }
            !hasObject(By.text(WIZARD_STEP_INDICATOR)) -> return
            hasObject(By.text(WIZARD_SKIP)) -> focusAndSelect(WIZARD_SKIP, awaitGone = false)
            hasObject(By.text(WIZARD_CONTINUE)) -> focusAndSelect(WIZARD_CONTINUE, awaitGone = false)
            else -> return
        }
    }
}

// Moves D-pad focus until the control carrying the label is focused and then selects it, since TV
// devices have no touch input. With awaitGone, the step only counts once the label disappears, so a
// mis-selection keeps searching; without it (a label every wizard step repeats) one press is enough.
private fun UiDevice.focusAndSelect(pattern: Pattern, awaitGone: Boolean = true, maxSteps: Int = 24): Boolean {
    if (wait(Until.hasObject(By.text(pattern)), 10_000) != true) return false
    repeat(maxSteps) { step ->
        if (isFocusedLabel(pattern)) {
            pressDPadCenter()
            waitForIdle()
            Thread.sleep(1_500)
            if (!awaitGone || wait(Until.gone(By.text(pattern)), 5_000) == true) return true
        }
        // Alternate directions so both vertical lists and side-by-side cards are reachable.
        if (step % 2 == 0) pressDPadDown() else pressDPadRight()
        waitForIdle()
        Thread.sleep(250)
    }
    return false
}

// Reports whether the currently focused control carries the label, treating nodes that disappear mid-query as a non-match.
private fun UiDevice.isFocusedLabel(pattern: Pattern): Boolean {
    return try {
        val focused = findObject(By.focused(true)) ?: return false
        if (focused.text?.let { pattern.matcher(it).matches() } == true) return true
        // Require the focused node to be the clickable control itself so a large parent container is not mistaken for the button.
        focused.isClickable && focused.findObject(By.text(pattern)) != null
    } catch (e: StaleObjectException) {
        false
    }
}

private fun labelPattern(label: String): Pattern =
    Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
