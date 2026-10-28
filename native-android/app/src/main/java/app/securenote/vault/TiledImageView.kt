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
    private val failedTiles = ConcurrentHashMap.newKeySet<String>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val visibleRect = RectF()
    private val destinationRect = RectF()
    @Volatile private var viewportRevision = 0L
    @Volatile private var fallback: Bitmap? = null
    var onZoom: ((Int) -> Unit)? = null
    val zoomPercent
        get() = ((scale / fit.coerceAtLeast(.00001f)) * 100).roundToInt()

    fun rotateClockwise() {
        if (!ready) return
        orientation.postRotate(90f)
        val bounds = RectF(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        orientation.mapRect(bounds)
        orientation.postTranslate(-bounds.left, -bounds.top)
        displayWidth = bounds.width()
        displayHeight = bounds.height()
        fitImage()
        viewportRevision++
        invalidate()
    }

    var onState: ((String) -> Unit)? = null
    val state
        get() = if (failed) "error" else if (ready) "ready" else "loading"

    fun fitToScreen() {
        fitImage()
        invalidate()
    }

    fun changeZoom(factor: Float) {
        val old = scale
        scale = (scale * factor).coerceIn(fit, fit * 32)
        tx = width / 2f - (width / 2f - tx) * scale / old
        ty = height / 2f - (height / 2f - ty) * scale / old
        clamp()
        viewportRevision++
        invalidate()
    }

    private var ready = false
    private var failed = false
    private val version = store.version()
    private val fallbackKey = "image/$id"

    init {
        contentDescription = "Encrypted image. Use Fit and Zoom controls."
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
                    else {
                        try {
                            cache.putBitmap(fallbackKey, bitmap, version)
                            fallback = bitmap
                        } catch (e: Exception) {
                            bitmap.recycle()
                            throw e
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!closed.get()) {
                        ready = true
                        onState?.invoke("ready")
                        fitImage()
                        invalidate()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    failed = true
                    onState?.invoke("error")
                    invalidate()
                }
            } finally {
                if (closed.get() || failed) releaseResources()
            }
        }
    }

    private fun fitImage() {
        if (!ready || width == 0 || height == 0 || imageWidth == 0) return
        fit = minOf(width.toFloat() / displayWidth, height.toFloat() / displayHeight)
        scale = fit
        clamp()
    }

    private fun clamp() {
        onZoom?.invoke(zoomPercent)
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
                    viewportRevision++
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
                    viewportRevision++
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
                    viewportRevision++
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
        viewportRevision++
        fitImage()
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            drawViewport(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun drawViewport(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed.get()) return
        if (failed) {
            paint.color = android.graphics.Color.WHITE
            paint.textSize =
                min(
                    18f * resources.displayMetrics.scaledDensity,
                    (width - 32f).coerceAtLeast(1f) / 14f,
                )
            canvas.drawText("Image preview unavailable", 16f, 60f, paint)
            return
        }
        if (!ready || imageWidth == 0 || width == 0) return
        val matrix = drawMatrix
        matrix.set(orientation)
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)
        matrix.invert(inverseMatrix)
        val visible = visibleRect
        visible.set(0f, 0f, width.toFloat(), height.toFloat())
        inverseMatrix.mapRect(visible)
        canvas.save()
        canvas.concat(matrix)
        (fallback ?: cache.getBitmap(fallbackKey))?.let {
            destinationRect.set(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
            canvas.drawBitmap(it, null, destinationRect, paint)
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
        while (sample * 2 <= 1f / scale && sample < (1 shl 19)) sample *= 2
        // Keep visible tiles below the cache budget even on large/high-density windows.
        val visibleWidth =
            (min(imageWidth.toFloat(), visible.right) - max(0f, visible.left)).coerceAtLeast(0f)
        val visibleHeight =
            (min(imageHeight.toFloat(), visible.bottom) - max(0f, visible.top)).coerceAtLeast(0f)
        while (
            sample < (1 shl 19) &&
                (ceil(visibleWidth / (512 * sample)) + 1) *
                    (ceil(visibleHeight / (512 * sample)) + 1) > 24
        ) sample *= 2
        val step = 512 * sample
        val left = max(0, visible.left.toInt() / step * step)
        val top = max(0, visible.top.toInt() / step * step)
        val right = min(imageWidth, ceil(visible.right).toInt())
        val bottom = min(imageHeight, ceil(visible.bottom).toInt())
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val key = "tile/$id/$sample/$x/$y"
                val tile = cache.getBitmap(key)
                if (tile != null) {
                    destinationRect.set(
                        x.toFloat(),
                        y.toFloat(),
                        min(imageWidth, x + step).toFloat(),
                        min(imageHeight, y + step).toFloat(),
                    )
                    canvas.drawBitmap(tile, null, destinationRect, paint)
                } else if (requested.size < 8 && key !in failedTiles && requested.add(key)) {
                    val rect = Rect(x, y, min(imageWidth, x + step), min(imageHeight, y + step))
                    val sampling = sample
                    val revision = viewportRevision
                    scope.launch {
                        try {
                            store.checkVersion(version)
                            if (revision != viewportRevision) return@launch
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
                            if (bitmap == null) failedTiles.add(key)
                            if (bitmap != null) {
                                if (closed.get()) bitmap.recycle()
                                else {
                                    try {
                                        cache.putBitmap(key, bitmap, version)
                                        postInvalidateOnAnimation()
                                    } catch (e: Exception) {
                                        bitmap.recycle()
                                        throw e
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedTiles.add(key)
                        } finally {
                            requested.remove(key)
                            if (!closed.get()) postInvalidateOnAnimation()
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
        fallback = null
        CoroutineScope(Dispatchers.IO).launch { releaseResources() }
    }

    @Synchronized
    private fun releaseResources() {
        decoder?.let { synchronized(it) { it.recycle() } }
        decoder = null
        descriptor?.close()
        descriptor = null
    }
}
