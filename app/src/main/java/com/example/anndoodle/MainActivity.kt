package com.example.anndoodle

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val palette32 = listOf(
        "#FFFFFF", "#E0E0E0", "#9E9E9E", "#616161", "#212121", "#000000",
        "#FFCDD2", "#EF5350", "#D32F2F", "#F8BBD0", "#EC407A", "#C2185B",
        "#E1BEE7", "#AB47BC", "#7B1FA2", "#D1C4E9", "#7E57C2", "#512DA8",
        "#BBDEFB", "#42A5F5", "#1976D2", "#B2EBF2", "#26C6DA", "#0097A7",
        "#C8E6C9", "#66BB6A", "#388E3C", "#FFF9C4", "#FFEE58", "#FBC02D",
        "#FFE0B2", "#FFA726"
    ).map { Color.parseColor(it) }

    private val swatchViews = mutableListOf<View>()
    private var selectedColorIndex = 0
    private var isTransparentBg = true

    private val PREFS_NAME = "anndoodle_prefs"
    private val PREF_RATIO_KEY = "canvas_ratio"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootLayout = findViewById<FrameLayout>(R.id.root_layout)
        val doodleView = findViewById<DoodleView>(R.id.doodle_view)
        val btnRatio = findViewById<MaterialButton>(R.id.btn_ratio)
        val btnBgToggle = findViewById<MaterialButton>(R.id.btn_bg_toggle)
        val btnZoomReset = findViewById<MaterialButton>(R.id.btn_zoom_reset)
        val btnUndo = findViewById<MaterialButton>(R.id.btn_undo)
        val btnRedo = findViewById<MaterialButton>(R.id.btn_redo)
        val btnEraser = findViewById<MaterialButton>(R.id.btn_eraser)
        val btnClear = findViewById<MaterialButton>(R.id.btn_clear)
        val btnDone = findViewById<MaterialButton>(R.id.btn_done)
        val sliderBrush = findViewById<Slider>(R.id.slider_brush)
        val colorContainer = findViewById<LinearLayout>(R.id.color_container)
        val floatingToolbar = findViewById<MaterialCardView>(R.id.floating_toolbar)
        val dragHandle = findViewById<View>(R.id.drag_handle_area)

        setupCanvasRatio(doodleView, btnRatio)

        setupColorSwatches(colorContainer, doodleView, btnEraser)

        applyBackgroundState(rootLayout, btnBgToggle)

        btnBgToggle.setOnClickListener {
            isTransparentBg = !isTransparentBg
            applyBackgroundState(rootLayout, btnBgToggle)
        }

        doodleView.onZoomChanged = { zoomPercent ->
            btnZoomReset.text = "${zoomPercent}%"
        }
        btnZoomReset.setOnClickListener {
            doodleView.resetZoom()
        }

        // Undo & Redo setup
        btnUndo.setOnClickListener {
            doodleView.undo()
        }
        btnRedo.setOnClickListener {
            doodleView.redo()
        }

        doodleView.onUndoRedoStateChanged = { canUndo, canRedo ->
            btnUndo.isEnabled = canUndo
            btnUndo.alpha = if (canUndo) 1.0f else 0.35f
            btnRedo.isEnabled = canRedo
            btnRedo.alpha = if (canRedo) 1.0f else 0.35f
        }
        // Initial button states
        btnUndo.isEnabled = false
        btnUndo.alpha = 0.35f
        btnRedo.isEnabled = false
        btnRedo.alpha = 0.35f

        // Multi-finger gesture feedback
        doodleView.onUndoTriggered = {
            Toast.makeText(this, "⤺ เลิกทำ (Undo)", Toast.LENGTH_SHORT).show()
        }
        doodleView.onRedoTriggered = {
            Toast.makeText(this, "⤻ ทำซ้ำ (Redo)", Toast.LENGTH_SHORT).show()
        }

        sliderBrush.addOnChangeListener { _, value, _ ->
            doodleView.brushSize = value
        }

        btnEraser.setOnClickListener {
            doodleView.isEraser = true
            updateEraserButtonUI(btnEraser, true)
            highlightSelectedSwatch(-1)
        }

        btnClear.setOnClickListener {
            doodleView.clear()
        }

        btnDone.setOnClickListener {
            saveAndPinWidget(doodleView, btnDone)
        }

        setupDraggableToolbar(dragHandle, floatingToolbar)
    }

    private fun setupCanvasRatio(doodleView: DoodleView, btnRatio: MaterialButton) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var initialRatio = CanvasRatio.fromKey(prefs.getString(PREF_RATIO_KEY, CanvasRatio.SQUARE.key))

        // Check if opened from an existing widget
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

            if (minW > 0 && minH > 0) {
                val detectedRatio = minW.toFloat() / minH.toFloat()
                initialRatio = when {
                    detectedRatio in 0.85f..1.15f -> CanvasRatio.SQUARE
                    detectedRatio in 1.15f..1.55f -> CanvasRatio.STANDARD
                    detectedRatio in 1.55f..1.85f -> CanvasRatio.WIDE
                    detectedRatio > 1.85f -> CanvasRatio.BANNER
                    else -> CanvasRatio.SQUARE
                }
            }
        }

        doodleView.setCanvasRatio(initialRatio)
        updateRatioButtonLabel(btnRatio, initialRatio)

        val ratioList = listOf(
            CanvasRatio.SQUARE to "1:1 (จัตุรัส — สำหรับ Widget 2x2, 3x3)",
            CanvasRatio.STANDARD to "4:3 (แนวกว้าง — สำหรับ Widget 4x3)",
            CanvasRatio.WIDE to "16:9 (แถบกว้าง — สำหรับ Widget 4x2, 5x2)",
            CanvasRatio.BANNER to "2:1 (แถบยาว — สำหรับ Widget 4x2 แนวนอน)",
            CanvasRatio.FULLSCREEN to "เต็มจอ (ตามขนาดหน้าจอเครื่อง)"
        )

        btnRatio.setOnClickListener {
            val currentIndex = ratioList.indexOfFirst { it.first == doodleView.canvasRatio }
            val titles = ratioList.map { it.second }.toTypedArray()

            MaterialAlertDialogBuilder(this)
                .setTitle("เลือกสัดส่วน Widget / Canvas")
                .setSingleChoiceItems(titles, currentIndex) { dialog, which ->
                    val selected = ratioList[which].first
                    doodleView.setCanvasRatio(selected)
                    prefs.edit().putString(PREF_RATIO_KEY, selected.key).apply()
                    updateRatioButtonLabel(btnRatio, selected)
                    dialog.dismiss()
                }
                .setNegativeButton("ยกเลิก", null)
                .show()
        }
    }

    private fun updateRatioButtonLabel(button: MaterialButton, ratio: CanvasRatio) {
        val shortLabel = when (ratio) {
            CanvasRatio.SQUARE -> "1:1"
            CanvasRatio.STANDARD -> "4:3"
            CanvasRatio.WIDE -> "16:9"
            CanvasRatio.BANNER -> "2:1"
            CanvasRatio.FULLSCREEN -> "เต็มจอ"
        }
        button.text = "สัดส่วน : $shortLabel"
    }

    private fun applyBackgroundState(rootLayout: FrameLayout, btnBgToggle: MaterialButton) {
        if (isTransparentBg) {
            rootLayout.setBackgroundColor(Color.TRANSPARENT)
            btnBgToggle.text = "พื้นหลัง : ใส"
            btnBgToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CC1E1E1E"))
        } else {
            rootLayout.setBackgroundColor(Color.parseColor("#181818"))
            btnBgToggle.text = "พื้นหลัง : ดำ"
            btnBgToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#383838"))
        }
    }

    private fun setupColorSwatches(container: LinearLayout, doodleView: DoodleView, btnEraser: MaterialButton) {
        val sizePx = (38 * resources.displayMetrics.density).toInt()
        val marginPx = (5 * resources.displayMetrics.density).toInt()

        palette32.forEachIndexed { index, color ->
            val view = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                    gravity = Gravity.CENTER_VERTICAL
                }
                setOnClickListener {
                    selectedColorIndex = index
                    doodleView.selectColor(color)
                    updateEraserButtonUI(btnEraser, false)
                    highlightSelectedSwatch(index)
                }
            }
            swatchViews.add(view)
            container.addView(view)
        }
        highlightSelectedSwatch(0)
    }

    private fun highlightSelectedSwatch(selectedIndex: Int) {
        val strokePx = (3 * resources.displayMetrics.density).toInt()
        val thinStrokePx = (1 * resources.displayMetrics.density).toInt()

        swatchViews.forEachIndexed { index, view ->
            val color = palette32[index]
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (index == selectedIndex) {
                    val borderColor = if (color == Color.WHITE) Color.parseColor("#00E5FF") else Color.WHITE
                    setStroke(strokePx, borderColor)
                } else {
                    setStroke(thinStrokePx, Color.parseColor("#55FFFFFF"))
                }
            }
            view.background = drawable
            view.scaleX = if (index == selectedIndex) 1.18f else 1.0f
            view.scaleY = if (index == selectedIndex) 1.18f else 1.0f
        }
    }

    private fun updateEraserButtonUI(btnEraser: MaterialButton, active: Boolean) {
        if (active) {
            btnEraser.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            btnEraser.iconTint = ColorStateList.valueOf(Color.WHITE)
        } else {
            btnEraser.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#383838"))
            btnEraser.iconTint = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
        }
    }

    private fun setupDraggableToolbar(handle: View, toolbar: View) {
        var dX = 0f
        var dY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = toolbar.x - event.rawX
                    dY = toolbar.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = toolbar.parent as View
                    val newX = (event.rawX + dX).coerceIn(0f, (parent.width - toolbar.width).toFloat().coerceAtLeast(0f))
                    val newY = (event.rawY + dY).coerceIn(0f, (parent.height - toolbar.height).toFloat().coerceAtLeast(0f))
                    toolbar.animate()
                        .x(newX)
                        .y(newY)
                        .setDuration(0)
                        .start()
                }
            }
            true
        }
    }

    private fun saveAndPinWidget(doodleView: DoodleView, btnDone: MaterialButton) {
        val bmp = doodleView.getDoodleBitmap() ?: return
        btnDone.isEnabled = false

        // Create immutable copy for background encoding
        val bmpCopy = bmp.copy(bmp.config, false)

        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(filesDir, "doodle.png")
            FileOutputStream(file).use { out ->
                bmpCopy.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmpCopy.recycle()

            withContext(Dispatchers.Main) {
                val appWidgetManager = AppWidgetManager.getInstance(this@MainActivity)
                val provider = ComponentName(this@MainActivity, DoodleWidget::class.java)
                val existingIds = appWidgetManager.getAppWidgetIds(provider)

                if (existingIds.isNotEmpty()) {
                    val intent = Intent(this@MainActivity, DoodleWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, existingIds)
                    }
                    sendBroadcast(intent)
                    Toast.makeText(this@MainActivity, "อัปเดต Doodle บน Widget แล้ว!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(provider, null, null)
                        Toast.makeText(this@MainActivity, "กด 'เพิ่ม' เพื่อวางบนหน้าจอหลัก", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "บันทึกแล้ว!", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }
}