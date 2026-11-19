package dev.cpa.android.feature.remote

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.RemoteSentImage
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class RemoteImagePreviewTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun image(color: Int): RemoteSentImage {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return RemoteSentImage(Base64.getEncoder().encodeToString(bytes.toByteArray()), "image/png")
    }

    @Test fun previewControlsStayReachableAndInvalidImageCanBeLeft() {
        compose.setContent { CpaTheme { RemoteSentImages(listOf(image(Color.RED), RemoteSentImage("invalid", "image/png"), image(Color.BLUE))) } }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Sent image 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Sent image 1").performClick()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom in").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Reset").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Next image").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Previous image").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Next image").performClick()
        compose.onNodeWithContentDescription("Next image").performClick()
        compose.onNodeWithContentDescription("Next image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close preview").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close preview").assertDoesNotExist()
    }
}
