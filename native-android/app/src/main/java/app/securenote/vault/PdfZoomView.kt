package app.securenote.vault

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.*
import kotlin.math.*
import kotlinx.coroutines.*

/** Main-thread viewport and bounded tiles; the isolated service renders authenticated ranges. */
internal class PdfZoomView(
    context: Context,
    private val client: PdfClient,
    private val page: Int,
    private val preview: Bitmap,
    private val report: (Boolean) -> Unit,
) : View(context), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount

            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                old: Bitmap,
                newValue: Bitmap?,
            ) {
                if (old !== newValue) old.recycle()
            }
        }
    private val pending = mutableSetOf<String>()
    private val failures = mutableSetOf<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var closed = false
    private val aspect
        get() = preview.height.toFloat() / preview.width

    init {
        contentDescription = "Zoomed PDF page. Use Fit, Zoom in and Zoom out controls."
        setBackgroundColor(Color.DKGRAY)
    }

    private fun clamp() {
        val w = width * zoom
        val h = w * aspect
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
    }

    fun fit() {
        zoom = 1f
        tx = 0f
        ty = 0f
        clamp()
        invalidate()
    }

    fun zoomBy(factor: Float, x: Float = width / 2f, y: Float = height / 2f) {
        val old = zoom
        zoom = (zoom * factor).coerceIn(1f, 8f)
        tx = x - (x - tx) * zoom / old
        ty = y - (y - ty) * zoom / old
        clamp()
        invalidate()
    }

    fun retry() {
        failures.clear()
        report(false)
        invalidate()
    }

    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    zoomBy(d.scaleFactor, d.focusX, d.focusY)
                    return true
                }
            },
        )
    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onScroll(
                    a: MotionEvent?,
                    b: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    tx -= dx
                    ty -= dy
                    clamp()
                    invalidate()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (zoom > 2) fit() else zoomBy(2f, e.x, e.y)
                    return true
                }
            },
        )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (!scale.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit()
    }

    override fun onDraw(canvas: Canvas) {
        if (closed || width == 0) return
        val displayWidth = width * zoom
        canvas.drawBitmap(
            preview,
            null,
            RectF(tx, ty, tx + displayWidth, ty + displayWidth * aspect),
            paint,
        )
        val renderWidth = displayWidth.roundToInt().coerceIn(128, 65536)
        val ratio = displayWidth / renderWidth
        val left = max(0f, -tx / ratio).toInt() / 512
        val right = min(renderWidth.toFloat(), (width - tx) / ratio).toInt() / 512
        val top = max(0f, -ty / ratio).toInt() / 512
        val bottom = min(renderWidth * aspect, (height - ty) / ratio).toInt() / 512
        canvas.save()
        canvas.clipRect(tx, ty, tx + displayWidth, ty + displayWidth * aspect)
        for (y in top..bottom) for (x in left..right) {
            val px = x * 512
            val py = y * 512
            if (px >= renderWidth || py >= renderWidth * aspect) continue
            val key = "$renderWidth/$x/$y"
            val bitmap = cache.get(key)
            if (bitmap != null)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        tx + px * ratio,
                        ty + py * ratio,
                        tx + (px + 512) * ratio,
                        ty + (py + 512) * ratio,
                    ),
                    paint,
                )
            else if (pending.size < 4 && key !in failures && pending.add(key))
                scope.launch {
                    try {
                        val result = client.render(page, renderWidth, px, py)
                        if (closed) result.recycle() else cache.put(key, result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!closed) {
                            failures.add(key)
                            report(true)
                        }
                    } finally {
                        pending.remove(key)
                        if (!closed) invalidate()
                    }
                }
        }
        canvas.restore()
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        cache.evictAll()
        pending.clear()
        failures.clear()
    }
}
