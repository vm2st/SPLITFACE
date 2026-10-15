package com.vm2st.splitface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Actual native raster rendering, not a hardware/emulator test or a golden-image test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RenderingTest {
    @Test fun rendersBothOrientationsAndPauseWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root: View = activity.findViewById(R.id.root)
            val game: GameView = activity.findViewById(R.id.game_view)
            game.setHostActive(false)
            for ((w, h) in listOf(960 to 540, 540 to 960)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                assertNotEquals(Color.TRANSPARENT, bitmap.getPixel(w / 4, h * 3 / 4))
                assertNotEquals(bitmap.getPixel(w / 4, h * 3 / 4), bitmap.getPixel(w * 3 / 4, h * 3 / 4))
                val output = File("build/audit-ui-${w}x${h}.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                game.togglePause()
                root.draw(Canvas(bitmap))
                game.togglePause()
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }
}
