package com.sorastudio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests for the critical navigation workflows. Run on an emulator/device
 * via `./gradlew connectedDebugAndroidTest` (see the manual CI job).
 */
class SoraAppTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsBrandAndQuickActions() {
        composeRule.onNodeWithText("SORA STUDIO").assertIsDisplayed()
        composeRule.onNodeWithText("New Project").assertIsDisplayed()
        composeRule.onNodeWithText("Record Vocal").assertIsDisplayed()
    }

    @Test
    fun canNavigateToStudioAndSeeTabs() {
        composeRule.onNodeWithText("Studio").performClick()
        composeRule.onNodeWithText("Arrangement").assertIsDisplayed()
        composeRule.onNodeWithText("Piano Roll").assertIsDisplayed()
        composeRule.onNodeWithText("Mixer").assertIsDisplayed()
    }

    @Test
    fun canNavigateToSoundsAndSettings() {
        composeRule.onNodeWithText("Sounds").performClick()
        composeRule.onNodeWithText("Drum Kits").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun newProjectDialogOpens() {
        composeRule.onNodeWithText("New Project").performClick()
        composeRule.onNodeWithText("Beat Starter").assertIsDisplayed()
        composeRule.onNodeWithText("Create").assertIsDisplayed()
    }
}
