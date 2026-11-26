package app.tingyu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.tingyu.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandResourcesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun launcherNameAndColorsMatchTheInAppBrand() {
        val info = app.applicationInfo
        assertEquals(app.getString(R.string.app_name), info.loadLabel(app.packageManager).toString())
        assertEquals(TingYuColors.Coral.toArgb(), app.getColor(R.color.brand_background))
        assertEquals(TingYuColors.Background.toArgb(), app.getColor(R.color.brand_canvas))

        for (id in listOf(info.icon, R.mipmap.ic_launcher_round)) {
            val launcher = app.getDrawable(id) as AdaptiveIconDrawable
            val background = render(launcher.background)
            assertEquals(app.getColor(R.color.brand_background), background.getPixel(32, 32))
            val glyph = render(launcher.foreground)
            val color = app.getColor(R.color.brand_mark)
            assertTrue("Launcher foreground should be visible", (0 until 64).sumOf { y ->
                (0 until 64).count { x -> glyph.getPixel(x, y) == color }
            } > 250)
            // Keep the brand mark inside the 66/108 adaptive safe circle for every launcher mask.
            for (y in 0 until 64) for (x in 0 until 64) {
                if (Color.alpha(glyph.getPixel(x, y)) > 128) {
                    val dx = x + 0.5 - 32
                    val dy = y + 0.5 - 32
                    assertTrue("Launcher glyph clipped at ($x,$y)", dx * dx + dy * dy < 20 * 20)
                }
            }
        }
    }

    @Test fun notificationSilhouetteRetainsTheNotesAndSmile() {
        val mark = render(requireNotNull(app.getDrawable(R.drawable.ic_brand_mark)))
        val highlight = app.getColor(R.color.brand_mark)
        assertEquals(highlight, mark.getPixel(18, 38))
        assertEquals(highlight, mark.getPixel(44, 32))
        assertEquals(highlight, mark.getPixel(32, 53))
        assertEquals(0, Color.alpha(mark.getPixel(32, 44)))
        assertEquals(0, Color.alpha(mark.getPixel(2, 2)))
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
}
