package app.securenote.vault

import android.content.Context
import android.graphics.*
import android.os.ParcelFileDescriptor
import android.view.*
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlinx.coroutines.*

/** Visible 512-pixel tiles only; encrypted ranges feed Android's native region decoder. */
class TiledImageView(
    context: Context,
    private val store: VaultStore,
    private val cache: Previews,
    private val id: String,
) : View(context), AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var descriptor: ParcelFileDescriptor? = null
    private var decoder: BitmapRegionDecoder? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private val orientation = Matrix()
    private var displayWidth = 0f
    private var displayHeight = 0f
    private var scale = 1f
    private var fit = 1f
    private var tx = 0f
    private var ty = 0f
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var failed = false
    private val version = store.version()
    private val fallbackKey = "image/$id"

    init {
        setBackgroundColor(android.graphics.Color.rgb(18, 22, 30))
        scope.launch {
            try {
                val (item, reader) = store.reader(id)
                descriptor = proxyDescriptor(context, reader, item.size)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor!!.fileDescriptor, null, bounds)
                require(
                    bounds.outWidth > 0 &&
                        bounds.outHeight > 0 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 200000000L
                ) {
                    "Image dimensions too large"
                }
                imageWidth = bounds.outWidth
                imageHeight = bounds.outHeight
                val exif = runCatching {
                    ExifInterface(descriptor!!.fileDescriptor)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }
                    .getOrDefault(1)
                when (exif) {
                    2 -> orientation.setScale(-1f, 1f)
                    3 -> orientation.setRotate(180f)
                    4 -> orientation.setScale(1f, -1f)
                    5 -> orientation.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                    6 -> orientation.setRotate(90f)
                    7 -> orientation.setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                    8 -> orientation.setRotate(270f)
                }
                val oriented = RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
                orientation.mapRect(oriented)
                orientation.postTranslate(-oriented.left, -oriented.top)
                displayWidth = oriented.width()
                displayHeight = oriented.height()
                decoder =
                    runCatching {
                        BitmapRegionDecoder.newInstance(descriptor!!.fileDescriptor, false)
                    }
                        .getOrNull()
                if (decoder == null) {
                    var sample = 1
                    while (imageWidth / sample > 2048 || imageHeight / sample > 2048) sample *= 2
                    val bitmap =
                        BitmapFactory.decodeFileDescriptor(
                            descriptor!!.fileDescriptor,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        ) ?: error("Unsupported image")
                    store.checkVersion(version)
                    if (closed.get()) bitmap.recycle()
                    else cache.putBitmap(fallbackKey, bitmap, version)
                }
                withContext(Dispatchers.Main) {
                    if (!closed.get()) {
                        fitImage()
                        invalidate()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    failed = true
                    invalidate()
                }
            }
        }
    }

    private fun fitImage() {
        if (width == 0 || height == 0 || imageWidth == 0) return
        fit = minOf(width.toFloat() / displayWidth, height.toFloat() / displayHeight)
        scale = fit
        clamp()
    }

    private fun clamp() {
        val w = displayWidth * scale
        val h = displayHeight * scale
        tx = if (w <= width) (width - w) / 2 else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2 else ty.coerceIn(height - h, 0f)
    }

    private val zoom =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val old = scale
                    scale = (scale * detector.scaleFactor).coerceIn(fit, fit * 32)
                    tx = detector.focusX - (detector.focusX - tx) * scale / old
                    ty = detector.focusY - (detector.focusY - ty) * scale / old
                    clamp()
                    invalidate()
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
                    first: MotionEvent?,
                    second: MotionEvent,
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
                    if (scale > fit * 1.5f) fitImage()
                    else {
                        val old = scale
                        scale = fit * 4
                        tx = e.x - (e.x - tx) * scale / old
                        ty = e.y - (e.y - ty) * scale / old
                        clamp()
                    }
                    invalidate()
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoom.onTouchEvent(event)
        if (!zoom.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fitImage()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed.get()) return
        if (failed) {
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 36f
            canvas.drawText("Image preview unavailable", 16f, 60f, paint)
            return
        }
        if (imageWidth == 0 || width == 0) return
        val matrix =
            Matrix(orientation).apply {
                postScale(scale, scale)
                postTranslate(tx, ty)
            }
        val inverse = Matrix()
        matrix.invert(inverse)
        val visible = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        canvas.save()
        canvas.concat(matrix)
        cache.getBitmap(fallbackKey)?.let {
            canvas.drawBitmap(
                it,
                null,
                RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat()),
                paint,
            )
            canvas.restore()
            return
        }
        val region =
            decoder
                ?: run {
                    canvas.restore()
                    return
                }
        var sample = 1
        while (sample * 2 <= 1f / scale && sample < 128) sample *= 2
        val step = 512 * sample
        val left = max(0, visible.left.toInt() / step * step)
        val top = max(0, visible.top.toInt() / step * step)
        val right = min(imageWidth, ceil(visible.right).toInt())
        val bottom = min(imageHeight, ceil(visible.bottom).toInt())
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val rect = Rect(x, y, min(imageWidth, x + step), min(imageHeight, y + step))
                val key = "tile/$id/$sample/$x/$y"
                val tile = cache.getBitmap(key)
                if (tile != null) canvas.drawBitmap(tile, null, RectF(rect), paint)
                else if (requested.add(key)) {
                    val sampling = sample
                    scope.launch {
                        try {
                            store.checkVersion(version)
                            val bitmap =
                                synchronized(region) {
                                    if (closed.get()) null
                                    else
                                        region.decodeRegion(
                                            rect,
                                            BitmapFactory.Options().apply {
                                                inSampleSize = sampling
                                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                            },
                                        )
                                }
                            if (bitmap != null) {
                                if (closed.get()) bitmap.recycle()
                                else {
                                    store.checkVersion(version)
                                    cache.putBitmap(key, bitmap, version)
                                    postInvalidateOnAnimation()
                                }
                            }
                        } catch (e: Exception) {} finally {
                            requested.remove(key)
                        }
                    }
                }
                x += step
            }
            y += step
        }
        canvas.restore()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            decoder?.let { synchronized(it) { it.recycle() } }
            decoder = null
            descriptor?.close()
            descriptor = null
        }
    }
}
