package com.example.anndoodle

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // 64-color curated palette
    private val palette64 = listOf(
        // Grayscale (8)
        "#FFFFFF", "#EEEEEE", "#E0E0E0", "#BDBDBD", "#9E9E9E", "#616161", "#424242", "#000000",
        // Red / Crimson (8)
        "#FFCDD2", "#EF9A9A", "#E57373", "#EF5350", "#F44336", "#E53935", "#D32F2F", "#B71C1C",
        // Pink / Rose (8)
        "#F8BBD0", "#F48FB1", "#F06292", "#EC407A", "#E91E63", "#D81B60", "#C2185B", "#880E4F",
        // Purple / Violet (8)
        "#E1BEE7", "#CE93D8", "#BA68C8", "#AB47BC", "#9C27B0", "#8E24AA", "#7B1FA2", "#4A148C",
        // Deep Purple / Indigo (8)
        "#D1C4E9", "#B39DDB", "#9575CD", "#7E57C2", "#673AB7", "#5E35B1", "#512DA8", "#311B92",
        // Blue / Cyan (8)
        "#BBDEFB", "#90CAF9", "#64B5F6", "#42A5F5", "#2196F3", "#1E88E5", "#1976D2", "#0D47A1",
        // Teal / Cyan / Green (8)
        "#B2EBF2", "#80DEEA", "#4DD0E1", "#26C6DA", "#00BCD4", "#00ACC1", "#0097A7", "#006064",
        // Mint / Lime / Yellow / Orange (8)
        "#C8E6C9", "#81C784", "#4CAF50", "#2E7D32", "#FFF59D", "#FFEE58", "#FBC02D", "#FFA726"
    ).map { Color.parseColor(it) }

    private val swatchViews = mutableListOf<View>()
    private var selectedColorIndex = 0
    private var isTransparentBg = true

    private val PREFS_NAME = "anndoodle_prefs"
    private val PREF_RATIO_KEY = "canvas_ratio"
    private val PREF_LANG_KEY = "app_language"

    private var currentLang = "en" // Default is English

    private lateinit var rootLayout: FrameLayout
    private lateinit var doodleView: DoodleView
    private lateinit var btnRatio: MaterialButton
    private lateinit var btnBgToggle: MaterialButton
    private lateinit var btnZoomReset: MaterialButton
    private lateinit var btnMore: MaterialButton
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnRedo: MaterialButton
    private lateinit var btnEraser: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var btnDone: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLang = prefs.getString(PREF_LANG_KEY, "en") ?: "en"

        rootLayout = findViewById(R.id.root_layout)
        doodleView = findViewById(R.id.doodle_view)
        btnRatio = findViewById(R.id.btn_ratio)
        btnBgToggle = findViewById(R.id.btn_bg_toggle)
        btnZoomReset = findViewById(R.id.btn_zoom_reset)
        btnMore = findViewById(R.id.btn_more)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnEraser = findViewById(R.id.btn_eraser)
        btnClear = findViewById(R.id.btn_clear)
        btnDone = findViewById(R.id.btn_done)
        val sliderBrush = findViewById<Slider>(R.id.slider_brush)
        val colorContainer = findViewById<LinearLayout>(R.id.color_container)
        val floatingToolbar = findViewById<MaterialCardView>(R.id.floating_toolbar)
        val dragHandle = findViewById<View>(R.id.drag_handle_area)

        setupCanvasRatio()
        setupColorSwatches(colorContainer)
        applyBackgroundState()

        btnBgToggle.setOnClickListener {
            isTransparentBg = !isTransparentBg
            applyBackgroundState()
        }

        doodleView.onZoomChanged = { zoomPercent ->
            btnZoomReset.text = "${zoomPercent}%"
        }
        btnZoomReset.setOnClickListener {
            doodleView.resetZoom()
        }

        btnMore.setOnClickListener {
            showAboutModal()
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
        btnUndo.isEnabled = false
        btnUndo.alpha = 0.35f
        btnRedo.isEnabled = false
        btnRedo.alpha = 0.35f

        // Multi-finger gesture feedback
        doodleView.onUndoTriggered = {
            val msg = if (currentLang == "th") "⤺ เลิกทำ (Undo)" else "⤺ Undo"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        doodleView.onRedoTriggered = {
            val msg = if (currentLang == "th") "⤻ ทำซ้ำ (Redo)" else "⤻ Redo"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        sliderBrush.addOnChangeListener { _, value, _ ->
            doodleView.brushSize = value
        }

        btnEraser.setOnClickListener {
            doodleView.isEraser = true
            updateEraserButtonUI(true)
            highlightSelectedSwatch(-1)
        }

        btnClear.setOnClickListener {
            doodleView.clear()
        }

        btnDone.setOnClickListener {
            saveAndPinWidget()
        }

        setupDraggableToolbar(dragHandle, floatingToolbar)
        updateUILanguage()
    }

    private fun setupCanvasRatio() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var initialRatio = CanvasRatio.fromKey(prefs.getString(PREF_RATIO_KEY, CanvasRatio.SQUARE.key))

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
        updateRatioButtonLabel(initialRatio)

        btnRatio.setOnClickListener {
            showVisualRatioModal()
        }
    }

    private fun showVisualRatioModal() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.modal_aspect_ratio, null)
        bottomSheet.setContentView(view)

        val title = view.findViewById<TextView>(R.id.modal_ratio_title)
        title.text = if (currentLang == "th") "สัดส่วน Widget" else "Widget Aspect Ratio"

        val cards = listOf(
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_square), CanvasRatio.SQUARE, view.findViewById<TextView>(R.id.sub_ratio_square)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_standard), CanvasRatio.STANDARD, view.findViewById<TextView>(R.id.sub_ratio_standard)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_wide), CanvasRatio.WIDE, view.findViewById<TextView>(R.id.sub_ratio_wide)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_banner), CanvasRatio.BANNER, view.findViewById<TextView>(R.id.sub_ratio_banner)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_full), CanvasRatio.FULLSCREEN, view.findViewById<TextView>(R.id.sub_ratio_full))
        )

        // Subtitle localization
        if (currentLang == "th") {
            cards[0].third.text = "จัตุรัส (2x2)"
            cards[1].third.text = "คลาสสิก (4x3)"
            cards[2].third.text = "แบนเนอร์ (4x2)"
            cards[3].third.text = "แถบยาว (5x2)"
            cards[4].third.text = "เต็มหน้าจอ"
        } else {
            cards[0].third.text = "Square (2x2)"
            cards[1].third.text = "Classic (4x3)"
            cards[2].third.text = "Wide (4x2)"
            cards[3].third.text = "Banner (5x2)"
            cards[4].third.text = "Full Screen"
        }

        val highlightStroke = Color.parseColor("#00E5FF")
        val normalStroke = Color.parseColor("#22FFFFFF")
        val highlightBg = Color.parseColor("#2E00E5FF")
        val normalBg = Color.parseColor("#1AFFFFFF")
        val strokeWidthActive = (2.5f * resources.displayMetrics.density).toInt()
        val strokeWidthNormal = (1f * resources.displayMetrics.density).toInt()

        cards.forEach { (card, ratio, _) ->
            val isActive = doodleView.canvasRatio == ratio
            card.strokeColor = if (isActive) highlightStroke else normalStroke
            card.strokeWidth = if (isActive) strokeWidthActive else strokeWidthNormal
            card.setCardBackgroundColor(if (isActive) highlightBg else normalBg)

            card.setOnClickListener {
                doodleView.setCanvasRatio(ratio)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_RATIO_KEY, ratio.key)
                    .apply()
                updateRatioButtonLabel(ratio)
                bottomSheet.dismiss()
            }
        }

        bottomSheet.show()
    }

    private fun showAboutModal() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.modal_about, null)
        bottomSheet.setContentView(view)

        val btnLinkX = view.findViewById<MaterialButton>(R.id.btn_link_x)
        val btnLinkGithub = view.findViewById<MaterialButton>(R.id.btn_link_github)
        val toggleLang = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_language)
        val labelLang = view.findViewById<TextView>(R.id.label_language)
        val creatorText = view.findViewById<TextView>(R.id.about_creator_text)

        creatorText.text = if (currentLang == "th") "พัฒนาโดย @iannnnn" else "Created by @iannnnn"
        labelLang.text = if (currentLang == "th") "ภาษา (Language)" else "Language"

        if (currentLang == "th") {
            toggleLang.check(R.id.btn_lang_th)
        } else {
            toggleLang.check(R.id.btn_lang_en)
        }

        btnLinkX.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/iannnnn"))
            startActivity(intent)
        }

        btnLinkGithub.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/iannnnn/anndoodle"))
            startActivity(intent)
        }

        toggleLang.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentLang = if (checkedId == R.id.btn_lang_th) "th" else "en"
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LANG_KEY, currentLang)
                    .apply()

                creatorText.text = if (currentLang == "th") "พัฒนาโดย @iannnnn" else "Created by @iannnnn"
                labelLang.text = if (currentLang == "th") "ภาษา (Language)" else "Language"
                updateUILanguage()
            }
        }

        bottomSheet.show()
    }

    private fun updateUILanguage() {
        applyBackgroundState()
        updateRatioButtonLabel(doodleView.canvasRatio)
    }

    private fun updateRatioButtonLabel(ratio: CanvasRatio) {
        val shortLabel = when (ratio) {
            CanvasRatio.SQUARE -> "1:1"
            CanvasRatio.STANDARD -> "4:3"
            CanvasRatio.WIDE -> "16:9"
            CanvasRatio.BANNER -> "2:1"
            CanvasRatio.FULLSCREEN -> if (currentLang == "th") "เต็มจอ" else "Full"
        }
        val prefix = if (currentLang == "th") "สัดส่วน" else "Ratio"
        btnRatio.text = "$prefix : $shortLabel"
    }

    private fun applyBackgroundState() {
        if (isTransparentBg) {
            rootLayout.setBackgroundColor(Color.TRANSPARENT)
            btnBgToggle.text = if (currentLang == "th") "พื้นหลัง : ใส" else "BG : Clear"
            btnBgToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CC1E1E1E"))
        } else {
            rootLayout.setBackgroundColor(Color.parseColor("#181818"))
            btnBgToggle.text = if (currentLang == "th") "พื้นหลัง : ดำ" else "BG : Dark"
            btnBgToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#383838"))
        }
    }

    private fun setupColorSwatches(container: LinearLayout) {
        // Slim color bar rectangle: width 13dp, height 22dp
        val density = resources.displayMetrics.density
        val widthPx = (13 * density).toInt()
        val heightPx = (22 * density).toInt()
        val marginPx = (1.5f * density).toInt()

        palette64.forEachIndexed { index, color ->
            val view = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                setOnClickListener {
                    selectedColorIndex = index
                    doodleView.selectColor(color)
                    updateEraserButtonUI(false)
                    highlightSelectedSwatch(index)
                }
            }
            swatchViews.add(view)
            container.addView(view)
        }
        highlightSelectedSwatch(0)
    }

    private fun highlightSelectedSwatch(selectedIndex: Int) {
        val density = resources.displayMetrics.density
        val strokePx = (2 * density).toInt()
        val radiusPx = 3f * density

        swatchViews.forEachIndexed { index, view ->
            val color = palette64[index]
            val isSelected = index == selectedIndex
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(color)
                if (isSelected) {
                    val borderColor = if (color == Color.WHITE) Color.parseColor("#00E5FF") else Color.WHITE
                    setStroke(strokePx, borderColor)
                } else {
                    setStroke(1, Color.parseColor("#33FFFFFF"))
                }
            }
            view.background = drawable
            view.scaleX = if (isSelected) 1.25f else 1.0f
            view.scaleY = if (isSelected) 1.18f else 1.0f
        }
    }

    private fun updateEraserButtonUI(active: Boolean) {
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

    private fun saveAndPinWidget() {
        val bmp = doodleView.getDoodleBitmap() ?: return
        btnDone.isEnabled = false

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
                    val msg = if (currentLang == "th") "อัปเดต Doodle บน Widget แล้ว!" else "Widget updated!"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(provider, null, null)
                        val msg = if (currentLang == "th") "กด 'เพิ่ม' เพื่อวางบนหน้าจอหลัก" else "Tap 'Add' to place on Home screen"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val msg = if (currentLang == "th") "บันทึกแล้ว!" else "Saved!"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }
}