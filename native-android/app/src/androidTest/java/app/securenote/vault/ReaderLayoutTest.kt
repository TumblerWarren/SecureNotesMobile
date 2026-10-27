package app.securenote.vault

import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutTest {
    @Test
    fun epubImagesFitActualViewWidthAfterResize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val frame =
                EpubFrame(
                    "<p><img src=\"image:0\"/></p>",
                    mutableMapOf("image:0" to image),
                    0,
                    0,
                    1,
                    false,
                    false,
                )
            val view = EpubTextView(instrumentation.targetContext)
            try {
                view.layout(0, 0, 200, 400)
                view.display(frame, 18) {}
                val first =
                    (view.text as Spanned)
                        .getSpans(0, view.text.length, ImageSpan::class.java)
                        .single()
                assertTrue(first.drawable.bounds.width() <= 200)
                view.layout(0, 0, 120, 400)
                val resized =
                    (view.text as Spanned)
                        .getSpans(0, view.text.length, ImageSpan::class.java)
                        .single()
                assertTrue(resized.drawable.bounds.width() <= 120)
                assertTrue(resized.drawable.bounds.width() > 0)
            } finally {
                view.clearContent()
                image.recycle()
            }
        }
    }
}
