package com.example.anndoodle

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CanvasRatio(val key: String, val title: String, val ratioW: Float, val ratioH: Float) {
    SQUARE("1_1", "1:1 (จัตุรัส)", 1f, 1f),
    STANDARD("4_3", "4:3 (แนวกว้าง)", 4f, 3f),
    WIDE("16_9", "16:9 (แถบกว้าง)", 16f, 9f),
    BANNER("2_1", "2:1 (แถบยาว)", 2f, 1f),
    PORTRAIT_5_4("4_5", "5:4 (แนวตั้ง)", 4f, 5f),
    FULLSCREEN("full", "เต็มจอ", 0f, 0f),
    CUSTOM("custom", "กำหนดเอง (Custom)", 0f, 0f);

    companion object {
        fun fromKey(key: String?): CanvasRatio {
            return entries.firstOrNull { it.key == key } ?: SQUARE
        }
    }
}

class DoodleView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private enum class ResizeCorner {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private data class HistoryStep(
        val bitmap: Bitmap,
        val rect: RectF,
        val ratio: CanvasRatio
    )

    var canvasRatio: CanvasRatio = CanvasRatio.SQUARE
        private set

    var isTransparentBg: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 16f
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val bitmapPaint = Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val darkCanvasBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        style = Paint.Style.FILL
    }

    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        pathEffect = DashPathEffect(
            floatArrayOf(8f * resources.displayMetrics.density, 6f * resources.displayMetrics.density),
            0f
        )
    }

    private val frameCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val activeCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val cornerKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D9181A20")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    var penColor: Int = Color.WHITE
        set(value) {
            field = value
            strokePaint.color = value
        }

    var brushSize: Float = 16f
    var isEraser: Boolean = false
    var isPressureEnabled: Boolean = true

    var onZoomChanged: ((Int) -> Unit)? = null
    var onUndoTriggered: (() -> Unit)? = null
    var onRedoTriggered: (() -> Unit)? = null
    var onUndoRedoStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onCanvasRectChanged: ((rect: RectF, ratio: CanvasRatio) -> Unit)? = null
    var onViewTransformChanged: ((scale: Float, panX: Float, panY: Float) -> Unit)? = null

    val canvasRect = RectF()
    private val pendingCustomRect = RectF()
    private var hasPendingCustomRect = false
    private var pendingWidgetAspectRatio: Float? = null

    private val clipPath = Path()
    private val transformedCanvasRect = RectF()

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val viewInverseMatrix = Matrix()
    private val totalMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var currentScale = 1f

    // Corner resize state
    private var activeResizeCorner = ResizeCorner.NONE
    private var isResizingCorner = false
    private val resizeStartCanvasRect = RectF()
    private val resizeStartBaseMatrix = Matrix()
    private var cornerDragOffsetX = 0f
    private var cornerDragOffsetY = 0f

    private val currentPath = Path()
    private var lastDrawX = 0f
    private var lastDrawY = 0f
    private var isDrawing = false

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    // Undo / Redo history & pooling
    private val MAX_HISTORY = 16
    private val undoStack = ArrayDeque<HistoryStep>()
    private val redoStack = ArrayDeque<HistoryStep>()
    private val bitmapPool = ArrayDeque<Bitmap>()
    private var pendingBeforeStrokeStep: HistoryStep? = null

    // Multi-touch & tap gesture tracking
    private var gestureStartTime = 0L
    private var maxPointersInGesture = 0
    private var hasMovedExceededSlop = false
    private val startPointerPositions = HashMap<Int, PointF>()
    private val TAP_TIMEOUT_MS = 350L
    private val TAP_MAX_DISTANCE_DP = 22f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = (currentScale * scaleFactor).coerceIn(0.4f, 5.0f)
            val actualFactor = newScale / currentScale
            if (abs(actualFactor - 1f) > 0.0001f) {
                currentScale = newScale
                viewMatrix.postScale(actualFactor, actualFactor, detector.focusX, detector.focusY)
                updateMatrices()
                onZoomChanged?.invoke((currentScale * 100).roundToInt())
                notifyViewTransform()
                invalidate()
            }
            return true
        }
    })

    private fun computeTargetBitmapSize(rectW: Float, rectH: Float): Pair<Int, Int> {
        val safeW = rectW.coerceAtLeast(100f)
        val safeH = rectH.coerceAtLeast(100f)
        var w = safeW * 1.6f
        var h = safeH * 1.6f
        val maxSide = maxOf(w, h)
        if (maxSide > 2200f) {
            val scale = 2200f / maxSide
            w *= scale
            h *= scale
        } else if (maxSide < 600f) {
            val scale = 600f / maxSide
            w *= scale
            h *= scale
        }
        return Pair(w.roundToInt().coerceAtLeast(100), h.roundToInt().coerceAtLeast(100))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            updateCanvasGeometry(w, h)
            if (bitmap == null) {
                loadExistingOrCreateNew()
            } else if (oldw != w || oldh != h) {
                val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
                adaptBitmapToDimensions(targetW, targetH, preserveCenterFit = true)
            }
            updateMatrices()
            invalidate()
        }
    }

    /**
     * Aligns the canvas frame to the exact on-screen widget rectangle from Launcher sourceBounds,
     * clamped safely within DoodleView while strictly preserving the widget's aspect ratio.
     */
    fun applyWidgetSourceBounds(boundsInView: RectF) {
        if (boundsInView.width() <= 20f || boundsInView.height() <= 20f) return
        canvasRatio = CanvasRatio.CUSTOM
        if (width > 0 && height > 0) {
            val clamped = clampCustomRectPreservingRatio(boundsInView, width.toFloat(), height.toFloat())
            canvasRect.set(clamped)
            if (bitmap == null) {
                loadExistingOrCreateNew()
            } else {
                val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
                adaptBitmapToDimensions(targetW, targetH, preserveCenterFit = true)
            }
            resetZoom()
            onCanvasRectChanged?.invoke(RectF(canvasRect), canvasRatio)
            invalidate()
        } else {
            pendingCustomRect.set(boundsInView)
            hasPendingCustomRect = true
        }
    }

    /**
     * Sets a custom canvas rectangle (e.g. restored from SharedPreferences).
     */
    fun setCustomCanvasRect(rect: RectF, ratio: CanvasRatio = CanvasRatio.CUSTOM) {
        if (rect.width() <= 20f || rect.height() <= 20f) return
        canvasRatio = ratio
        if (width > 0 && height > 0) {
            val clamped = clampRectToView(rect, width.toFloat(), height.toFloat())
            canvasRect.set(clamped)
            if (bitmap == null) {
                loadExistingOrCreateNew()
            } else {
                val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
                adaptBitmapToDimensions(targetW, targetH, preserveCenterFit = true)
            }
            updateMatrices()
            invalidate()
        } else {
            pendingCustomRect.set(rect)
            hasPendingCustomRect = true
        }
    }

    /**
     * Aligns the canvas frame to a specific widget aspect ratio (width / height) when sourceBounds is unavailable.
     */
    fun setWidgetAspectRatio(aspectRatio: Float) {
        if (aspectRatio <= 0.1f || aspectRatio > 10f) return
        canvasRatio = CanvasRatio.CUSTOM
        pendingWidgetAspectRatio = aspectRatio
        hasPendingCustomRect = false
        if (width > 0 && height > 0) {
            updateCanvasGeometry(width, height)
            if (bitmap == null) {
                loadExistingOrCreateNew()
            } else {
                val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
                adaptBitmapToDimensions(targetW, targetH, preserveCenterFit = true)
            }
            resetZoom()
            onCanvasRectChanged?.invoke(RectF(canvasRect), canvasRatio)
            invalidate()
        }
    }

    fun setCanvasRatio(ratio: CanvasRatio, recordUndo: Boolean = true) {
        if (canvasRatio == ratio && ratio != CanvasRatio.CUSTOM && !hasPendingCustomRect && pendingWidgetAspectRatio == null) {
            return
        }

        if (recordUndo && bitmap != null && width > 0 && height > 0) {
            captureCurrentToUndo()
        }

        canvasRatio = ratio
        hasPendingCustomRect = false
        pendingWidgetAspectRatio = null

        if (width > 0 && height > 0) {
            updateCanvasGeometry(width, height)
            val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
            adaptBitmapToDimensions(targetW, targetH, preserveCenterFit = true)
            resetZoom()
            onCanvasRectChanged?.invoke(RectF(canvasRect), canvasRatio)
            invalidate()
            notifyUndoRedoState()
        }
    }

    fun restoreViewTransform(scale: Float, panX: Float, panY: Float) {
        currentScale = scale.coerceIn(0.4f, 5.0f)
        viewMatrix.reset()
        viewMatrix.postScale(currentScale, currentScale, width / 2f, height / 2f)
        viewMatrix.postTranslate(panX, panY)
        updateMatrices()
        onZoomChanged?.invoke((currentScale * 100).roundToInt())
        invalidate()
    }

    private fun notifyViewTransform() {
        val values = FloatArray(9)
        viewMatrix.getValues(values)
        onViewTransformChanged?.invoke(currentScale, values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
    }

    private fun clampCustomRectPreservingRatio(src: RectF, viewW: Float, viewH: Float): RectF {
        val density = resources.displayMetrics.density
        val minMargin = 10f * density
        val topSafe = 70f * density
        val maxAvailW = (viewW - minMargin * 2f).coerceAtLeast(120f)
        val maxAvailH = (viewH - topSafe - minMargin).coerceAtLeast(120f)

        var w = src.width()
        var h = src.height()
        val ratio = w / h

        if (w > maxAvailW) {
            w = maxAvailW
            h = w / ratio
        }
        if (h > maxAvailH) {
            h = maxAvailH
            w = h * ratio
        }

        var left = src.left.coerceIn(minMargin, (viewW - minMargin - w).coerceAtLeast(minMargin))
        var top = src.top.coerceIn(topSafe, (viewH - minMargin - h).coerceAtLeast(topSafe))
        return RectF(left, top, left + w, top + h)
    }

    private fun clampRectToView(src: RectF, viewW: Float, viewH: Float): RectF {
        val density = resources.displayMetrics.density
        val minMargin = 8f * density
        val minSize = 90f * density
        val left = src.left.coerceIn(minMargin, (viewW - minSize - minMargin).coerceAtLeast(minMargin))
        val top = src.top.coerceIn(minMargin, (viewH - minSize - minMargin).coerceAtLeast(minMargin))
        val right = src.right.coerceIn(left + minSize, (viewW - minMargin).coerceAtLeast(left + minSize))
        val bottom = src.bottom.coerceIn(top + minSize, (viewH - minMargin).coerceAtLeast(top + minSize))
        return RectF(left, top, right, bottom)
    }

    private fun updateCanvasGeometry(w: Int, h: Int) {
        val density = resources.displayMetrics.density
        if (hasPendingCustomRect) {
            val clamped = clampCustomRectPreservingRatio(pendingCustomRect, w.toFloat(), h.toFloat())
            canvasRect.set(clamped)
            hasPendingCustomRect = false
            updateMatrices()
            return
        }

        if (canvasRatio == CanvasRatio.CUSTOM && canvasRect.width() > 20f && canvasRect.height() > 20f && pendingWidgetAspectRatio == null) {
            val clamped = clampRectToView(canvasRect, w.toFloat(), h.toFloat())
            canvasRect.set(clamped)
            updateMatrices()
            return
        }

        val topPadding = 75f * density
        val bottomPadding = 120f * density
        val sidePadding = 16f * density

        if (canvasRatio == CanvasRatio.FULLSCREEN) {
            val inset = 10f * density
            canvasRect.set(inset, topPadding, w.toFloat() - inset, (h.toFloat() - inset).coerceAtLeast(topPadding + 200f))
        } else {
            val availW = (w - sidePadding * 2f).coerceAtLeast(100f)
            val availH = (h - topPadding - bottomPadding).coerceAtLeast(100f)
            val ratio = pendingWidgetAspectRatio ?: (canvasRatio.ratioW / canvasRatio.ratioH)

            val targetW: Float
            val targetH: Float
            if (availW / availH > ratio) {
                targetH = availH
                targetW = targetH * ratio
            } else {
                targetW = availW
                targetH = targetW / ratio
            }

            val left = (w - targetW) / 2f
            val top = topPadding + (availH - targetH) / 2f
            canvasRect.set(left, top, left + targetW, top + targetH)
        }

        updateMatrices()
    }

    private fun updateMatrices() {
        val bmp = bitmap ?: return
        baseMatrix.reset()
        val scaleX = canvasRect.width() / bmp.width.toFloat()
        val scaleY = canvasRect.height() / bmp.height.toFloat()
        baseMatrix.postScale(scaleX, scaleY)
        baseMatrix.postTranslate(canvasRect.left, canvasRect.top)

        viewMatrix.invert(viewInverseMatrix)

        totalMatrix.set(baseMatrix)
        totalMatrix.postConcat(viewMatrix)
        totalMatrix.invert(inverseMatrix)
    }

    private fun loadExistingOrCreateNew() {
        val (targetW, targetH) = computeTargetBitmapSize(canvasRect.width(), canvasRect.height())
        val file = File(context.filesDir, "doodle.png")

        if (file.exists()) {
            val loaded = BitmapFactory.decodeFile(file.absolutePath)
            if (loaded != null) {
                val newBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val c = Canvas(newBmp)
                val scale = minOf(targetW.toFloat() / loaded.width, targetH.toFloat() / loaded.height)
                val sw = loaded.width * scale
                val sh = loaded.height * scale
                val dx = (targetW - sw) / 2f
                val dy = (targetH - sh) / 2f
                val destRect = RectF(dx, dy, dx + sw, dy + sh)
                c.drawBitmap(loaded, null, destRect, bitmapPaint)
                loaded.recycle()
                bitmap = newBmp
            } else {
                bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            }
        } else {
            bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        }
        bitmapCanvas = Canvas(bitmap!!)
        updateMatrices()
        clearUndoHistory()
    }

    private fun adaptBitmapToDimensions(targetW: Int, targetH: Int, preserveCenterFit: Boolean = true) {
        val oldBmp = bitmap ?: return
        if (oldBmp.width == targetW && oldBmp.height == targetH) {
            updateMatrices()
            return
        }

        val newBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(newBmp)
        if (preserveCenterFit) {
            val scale = minOf(targetW.toFloat() / oldBmp.width, targetH.toFloat() / oldBmp.height)
            val sw = oldBmp.width * scale
            val sh = oldBmp.height * scale
            val dx = (targetW - sw) / 2f
            val dy = (targetH - sh) / 2f
            val destRect = RectF(dx, dy, dx + sw, dy + sh)
            c.drawBitmap(oldBmp, null, destRect, bitmapPaint)
        } else {
            c.drawBitmap(oldBmp, 0f, 0f, bitmapPaint)
        }

        releaseBitmap(oldBmp)
        bitmap = newBmp
        bitmapCanvas = Canvas(bitmap!!)
        updateMatrices()
    }

    /**
     * Reallocates the bitmap after a corner-drag resize so that the existing drawing stays
     * at the exact same screen coordinates and scale without stretching or shifting.
     */
    private fun commitCornerResizeBitmap(oldRect: RectF, newRect: RectF) {
        val oldBmp = bitmap ?: return
        val (newW, newH) = computeTargetBitmapSize(newRect.width(), newRect.height())
        val newBmp = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val c = Canvas(newBmp)

        val scaleToNewX = newW.toFloat() / newRect.width().coerceAtLeast(1f)
        val scaleToNewY = newH.toFloat() / newRect.height().coerceAtLeast(1f)
        val dstLeft = (oldRect.left - newRect.left) * scaleToNewX
        val dstTop = (oldRect.top - newRect.top) * scaleToNewY
        val dstRight = dstLeft + oldRect.width() * scaleToNewX
        val dstBottom = dstTop + oldRect.height() * scaleToNewY
        val dstRect = RectF(dstLeft, dstTop, dstRight, dstBottom)

        c.drawBitmap(oldBmp, null, dstRect, bitmapPaint)
        releaseBitmap(oldBmp)
        bitmap = newBmp
        bitmapCanvas = Canvas(bitmap!!)
        updateMatrices()
    }

    // --- Undo / Redo Pool & Stack ---

    private fun obtainPooledBitmap(w: Int, h: Int): Bitmap {
        val iterator = bitmapPool.iterator()
        while (iterator.hasNext()) {
            val pooled = iterator.next()
            if (pooled.isRecycled) {
                iterator.remove()
            } else if (pooled.width == w && pooled.height == h) {
                iterator.remove()
                pooled.eraseColor(Color.TRANSPARENT)
                return pooled
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun releaseBitmap(b: Bitmap) {
        if (b.isRecycled) return
        if (bitmapPool.size < MAX_HISTORY) {
            bitmapPool.addLast(b)
        } else {
            b.recycle()
        }
    }

    private fun copyToPooled(src: Bitmap): Bitmap {
        val copy = obtainPooledBitmap(src.width, src.height)
        val c = Canvas(copy)
        c.drawBitmap(src, 0f, 0f, null)
        return copy
    }

    private fun createCurrentStep(): HistoryStep? {
        val current = bitmap ?: return null
        return HistoryStep(
            bitmap = copyToPooled(current),
            rect = RectF(canvasRect),
            ratio = canvasRatio
        )
    }

    private fun releaseStep(step: HistoryStep) {
        releaseBitmap(step.bitmap)
    }

    private fun captureCurrentToUndo() {
        val step = createCurrentStep() ?: return
        if (undoStack.size >= MAX_HISTORY) {
            releaseStep(undoStack.removeFirst())
        }
        undoStack.addLast(step)
        clearRedoStack()
        notifyUndoRedoState()
    }

    private fun clearRedoStack() {
        while (redoStack.isNotEmpty()) {
            releaseStep(redoStack.removeLast())
        }
    }

    private fun clearUndoHistory() {
        while (undoStack.isNotEmpty()) {
            releaseStep(undoStack.removeLast())
        }
        clearRedoStack()
        notifyUndoRedoState()
    }

    private fun applyHistoryStep(step: HistoryStep) {
        canvasRect.set(step.rect)
        canvasRatio = step.ratio
        val current = bitmap
        if (current == null || current.width != step.bitmap.width || current.height != step.bitmap.height) {
            current?.let { releaseBitmap(it) }
            val newBmp = obtainPooledBitmap(step.bitmap.width, step.bitmap.height)
            val c = Canvas(newBmp)
            c.drawBitmap(step.bitmap, 0f, 0f, null)
            bitmap = newBmp
            bitmapCanvas = c
        } else {
            current.eraseColor(Color.TRANSPARENT)
            bitmapCanvas?.drawBitmap(step.bitmap, 0f, 0f, null)
        }
        updateMatrices()
        onCanvasRectChanged?.invoke(RectF(canvasRect), canvasRatio)
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val currentStep = createCurrentStep() ?: return false
        if (redoStack.size >= MAX_HISTORY) {
            releaseStep(redoStack.removeFirst())
        }
        redoStack.addLast(currentStep)

        val prevStep = undoStack.removeLast()
        applyHistoryStep(prevStep)
        releaseStep(prevStep)

        invalidate()
        notifyUndoRedoState()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val currentStep = createCurrentStep() ?: return false
        if (undoStack.size >= MAX_HISTORY) {
            releaseStep(undoStack.removeFirst())
        }
        undoStack.addLast(currentStep)

        val nextStep = redoStack.removeLast()
        applyHistoryStep(nextStep)
        releaseStep(nextStep)

        invalidate()
        notifyUndoRedoState()
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun notifyUndoRedoState() {
        onUndoRedoStateChanged?.invoke(canUndo(), canRedo())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val cornerRadius = 18f * density

        // Draw dim mask outside the transformed canvas
        canvas.save()
        canvas.concat(viewMatrix)

        clipPath.reset()
        clipPath.addRoundRect(canvasRect, cornerRadius, cornerRadius, Path.Direction.CW)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(clipPath)
        }
        canvas.drawColor(Color.argb(95, 0, 0, 0))
        canvas.restore()

        // Draw bitmap and frame inside transformed canvas
        canvas.save()
        canvas.concat(viewMatrix)

        val count = canvas.save()
        canvas.clipPath(clipPath)
        if (!isTransparentBg) {
            canvas.drawRoundRect(canvasRect, cornerRadius, cornerRadius, darkCanvasBgPaint)
        }
        bitmap?.let {
            val drawMatrix = if (isResizingCorner) resizeStartBaseMatrix else baseMatrix
            canvas.drawBitmap(it, drawMatrix, bitmapPaint)
        }
        canvas.restoreToCount(count)

        // Draw widget boundary outline
        canvas.drawRoundRect(canvasRect, cornerRadius, cornerRadius, frameBorderPaint)

        // Draw corner resize handles
        val cLen = 16f * density
        val knobRadius = 12f * density

        // Subtle touch target circles at corners
        canvas.drawCircle(canvasRect.left + cornerRadius * 0.35f, canvasRect.top + cornerRadius * 0.35f, knobRadius, cornerKnobPaint)
        canvas.drawCircle(canvasRect.right - cornerRadius * 0.35f, canvasRect.top + cornerRadius * 0.35f, knobRadius, cornerKnobPaint)
        canvas.drawCircle(canvasRect.left + cornerRadius * 0.35f, canvasRect.bottom - cornerRadius * 0.35f, knobRadius, cornerKnobPaint)
        canvas.drawCircle(canvasRect.right - cornerRadius * 0.35f, canvasRect.bottom - cornerRadius * 0.35f, knobRadius, cornerKnobPaint)

        // Top-Left
        val tlPaint = if (activeResizeCorner == ResizeCorner.TOP_LEFT) activeCornerPaint else frameCornerPaint
        canvas.drawLine(canvasRect.left, canvasRect.top + cornerRadius, canvasRect.left, canvasRect.top + cornerRadius + cLen, tlPaint)
        canvas.drawLine(canvasRect.left + cornerRadius, canvasRect.top, canvasRect.left + cornerRadius + cLen, canvasRect.top, tlPaint)
        // Top-Right
        val trPaint = if (activeResizeCorner == ResizeCorner.TOP_RIGHT) activeCornerPaint else frameCornerPaint
        canvas.drawLine(canvasRect.right, canvasRect.top + cornerRadius, canvasRect.right, canvasRect.top + cornerRadius + cLen, trPaint)
        canvas.drawLine(canvasRect.right - cornerRadius, canvasRect.top, canvasRect.right - cornerRadius - cLen, canvasRect.top, trPaint)
        // Bottom-Left
        val blPaint = if (activeResizeCorner == ResizeCorner.BOTTOM_LEFT) activeCornerPaint else frameCornerPaint
        canvas.drawLine(canvasRect.left, canvasRect.bottom - cornerRadius, canvasRect.left, canvasRect.bottom - cornerRadius - cLen, blPaint)
        canvas.drawLine(canvasRect.left + cornerRadius, canvasRect.bottom, canvasRect.left + cornerRadius + cLen, canvasRect.bottom, blPaint)
        // Bottom-Right
        val brPaint = if (activeResizeCorner == ResizeCorner.BOTTOM_RIGHT) activeCornerPaint else frameCornerPaint
        canvas.drawLine(canvasRect.right, canvasRect.bottom - cornerRadius, canvasRect.right, canvasRect.bottom - cornerRadius - cLen, brPaint)
        canvas.drawLine(canvasRect.right - cornerRadius, canvasRect.bottom, canvasRect.right - cornerRadius - cLen, canvasRect.bottom, brPaint)

        if (isResizingCorner) {
            val wDp = (canvasRect.width() / density).roundToInt()
            val hDp = (canvasRect.height() / density).roundToInt()
            val ratioVal = canvasRect.width() / canvasRect.height().coerceAtLeast(1f)
            val label = "${wDp} × ${hDp} dp (${String.format("%.2f", ratioVal)}:1)"
            val textWidth = badgeTextPaint.measureText(label)
            val padH = 12f * density
            val padV = 8f * density
            val cx = canvasRect.centerX()
            val cy = canvasRect.centerY()
            val pillRect = RectF(
                cx - textWidth / 2f - padH,
                cy - padV * 1.8f,
                cx + textWidth / 2f + padH,
                cy + padV * 1.4f
            )
            canvas.drawRoundRect(pillRect, 12f * density, 12f * density, badgeBgPaint)
            val textY = pillRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(label, cx, textY, badgeTextPaint)
        }

        canvas.restore()
    }

    private fun detectCornerHit(screenX: Float, screenY: Float): ResizeCorner {
        viewMatrix.mapRect(transformedCanvasRect, canvasRect)
        val hitRadius = 38f * resources.displayMetrics.density

        val corners = listOf(
            Triple(ResizeCorner.TOP_LEFT, transformedCanvasRect.left, transformedCanvasRect.top),
            Triple(ResizeCorner.TOP_RIGHT, transformedCanvasRect.right, transformedCanvasRect.top),
            Triple(ResizeCorner.BOTTOM_LEFT, transformedCanvasRect.left, transformedCanvasRect.bottom),
            Triple(ResizeCorner.BOTTOM_RIGHT, transformedCanvasRect.right, transformedCanvasRect.bottom)
        )

        var bestCorner = ResizeCorner.NONE
        var bestDist = hitRadius
        for ((corner, cx, cy) in corners) {
            val dist = hypot((screenX - cx).toDouble(), (screenY - cy).toDouble()).toFloat()
            if (dist <= bestDist) {
                bestDist = dist
                bestCorner = corner
            }
        }
        return bestCorner
    }

    private fun mapScreenToViewCanvasSpace(x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        viewInverseMatrix.mapPoints(pts)
        return pts
    }

    private fun mapPointToCanvas(x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    private fun getActivePaint(event: MotionEvent): Paint {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val isStylusEraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        val useEraser = isEraser || isStylusEraser
        val usePressure = isPressureEnabled && isStylus
        val pressure = if (usePressure) event.pressure.coerceIn(0.2f, 1.0f) else 1.0f

        val bmp = bitmap
        val bitmapToCanvasScale = if (bmp != null && canvasRect.width() > 0f) {
            bmp.width.toFloat() / canvasRect.width()
        } else {
            1.6f
        }
        val normalizedScale = (bitmapToCanvasScale / 1.6f).coerceIn(0.5f, 3.0f)

        return if (useEraser) {
            clearPaint.apply { strokeWidth = brushSize * 2.5f * normalizedScale }
        } else {
            strokePaint.apply {
                color = penColor
                val baseWidth = if (usePressure) {
                    (brushSize * pressure * 1.3f).coerceAtLeast(2f)
                } else {
                    brushSize
                }
                strokeWidth = baseWidth * normalizedScale
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val density = resources.displayMetrics.density
        val tapDistLimit = TAP_MAX_DISTANCE_DP * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = System.currentTimeMillis()
                maxPointersInGesture = 1
                hasMovedExceededSlop = false
                startPointerPositions.clear()
                startPointerPositions[event.getPointerId(0)] = PointF(event.x, event.y)

                pendingBeforeStrokeStep?.let { releaseStep(it) }
                pendingBeforeStrokeStep = createCurrentStep()

                val hitCorner = detectCornerHit(event.x, event.y)
                if (hitCorner != ResizeCorner.NONE) {
                    activeResizeCorner = hitCorner
                    isResizingCorner = true
                    isDrawing = false
                    resizeStartCanvasRect.set(canvasRect)
                    resizeStartBaseMatrix.set(baseMatrix)

                    val viewPt = mapScreenToViewCanvasSpace(event.x, event.y)
                    when (hitCorner) {
                        ResizeCorner.TOP_LEFT -> {
                            cornerDragOffsetX = canvasRect.left - viewPt[0]
                            cornerDragOffsetY = canvasRect.top - viewPt[1]
                        }
                        ResizeCorner.TOP_RIGHT -> {
                            cornerDragOffsetX = canvasRect.right - viewPt[0]
                            cornerDragOffsetY = canvasRect.top - viewPt[1]
                        }
                        ResizeCorner.BOTTOM_LEFT -> {
                            cornerDragOffsetX = canvasRect.left - viewPt[0]
                            cornerDragOffsetY = canvasRect.bottom - viewPt[1]
                        }
                        ResizeCorner.BOTTOM_RIGHT -> {
                            cornerDragOffsetX = canvasRect.right - viewPt[0]
                            cornerDragOffsetY = canvasRect.bottom - viewPt[1]
                        }
                        ResizeCorner.NONE -> {}
                    }
                    invalidate()
                } else {
                    activeResizeCorner = ResizeCorner.NONE
                    isResizingCorner = false
                    val pts = mapPointToCanvas(event.x, event.y)
                    lastDrawX = pts[0]
                    lastDrawY = pts[1]
                    currentPath.reset()
                    currentPath.moveTo(lastDrawX, lastDrawY)
                    isDrawing = true
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = maxOf(maxPointersInGesture, event.pointerCount)
                val pIdx = event.actionIndex
                val pId = event.getPointerId(pIdx)
                startPointerPositions[pId] = PointF(event.getX(pIdx), event.getY(pIdx))

                if (isResizingCorner) {
                    canvasRect.set(resizeStartCanvasRect)
                    isResizingCorner = false
                    activeResizeCorner = ResizeCorner.NONE
                    updateMatrices()
                    invalidate()
                }

                // Cancel single finger drawing stroke and restore clean snapshot
                if (isDrawing && pendingBeforeStrokeStep != null) {
                    bitmap?.eraseColor(Color.TRANSPARENT)
                    bitmapCanvas?.drawBitmap(pendingBeforeStrokeStep!!.bitmap, 0f, 0f, null)
                    currentPath.reset()
                    isDrawing = false
                    invalidate()
                }

                if (event.pointerCount >= 2) {
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    lastPanX = focusX
                    lastPanY = focusY
                    isPanning = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!hasMovedExceededSlop) {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        val start = startPointerPositions[id]
                        if (start != null) {
                            val dx = event.getX(i) - start.x
                            val dy = event.getY(i) - start.y
                            if (hypot(dx.toDouble(), dy.toDouble()) > tapDistLimit) {
                                hasMovedExceededSlop = true
                                break
                            }
                        }
                    }
                }

                if (event.pointerCount >= 2) {
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = focusX - lastPanX
                    val dy = focusY - lastPanY
                    lastPanX = focusX
                    lastPanY = focusY

                    if (isPanning && hasMovedExceededSlop) {
                        viewMatrix.postTranslate(dx, dy)
                        updateMatrices()
                        notifyViewTransform()
                        invalidate()
                    }
                } else if (event.pointerCount == 1 && isResizingCorner) {
                    val viewPt = mapScreenToViewCanvasSpace(event.x, event.y)
                    val targetX = viewPt[0] + cornerDragOffsetX
                    val targetY = viewPt[1] + cornerDragOffsetY
                    val minSize = (95f * density) / currentScale.coerceAtLeast(0.5f)
                    val margin = 6f * density
                    val minBoundX = -width * 0.5f
                    val maxBoundX = width * 1.5f
                    val minBoundY = -height * 0.5f
                    val maxBoundY = height * 1.5f

                    when (activeResizeCorner) {
                        ResizeCorner.TOP_LEFT -> {
                            canvasRect.left = targetX.coerceIn(minBoundX + margin, canvasRect.right - minSize)
                            canvasRect.top = targetY.coerceIn(minBoundY + margin, canvasRect.bottom - minSize)
                        }
                        ResizeCorner.TOP_RIGHT -> {
                            canvasRect.right = targetX.coerceIn(canvasRect.left + minSize, maxBoundX - margin)
                            canvasRect.top = targetY.coerceIn(minBoundY + margin, canvasRect.bottom - minSize)
                        }
                        ResizeCorner.BOTTOM_LEFT -> {
                            canvasRect.left = targetX.coerceIn(minBoundX + margin, canvasRect.right - minSize)
                            canvasRect.bottom = targetY.coerceIn(canvasRect.top + minSize, maxBoundY - margin)
                        }
                        ResizeCorner.BOTTOM_RIGHT -> {
                            canvasRect.right = targetX.coerceIn(canvasRect.left + minSize, maxBoundX - margin)
                            canvasRect.bottom = targetY.coerceIn(canvasRect.top + minSize, maxBoundY - margin)
                        }
                        ResizeCorner.NONE -> {}
                    }
                    invalidate()
                } else if (event.pointerCount == 1 && !isPanning && !scaleDetector.isInProgress) {
                    val pts = mapPointToCanvas(event.x, event.y)
                    val canvasX = pts[0]
                    val canvasY = pts[1]

                    if (isDrawing) {
                        val midX = (lastDrawX + canvasX) / 2f
                        val midY = (lastDrawY + canvasY) / 2f
                        currentPath.quadTo(lastDrawX, lastDrawY, midX, midY)
                        bitmapCanvas?.drawPath(currentPath, getActivePaint(event))
                        currentPath.reset()
                        currentPath.moveTo(midX, midY)
                        lastDrawX = canvasX
                        lastDrawY = canvasY
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = event.pointerCount - 1
                if (remaining >= 2) {
                    val upIdx = event.actionIndex
                    var sumX = 0f
                    var sumY = 0f
                    var count = 0
                    for (i in 0 until event.pointerCount) {
                        if (i != upIdx && count < 2) {
                            sumX += event.getX(i)
                            sumY += event.getY(i)
                            count++
                        }
                    }
                    if (count == 2) {
                        lastPanX = sumX / 2f
                        lastPanY = sumY / 2f
                    }
                } else {
                    isPanning = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - gestureStartTime
                val isTap = !hasMovedExceededSlop && duration <= TAP_TIMEOUT_MS

                if (isResizingCorner) {
                    val changed = abs(canvasRect.left - resizeStartCanvasRect.left) > 1f ||
                            abs(canvasRect.top - resizeStartCanvasRect.top) > 1f ||
                            abs(canvasRect.right - resizeStartCanvasRect.right) > 1f ||
                            abs(canvasRect.bottom - resizeStartCanvasRect.bottom) > 1f

                    isResizingCorner = false
                    activeResizeCorner = ResizeCorner.NONE

                    if (changed && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        canvasRatio = CanvasRatio.CUSTOM
                        commitCornerResizeBitmap(resizeStartCanvasRect, canvasRect)
                        pendingBeforeStrokeStep?.let {
                            if (undoStack.size >= MAX_HISTORY) {
                                releaseStep(undoStack.removeFirst())
                            }
                            undoStack.addLast(it)
                            clearRedoStack()
                            notifyUndoRedoState()
                            pendingBeforeStrokeStep = null
                        }
                        onCanvasRectChanged?.invoke(RectF(canvasRect), canvasRatio)
                    } else {
                        canvasRect.set(resizeStartCanvasRect)
                        updateMatrices()
                        pendingBeforeStrokeStep?.let { releaseStep(it) }
                        pendingBeforeStrokeStep = null
                    }
                    invalidate()
                    return true
                }

                if (isTap && maxPointersInGesture == 2) {
                    pendingBeforeStrokeStep?.let { releaseStep(it) }
                    pendingBeforeStrokeStep = null
                    isDrawing = false
                    currentPath.reset()
                    if (undo()) {
                        onUndoTriggered?.invoke()
                    }
                    return true
                } else if (isTap && maxPointersInGesture == 3) {
                    pendingBeforeStrokeStep?.let { releaseStep(it) }
                    pendingBeforeStrokeStep = null
                    isDrawing = false
                    currentPath.reset()
                    if (redo()) {
                        onRedoTriggered?.invoke()
                    }
                    return true
                }

                if (isDrawing && maxPointersInGesture == 1) {
                    val pts = mapPointToCanvas(event.x, event.y)
                    val canvasX = pts[0]
                    val canvasY = pts[1]
                    val paint = getActivePaint(event)

                    if (!hasMovedExceededSlop) {
                        // Single tap creates a clean round dot
                        bitmapCanvas?.drawLine(canvasX, canvasY, canvasX + 0.05f, canvasY, paint)
                    } else {
                        currentPath.lineTo(canvasX, canvasY)
                        bitmapCanvas?.drawPath(currentPath, paint)
                    }
                    currentPath.reset()
                    isDrawing = false
                    invalidate()

                    pendingBeforeStrokeStep?.let {
                        if (undoStack.size >= MAX_HISTORY) {
                            releaseStep(undoStack.removeFirst())
                        }
                        undoStack.addLast(it)
                        clearRedoStack()
                        notifyUndoRedoState()
                        pendingBeforeStrokeStep = null
                    }
                } else {
                    pendingBeforeStrokeStep?.let { releaseStep(it) }
                    pendingBeforeStrokeStep = null
                    isDrawing = false
                    currentPath.reset()
                }

                isPanning = false
            }
        }
        return true
    }

    fun resetZoom() {
        currentScale = 1f
        viewMatrix.reset()
        updateMatrices()
        onZoomChanged?.invoke(100)
        notifyViewTransform()
        invalidate()
    }

    fun selectColor(color: Int) {
        isEraser = false
        penColor = color
    }

    fun clear() {
        captureCurrentToUndo()
        bitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun getDoodleBitmap(): Bitmap? = bitmap
}