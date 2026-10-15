package com.example.anndoodle

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File

class DoodleView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
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

    private val bitmapPaint = Paint(Paint.DITHER_FLAG)

    var penColor: Int = Color.WHITE
        set(value) {
            field = value
            strokePaint.color = value
        }

    var brushSize: Float = 16f
    var isEraser: Boolean = false

    var onZoomChanged: ((Int) -> Unit)? = null

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var currentScale = 1f

    private val currentPath = Path()
    private var lastDrawX = 0f
    private var lastDrawY = 0f
    private var isDrawing = false

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            if (newScale in 0.5f..5.0f) {
                currentScale = newScale
                viewMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                onZoomChanged?.invoke((currentScale * 100).toInt())
                invalidate()
            }
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && bitmap == null) {
            loadExistingOrCreateNew(w, h)
        }
    }

    private fun loadExistingOrCreateNew(w: Int, h: Int) {
        val file = File(context.filesDir, "doodle.png")
        bitmap = if (file.exists()) {
            val loaded = BitmapFactory.decodeFile(file.absolutePath)
            if (loaded != null) {
                Bitmap.createScaledBitmap(loaded, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        bitmapCanvas = Canvas(bitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(viewMatrix)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        canvas.restore()
    }

    private fun mapPointToCanvas(x: Float, y: Float): FloatArray {
        viewMatrix.invert(inverseMatrix)
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount > 1) {
            isDrawing = false
            currentPath.reset()
            val focusX = (event.getX(0) + event.getX(1)) / 2f
            val focusY = (event.getY(0) + event.getY(1)) / 2f

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastPanX = focusX
                    lastPanY = focusY
                    isPanning = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanning && !scaleDetector.isInProgress) {
                        val dx = focusX - lastPanX
                        val dy = focusY - lastPanY
                        viewMatrix.postTranslate(dx, dy)
                        invalidate()
                    }
                    lastPanX = focusX
                    lastPanY = focusY
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                }
            }
            return true
        }

        if (isPanning || scaleDetector.isInProgress) return true

        val pts = mapPointToCanvas(event.x, event.y)
        val canvasX = pts[0]
        val canvasY = pts[1]

        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val isStylusEraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        val useEraser = isEraser || isStylusEraser
        val pressure = if (isStylus) event.pressure.coerceIn(0.2f, 1.0f) else 0.75f

        val activePaint = if (useEraser) {
            clearPaint.apply { strokeWidth = brushSize * 2.5f }
        } else {
            strokePaint.apply {
                color = penColor
                strokeWidth = (brushSize * pressure * 1.3f).coerceAtLeast(2f)
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                currentPath.reset()
                currentPath.moveTo(canvasX, canvasY)
                lastDrawX = canvasX
                lastDrawY = canvasY
                bitmapCanvas?.drawPoint(canvasX, canvasY, activePaint)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDrawing) {
                    val midX = (lastDrawX + canvasX) / 2f
                    val midY = (lastDrawY + canvasY) / 2f
                    currentPath.quadTo(lastDrawX, lastDrawY, midX, midY)
                    bitmapCanvas?.drawPath(currentPath, activePaint)
                    currentPath.reset()
                    currentPath.moveTo(midX, midY)
                    lastDrawX = canvasX
                    lastDrawY = canvasY
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    currentPath.lineTo(canvasX, canvasY)
                    bitmapCanvas?.drawPath(currentPath, activePaint)
                    currentPath.reset()
                    isDrawing = false
                    invalidate()
                }
            }
        }
        return true
    }

    fun resetZoom() {
        currentScale = 1f
        viewMatrix.reset()
        onZoomChanged?.invoke(100)
        invalidate()
    }

    fun selectColor(color: Int) {
        isEraser = false
        penColor = color
    }

    fun clear() {
        bitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun getDoodleBitmap(): Bitmap? = bitmap
}