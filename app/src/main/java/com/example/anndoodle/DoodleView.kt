package com.example.anndoodle

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import kotlin.math.hypot

enum class CanvasRatio(val key: String, val title: String, val ratioW: Float, val ratioH: Float) {
    SQUARE("1_1", "1:1 (จัตุรัส)", 1f, 1f),
    STANDARD("4_3", "4:3 (แนวกว้าง)", 4f, 3f),
    WIDE("16_9", "16:9 (แถบกว้าง)", 16f, 9f),
    BANNER("2_1", "2:1 (แถบยาว)", 2f, 1f),
    PORTRAIT_5_4("4_5", "5:4 (แนวตั้ง)", 4f, 5f),
    FULLSCREEN("full", "เต็มจอ", 0f, 0f);

    fun getTargetDimensions(viewW: Int, viewH: Int): Pair<Int, Int> {
        return when (this) {
            SQUARE -> Pair(1440, 1440)
            STANDARD -> Pair(1600, 1200)
            WIDE -> Pair(1920, 1080)
            BANNER -> Pair(1600, 800)
            PORTRAIT_5_4 -> Pair(1280, 1600)
            FULLSCREEN -> Pair(viewW.coerceAtLeast(720), viewH.coerceAtLeast(720))
        }
    }

    companion object {
        fun fromKey(key: String?): CanvasRatio {
            return entries.firstOrNull { it.key == key } ?: SQUARE
        }
    }
}

class DoodleView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var canvasRatio: CanvasRatio = CanvasRatio.SQUARE
        private set

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
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
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

    val canvasRect = RectF()
    private val clipPath = Path()
    private val transformedCanvasRect = RectF()

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val totalMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var currentScale = 1f

    private val currentPath = Path()
    private var lastDrawX = 0f
    private var lastDrawY = 0f
    private var isDrawing = false

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    // Undo / Redo history & pooling
    private val MAX_HISTORY = 16
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private val bitmapPool = ArrayDeque<Bitmap>()
    private var pendingBeforeStrokeBitmap: Bitmap? = null

    // Multi-touch & tap gesture tracking
    private var gestureStartTime = 0L
    private var maxPointersInGesture = 0
    private var hasMovedExceededSlop = false
    private val startPointerPositions = HashMap<Int, PointF>()
    private val TAP_TIMEOUT_MS = 350L
    private val TAP_MAX_DISTANCE_DP = 25f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            if (newScale in 0.5f..5.0f) {
                currentScale = newScale
                viewMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                updateMatrices()
                onZoomChanged?.invoke((currentScale * 100).toInt())
                invalidate()
            }
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (bitmap == null) {
                loadExistingOrCreateNew(w, h)
            } else if (canvasRatio == CanvasRatio.FULLSCREEN && (oldw != w || oldh != h)) {
                adaptBitmapToDimensions(w, h)
            }
            updateCanvasGeometry(w, h)
            invalidate()
        }
    }

    fun setCanvasRatio(ratio: CanvasRatio) {
        if (canvasRatio == ratio) return

        captureCurrentToUndo()
        canvasRatio = ratio

        if (width > 0 && height > 0) {
            val (targetW, targetH) = ratio.getTargetDimensions(width, height)
            adaptBitmapToDimensions(targetW, targetH)
            updateCanvasGeometry(width, height)
            resetZoom()
            invalidate()
            notifyUndoRedoState()
        }
    }

    private fun updateCanvasGeometry(w: Int, h: Int) {
        val density = resources.displayMetrics.density
        if (canvasRatio == CanvasRatio.FULLSCREEN) {
            canvasRect.set(0f, 0f, w.toFloat(), h.toFloat())
        } else {
            val topPadding = 75f * density
            val bottomPadding = 120f * density
            val sidePadding = 16f * density

            val availW = (w - sidePadding * 2f).coerceAtLeast(100f)
            val availH = (h - topPadding - bottomPadding).coerceAtLeast(100f)
            val ratio = canvasRatio.ratioW / canvasRatio.ratioH

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

        totalMatrix.set(baseMatrix)
        totalMatrix.postConcat(viewMatrix)
        totalMatrix.invert(inverseMatrix)
    }

    private fun loadExistingOrCreateNew(viewW: Int, viewH: Int) {
        val (targetW, targetH) = canvasRatio.getTargetDimensions(viewW, viewH)
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
        clearUndoHistory()
    }

    private fun adaptBitmapToDimensions(targetW: Int, targetH: Int) {
        val oldBmp = bitmap ?: return
        if (oldBmp.width == targetW && oldBmp.height == targetH) return

        val newBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(newBmp)
        val scale = minOf(targetW.toFloat() / oldBmp.width, targetH.toFloat() / oldBmp.height)
        val sw = oldBmp.width * scale
        val sh = oldBmp.height * scale
        val dx = (targetW - sw) / 2f
        val dy = (targetH - sh) / 2f
        val destRect = RectF(dx, dy, dx + sw, dy + sh)
        c.drawBitmap(oldBmp, null, destRect, bitmapPaint)

        bitmap = newBmp
        bitmapCanvas = Canvas(bitmap!!)
        clearUndoHistory()
    }

    // --- Undo / Redo Pool & Stack ---

    private fun obtainPooledBitmap(w: Int, h: Int): Bitmap {
        while (bitmapPool.isNotEmpty()) {
            val pooled = bitmapPool.removeFirst()
            if (!pooled.isRecycled && pooled.width == w && pooled.height == h) {
                pooled.eraseColor(Color.TRANSPARENT)
                return pooled
            } else {
                pooled.recycle()
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

    private fun captureCurrentToUndo() {
        val current = bitmap ?: return
        val snapshot = copyToPooled(current)
        if (undoStack.size >= MAX_HISTORY) {
            val discarded = undoStack.removeFirst()
            releaseBitmap(discarded)
        }
        undoStack.addLast(snapshot)
        clearRedoStack()
        notifyUndoRedoState()
    }

    private fun clearRedoStack() {
        while (redoStack.isNotEmpty()) {
            releaseBitmap(redoStack.removeLast())
        }
    }

    private fun clearUndoHistory() {
        while (undoStack.isNotEmpty()) {
            releaseBitmap(undoStack.removeLast())
        }
        clearRedoStack()
        notifyUndoRedoState()
    }

    fun undo(): Boolean {
        val current = bitmap ?: return false
        if (undoStack.isEmpty()) return false

        val currentSnapshot = copyToPooled(current)
        if (redoStack.size >= MAX_HISTORY) {
            releaseBitmap(redoStack.removeFirst())
        }
        redoStack.addLast(currentSnapshot)

        val prevSnapshot = undoStack.removeLast()
        current.eraseColor(Color.TRANSPARENT)
        bitmapCanvas?.drawBitmap(prevSnapshot, 0f, 0f, null)
        releaseBitmap(prevSnapshot)

        invalidate()
        notifyUndoRedoState()
        return true
    }

    fun redo(): Boolean {
        val current = bitmap ?: return false
        if (redoStack.isEmpty()) return false

        val currentSnapshot = copyToPooled(current)
        if (undoStack.size >= MAX_HISTORY) {
            releaseBitmap(undoStack.removeFirst())
        }
        undoStack.addLast(currentSnapshot)

        val nextSnapshot = redoStack.removeLast()
        current.eraseColor(Color.TRANSPARENT)
        bitmapCanvas?.drawBitmap(nextSnapshot, 0f, 0f, null)
        releaseBitmap(nextSnapshot)

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

        if (canvasRatio != CanvasRatio.FULLSCREEN) {
            // Draw dim mask outside the transformed canvas
            canvas.save()
            canvas.concat(viewMatrix)

            clipPath.reset()
            clipPath.addRoundRect(canvasRect, cornerRadius, cornerRadius, Path.Direction.CW)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutPath(clipPath)
            }
            canvas.drawColor(Color.argb(85, 0, 0, 0))
            canvas.restore()

            // Draw bitmap and frame inside transformed canvas
            canvas.save()
            canvas.concat(viewMatrix)

            val count = canvas.save()
            canvas.clipPath(clipPath)
            bitmap?.let {
                canvas.drawBitmap(it, baseMatrix, bitmapPaint)
            }
            canvas.restoreToCount(count)

            // Draw widget boundary outline
            canvas.drawRoundRect(canvasRect, cornerRadius, cornerRadius, frameBorderPaint)

            // Draw corner accent handles
            val cLen = 14f * density
            // Top-Left
            canvas.drawLine(canvasRect.left, canvasRect.top + cornerRadius, canvasRect.left, canvasRect.top + cornerRadius + cLen, frameCornerPaint)
            canvas.drawLine(canvasRect.left + cornerRadius, canvasRect.top, canvasRect.left + cornerRadius + cLen, canvasRect.top, frameCornerPaint)
            // Top-Right
            canvas.drawLine(canvasRect.right, canvasRect.top + cornerRadius, canvasRect.right, canvasRect.top + cornerRadius + cLen, frameCornerPaint)
            canvas.drawLine(canvasRect.right - cornerRadius, canvasRect.top, canvasRect.right - cornerRadius - cLen, canvasRect.top, frameCornerPaint)
            // Bottom-Left
            canvas.drawLine(canvasRect.left, canvasRect.bottom - cornerRadius, canvasRect.left, canvasRect.bottom - cornerRadius - cLen, frameCornerPaint)
            canvas.drawLine(canvasRect.left + cornerRadius, canvasRect.bottom, canvasRect.left + cornerRadius + cLen, canvasRect.bottom, frameCornerPaint)
            // Bottom-Right
            canvas.drawLine(canvasRect.right, canvasRect.bottom - cornerRadius, canvasRect.right, canvasRect.bottom - cornerRadius - cLen, frameCornerPaint)
            canvas.drawLine(canvasRect.right - cornerRadius, canvasRect.bottom, canvasRect.right - cornerRadius - cLen, canvasRect.bottom, frameCornerPaint)

            canvas.restore()
        } else {
            canvas.save()
            canvas.concat(viewMatrix)
            bitmap?.let {
                canvas.drawBitmap(it, baseMatrix, bitmapPaint)
            }
            canvas.restore()
        }
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

        return if (useEraser) {
            clearPaint.apply { strokeWidth = brushSize * 2.5f }
        } else {
            strokePaint.apply {
                color = penColor
                strokeWidth = if (usePressure) {
                    (brushSize * pressure * 1.3f).coerceAtLeast(2f)
                } else {
                    brushSize
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val tapDistLimit = TAP_MAX_DISTANCE_DP * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartTime = System.currentTimeMillis()
                maxPointersInGesture = 1
                hasMovedExceededSlop = false
                startPointerPositions.clear()
                startPointerPositions[event.getPointerId(0)] = PointF(event.x, event.y)

                bitmap?.let {
                    pendingBeforeStrokeBitmap?.let { old -> releaseBitmap(old) }
                    pendingBeforeStrokeBitmap = copyToPooled(it)
                }

                val pts = mapPointToCanvas(event.x, event.y)
                lastDrawX = pts[0]
                lastDrawY = pts[1]
                currentPath.reset()
                currentPath.moveTo(lastDrawX, lastDrawY)
                isDrawing = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = maxOf(maxPointersInGesture, event.pointerCount)
                val pIdx = event.actionIndex
                val pId = event.getPointerId(pIdx)
                startPointerPositions[pId] = PointF(event.getX(pIdx), event.getY(pIdx))

                // Cancel single finger drawing stroke and restore clean snapshot
                if (isDrawing && pendingBeforeStrokeBitmap != null) {
                    bitmap?.eraseColor(Color.TRANSPARENT)
                    bitmapCanvas?.drawBitmap(pendingBeforeStrokeBitmap!!, 0f, 0f, null)
                    currentPath.reset()
                    isDrawing = false
                    invalidate()
                }

                if (event.pointerCount > 1) {
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

                if (event.pointerCount > 1) {
                    if (isPanning && !scaleDetector.isInProgress && hasMovedExceededSlop) {
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f
                        val dx = focusX - lastPanX
                        val dy = focusY - lastPanY
                        viewMatrix.postTranslate(dx, dy)
                        updateMatrices()
                        invalidate()
                        lastPanX = focusX
                        lastPanY = focusY
                    }
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
                if (event.pointerCount <= 2) {
                    isPanning = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val duration = System.currentTimeMillis() - gestureStartTime
                val isTap = !hasMovedExceededSlop && duration <= TAP_TIMEOUT_MS

                if (isTap && maxPointersInGesture == 2) {
                    pendingBeforeStrokeBitmap?.let { releaseBitmap(it) }
                    pendingBeforeStrokeBitmap = null
                    isDrawing = false
                    currentPath.reset()
                    if (undo()) {
                        onUndoTriggered?.invoke()
                    }
                    return true
                } else if (isTap && maxPointersInGesture == 3) {
                    pendingBeforeStrokeBitmap?.let { releaseBitmap(it) }
                    pendingBeforeStrokeBitmap = null
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
                        // Single tap creates a point/dot
                        bitmapCanvas?.drawPoint(canvasX, canvasY, paint)
                    } else {
                        currentPath.lineTo(canvasX, canvasY)
                        bitmapCanvas?.drawPath(currentPath, paint)
                    }
                    currentPath.reset()
                    isDrawing = false
                    invalidate()

                    pendingBeforeStrokeBitmap?.let {
                        if (undoStack.size >= MAX_HISTORY) {
                            releaseBitmap(undoStack.removeFirst())
                        }
                        undoStack.addLast(it)
                        clearRedoStack()
                        notifyUndoRedoState()
                        pendingBeforeStrokeBitmap = null
                    }
                } else {
                    pendingBeforeStrokeBitmap?.let { releaseBitmap(it) }
                    pendingBeforeStrokeBitmap = null
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