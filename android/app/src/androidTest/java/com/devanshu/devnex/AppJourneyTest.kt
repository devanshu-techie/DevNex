package com.devanshu.devnex

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app (Demo backend) through every main screen as three roles,
 * performing the core workflow, and saves a screenshot of each step to
 * /sdcard/Download/devnex (pulled by CI). Failing steps are collected and reported together.
 */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @get:Rule(order = 0) val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val failures = mutableListOf<String>()
    private var shot = 0

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle(); Thread.sleep(700)
        shell("screencap -p /sdcard/Download/devnex/%02d_%s.png".format(++shot, name))
    }

    private fun step(name: String, block: () -> Unit) {
        try { block(); screenshot(name) } catch (t: Throwable) {
            failures += "$name: ${t.message?.lineSequence()?.firstOrNull()}"
            screenshot("FAILED_$name")
        }
    }

    private fun waitText(text: String, timeout: Long = 15_000, substring: Boolean = false) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun clickText(text: String, substring: Boolean = false) {
        waitText(text, substring = substring)
        rule.onAllNodesWithText(text, substring = substring).onFirst().performScrollTo().performClick()
    }

    private fun clickTextNoScroll(text: String, substring: Boolean = false) {
        waitText(text, substring = substring)
        rule.onAllNodesWithText(text, substring = substring).onFirst().performClick()
    }

    private fun tab(name: String) { rule.onNodeWithTag("tab_$name").performClick(); rule.waitForIdle() }

    /** Back out of secondary screens until the bottom tabs are visible again. */
    private fun ensureMain() {
        repeat(4) {
            rule.waitForIdle()
            if (rule.onAllNodesWithTag("tab_HOME").fetchSemanticsNodes().isNotEmpty()) return
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        }
    }

    private fun moreRow(title: String) {
        ensureMain()
        tab("MORE")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("row_$title"))
        rule.onNodeWithTag("row_$title").performClick()
        rule.waitForIdle()
    }

    /** Presses back only on secondary screens, so the app's root screen is never closed. */
    private fun back() {
        rule.waitForIdle()
        if (rule.onAllNodesWithTag("tab_HOME").fetchSemanticsNodes().isNotEmpty()) return
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Emulator system apps (e.g. the launcher) sometimes raise ANR dialogs that block touches. */
    private fun quietSystem() {
        shell("settings put global hide_error_dialogs 1")
        shell("am force-stop com.google.android.apps.nexuslauncher")
    }

    private fun mfaAndPickWorkspace() {
        waitText("Two-step verification")
        rule.onNode(hasSetTextAction()).performTextInput("123456")
        clickTextNoScroll("Velocity Sports Media")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("tab_HOME").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun signOut() {
        moreRow("Sign out")
        waitText("Welcome back")
    }

    @Test fun fullJourney() {
        quietSystem()
        shell("mkdir -p /sdcard/Download/devnex")
        Thread.sleep(1200); shell("screencap -p /sdcard/Download/devnex/00_splash.png")

        step("login") { waitText("Welcome back", 20_000) }

        // ---------------- Reporter: claim → start → submit
        step("reporter_home") {
            clickText("Kabir · Reporter")
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("tab_HOME").fetchSemanticsNodes().isNotEmpty() }
            waitText("Kabir")
        }
        step("my_work") { tab("WORK"); waitText("Search tasks") }
        step("open_pool") { clickTextNoScroll("Open pool", substring = true); waitText("District meet — live updates thread") }
        step("task_detail") { clickText("District meet — live updates thread"); waitText("Claim task") }
        step("claimed") { clickTextNoScroll("Claim task"); waitText("Start working") }
        step("started") { clickTextNoScroll("Start working"); waitText("Submit for review") }
        step("submitted") { clickTextNoScroll("Submit for review"); waitText("In review") }
        step("coverage") { back(); tab("DESK"); waitText("National Athletics Championship") }
        step("coverage_event") { clickText("National Athletics Championship"); waitText("P-SCORE") }
        step("messages") { back(); tab("CHAT"); waitText("New message") }
        step("channel") { clickText("#general"); waitText("Message #general") }
        step("send_message") {
            rule.onNode(hasSetTextAction()).performTextInput("Results sheet uploaded to the drive.")
            rule.onNodeWithContentDescription("Send").performClick()
            waitText("Results sheet uploaded to the drive.")
        }
        step("new_dm") { back(); clickTextNoScroll("New message"); waitText("Find a colleague") }
        step("dm_open") { clickText("Neha Iyer"); waitText("Neha Iyer") }
        step("profile") { back(); moreRow("My profile"); waitText("Save profile") }
        step("calendar") { back(); moreRow("Calendar"); waitText("Everything") }
        step("search") { back(); moreRow("Search"); rule.onNode(hasSetTextAction()).performTextInput("relay"); waitText("Tasks", substring = true) }
        step("ideas") { back(); moreRow("Ideas board"); waitText("Ideas board"); waitText("Weekly coach roundtable podcast") }
        step("ops_log") { back(); moreRow("Operations log"); waitText("Add to log") }
        step("publishing") { back(); moreRow("Publishing calendar"); waitText("Social media and website schedule") }
        step("more_member") { back(); tab("MORE"); waitText("My profile") }
        step("reporter_signout") { signOut() }

        // ---------------- Department head: approve the submission
        step("head_mfa") { clickText("Priya · Editorial head (approver)"); waitText("Two-step verification") }
        step("head_home") { mfaAndPickWorkspace(); waitText("Needs your action") }
        step("approvals") { moreRow("Approvals"); waitText("Waiting for your decision") }
        step("approve_stage1") {
            rule.onAllNodes(hasText("Approve") and hasClickAction()).onFirst().performClick()
            waitText("Approved", substring = true)
        }
        step("insights") { back(); moreRow("Insights"); waitText("WORK BY STAGE") }
        step("team") { back(); moreRow("Team & workload"); waitText("Capacity bar", substring = true) }
        step("head_signout") { signOut() }

        // ---------------- Owner: administration + platform console
        step("owner_home") { clickText("Devanshu · Platform owner + Company owner"); mfaAndPickWorkspace(); waitText("Devanshu") }
        step("members") { moreRow("Members"); waitText("Search people") }
        step("departments") { back(); moreRow("Departments"); waitText("Reporting") }
        step("company_settings") { back(); moreRow("Company settings"); waitText("Save settings") }
        step("audit_log") { back(); moreRow("Audit log"); waitText("Search by person, action or item") }
        step("invitations") { back(); moreRow("Invitations"); waitText("new.reporter@devnex.app") }
        step("invite_modules") { back(); moreRow("Invite people & modules"); waitText("Invite a member") }
        step("platform_console") { back(); moreRow("Platform console"); waitText("Velocity Sports Media") }
        step("notifications") { back(); moreRow("Notifications"); waitText("Notifications") }
        step("settings") { back(); moreRow("Settings"); waitText("Appearance") }
        step("dark_mode") { clickTextNoScroll("Dark"); back(); tab("HOME"); waitText("Needs your action") }
        step("dark_work") { tab("WORK"); waitText("Search tasks") }
        step("dark_task") { clickTextNoScroll("All", substring = true); clickText("Cover National Athletics Championship"); waitText("Deliverables", substring = true) }
        step("edit_task") { rule.onNodeWithContentDescription("Edit task").performClick(); waitText("Save changes") }
        step("light_again") { back(); back(); moreRow("Settings"); clickTextNoScroll("Light"); back(); tab("HOME") }

        if (failures.isNotEmpty()) fail("${failures.size} step(s) failed:\n" + failures.joinToString("\n"))
    }
}
