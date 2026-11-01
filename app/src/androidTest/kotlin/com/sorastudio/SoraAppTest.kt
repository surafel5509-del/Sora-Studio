package com.sorastudio

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests for the critical navigation workflows. Run on an emulator/device
 * via `./gradlew connectedDebugAndroidTest` (or a CI commit containing [ui-tests]).
 *
 * Navigation uses semantic clicks on the merged, clickable nodes instead of raw
 * touch injection, which is unreliable near the system gesture area.
 */
class SoraAppTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun clickNav(label: String) {
        // Role.Tab disambiguates bottom-nav items from same-labelled home cards.
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(hasText(label) and hasClickAction() and tabRole).performClick()
    }

    @Test
    fun homeShowsBrandAndQuickActions() {
        composeRule.onNodeWithText("SORA STUDIO").assertIsDisplayed()
        composeRule.onNodeWithText("New Project").assertIsDisplayed()
        composeRule.onNodeWithText("Record Vocal").assertIsDisplayed()
    }

    @Test
    fun canNavigateToStudioAndSeeTabs() {
        clickNav("Studio")
        composeRule.onNodeWithText("Arrangement").assertIsDisplayed()
        // Tab chips live in a horizontally scrollable row; scroll later tabs into view.
        composeRule.onNodeWithText("Mixer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Piano Roll").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun canNavigateToSoundsAndSettings() {
        clickNav("Sounds")
        composeRule.onNodeWithText("Drum Kits").assertIsDisplayed()
        clickNav("Settings")
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun newProjectDialogOpens() {
        composeRule.onNodeWithText("New Project").performClick()
        // Template names also exist on the Home screen behind the dialog, so
        // assert labels that are unique to the dialog itself.
        composeRule.onNodeWithText("Project name").assertIsDisplayed()
        composeRule.onNodeWithText("Create").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
