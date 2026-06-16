package app.passwordmanager.translator

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import app.passwordmanager.ui.theme.PasswordManagerTheme
import org.junit.Rule
import org.junit.Test

class TranslatorUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun translatorScreen_rendersCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val viewModel = TranslatorViewModel(context.applicationContext as android.app.Application)

        composeTestRule.setContent {
            PasswordManagerTheme {
                TranslatorScreen(viewModel = viewModel)
            }
        }

        // Verify the screen title and main buttons are displayed
        composeTestRule.onNodeWithText("Smart Translator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Image").assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF").assertIsDisplayed()
    }
}
