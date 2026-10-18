package com.example.anndoodle

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

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
    private val PREF_CUSTOM_LEFT = "custom_rect_left"
    private val PREF_CUSTOM_TOP = "custom_rect_top"
    private val PREF_CUSTOM_RIGHT = "custom_rect_right"
    private val PREF_CUSTOM_BOTTOM = "custom_rect_bottom"
    private val PREF_VIEW_SCALE = "view_scale"
    private val PREF_VIEW_PAN_X = "view_pan_x"
    private val PREF_VIEW_PAN_Y = "view_pan_y"
    private val PREF_LANG_KEY = "app_language"
    private val PREF_BG_TRANSPARENT_KEY = "canvas_bg_transparent"
    private val PREF_SPEN_PRESSURE_KEY = "spen_pressure"

    private var currentLang = "en" // Default is English

    private lateinit var rootLayout: FrameLayout
    private lateinit var doodleView: DoodleView
    private lateinit var floatingToolbar: MaterialCardView
    private lateinit var btnDone: MaterialButton
    private lateinit var btnRatio: MaterialButton
    private lateinit var btnZoomReset: MaterialButton
    private lateinit var btnMore: MaterialButton
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnRedo: MaterialButton
    private lateinit var btnEraser: MaterialButton
    private lateinit var btnClear: MaterialButton

    private enum class SaveBgOption {
        TRANSPARENT, WHITE, BLACK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLang = prefs.getString(PREF_LANG_KEY, "en") ?: "en"
        isTransparentBg = prefs.getBoolean(PREF_BG_TRANSPARENT_KEY, true)

        rootLayout = findViewById(R.id.root_layout)
        doodleView = findViewById(R.id.doodle_view)
        doodleView.isPressureEnabled = prefs.getBoolean(PREF_SPEN_PRESSURE_KEY, true)

        btnDone = findViewById(R.id.btn_done)
        btnRatio = findViewById(R.id.btn_ratio)
        btnZoomReset = findViewById(R.id.btn_zoom_reset)
        btnMore = findViewById(R.id.btn_more)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnEraser = findViewById(R.id.btn_eraser)
        btnClear = findViewById(R.id.btn_clear)
        val sliderBrush = findViewById<Slider>(R.id.slider_brush)
        val colorContainer = findViewById<LinearLayout>(R.id.color_container)
        floatingToolbar = findViewById(R.id.floating_toolbar)
        val dragHandle = findViewById<View>(R.id.drag_handle_area)

        doodleView.onCanvasRectChanged = { rect, ratio ->
            prefs.edit()
                .putString(PREF_RATIO_KEY, ratio.key)
                .putFloat(PREF_CUSTOM_LEFT, rect.left)
                .putFloat(PREF_CUSTOM_TOP, rect.top)
                .putFloat(PREF_CUSTOM_RIGHT, rect.right)
                .putFloat(PREF_CUSTOM_BOTTOM, rect.bottom)
                .apply()
        }

        doodleView.onViewTransformChanged = { scale, panX, panY ->
            prefs.edit()
                .putFloat(PREF_VIEW_SCALE, scale)
                .putFloat(PREF_VIEW_PAN_X, panX)
                .putFloat(PREF_VIEW_PAN_Y, panY)
                .apply()
        }

        setupCanvasRatio(intent)
        setupColorSwatches(colorContainer)
        applyBackgroundState()

        btnDone.setOnClickListener {
            saveAndPinWidget()
        }

        btnRatio.setOnClickListener {
            showVisualRatioModal()
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

        setupDraggableToolbar(dragHandle, floatingToolbar)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupCanvasRatio(intent)
    }

    private fun getWidgetDimensionsDp(appWidgetManager: AppWidgetManager, appWidgetId: Int): Pair<Int, Int>? {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wDp: Int
        val hDp: Int
        if (isLandscape) {
            wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                .takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                .takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        } else {
            wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                .takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        }
        if (wDp > 0 && hDp > 0) return Pair(wDp, hDp)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedW = prefs.getInt("widget_${appWidgetId}_w", 0)
        val savedH = prefs.getInt("widget_${appWidgetId}_h", 0)
        if (savedW > 0 && savedH > 0) return Pair(savedW, savedH)
        return null
    }

    private fun setupCanvasRatio(launchIntent: Intent) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRatioKey = prefs.getString(PREF_RATIO_KEY, null)
        val savedRatio = CanvasRatio.fromKey(savedRatioKey)

        val savedLeft = prefs.getFloat(PREF_CUSTOM_LEFT, 0f)
        val savedTop = prefs.getFloat(PREF_CUSTOM_TOP, 0f)
        val savedRight = prefs.getFloat(PREF_CUSTOM_RIGHT, 0f)
        val savedBottom = prefs.getFloat(PREF_CUSTOM_BOTTOM, 0f)
        val hasSavedCustomRect = (savedRight - savedLeft) > 50f && (savedBottom - savedTop) > 50f

        val appWidgetManager = AppWidgetManager.getInstance(this)
        var appWidgetId = launchIntent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val sourceBounds = launchIntent.sourceBounds

        // 1. If launched directly by clicking a widget on Home Screen and launcher provided exact sourceBounds
        if (sourceBounds != null && sourceBounds.width() > 40 && sourceBounds.height() > 40) {
            doodleView.post {
                val loc = IntArray(2)
                doodleView.getLocationOnScreen(loc)
                val localRect = RectF(
                    (sourceBounds.left - loc[0]).toFloat(),
                    (sourceBounds.top - loc[1]).toFloat(),
                    (sourceBounds.right - loc[0]).toFloat(),
                    (sourceBounds.bottom - loc[1]).toFloat()
                )
                val widgetRatio = localRect.width() / localRect.height().coerceAtLeast(1f)
                val savedCustomRatio = if (hasSavedCustomRect) {
                    (savedRight - savedLeft) / (savedBottom - savedTop).coerceAtLeast(1f)
                } else -1f

                // If user already manually resized the canvas to a very similar ratio, keep their preferred rect;
                // otherwise snap to the exact widget bounds on screen!
                if (savedRatio == CanvasRatio.CUSTOM && hasSavedCustomRect && abs(savedCustomRatio - widgetRatio) < 0.08f) {
                    doodleView.setCustomCanvasRect(RectF(savedLeft, savedTop, savedRight, savedBottom), CanvasRatio.CUSTOM)
                } else {
                    doodleView.applyWidgetSourceBounds(localRect)
                }
                avoidToolbarOverlapWithCanvas()
            }
            return
        }

        // 2. If launched from a widget (or if a widget exists on Home Screen) without sourceBounds
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && savedRatioKey == null) {
            val existingIds = appWidgetManager.getAppWidgetIds(ComponentName(this, DoodleWidget::class.java))
            if (existingIds.isNotEmpty()) {
                appWidgetId = existingIds.first()
            }
        }

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val dims = getWidgetDimensionsDp(appWidgetManager, appWidgetId)
            if (dims != null) {
                val (wDp, hDp) = dims
                val widgetRatio = wDp.toFloat() / hDp.toFloat()
                val savedCustomRatio = if (hasSavedCustomRect) {
                    (savedRight - savedLeft) / (savedBottom - savedTop).coerceAtLeast(1f)
                } else -1f

                if (savedRatio == CanvasRatio.CUSTOM && hasSavedCustomRect && abs(savedCustomRatio - widgetRatio) < 0.08f) {
                    doodleView.setCustomCanvasRect(RectF(savedLeft, savedTop, savedRight, savedBottom), CanvasRatio.CUSTOM)
                } else {
                    doodleView.setWidgetAspectRatio(widgetRatio)
                }
                return
            }
        }

        // 3. Otherwise restore from SharedPreferences
        if (savedRatio == CanvasRatio.CUSTOM && hasSavedCustomRect) {
            doodleView.setCustomCanvasRect(RectF(savedLeft, savedTop, savedRight, savedBottom), CanvasRatio.CUSTOM)
        } else {
            doodleView.setCanvasRatio(savedRatio, recordUndo = false)
        }

        val savedScale = prefs.getFloat(PREF_VIEW_SCALE, 1f)
        val savedPanX = prefs.getFloat(PREF_VIEW_PAN_X, 0f)
        val savedPanY = prefs.getFloat(PREF_VIEW_PAN_Y, 0f)
        if (abs(savedScale - 1f) > 0.01f || abs(savedPanX) > 1f || abs(savedPanY) > 1f) {
            doodleView.post {
                doodleView.restoreViewTransform(savedScale, savedPanX, savedPanY)
            }
        }
    }

    /**
     * If the widget on the Home Screen is placed near the bottom of the screen where the floating
     * toolbar defaults, automatically move the floating toolbar up below the top bar so it doesn't cover the canvas.
     */
    private fun avoidToolbarOverlapWithCanvas() {
        floatingToolbar.post {
            val cRect = doodleView.canvasRect
            val tbTop = floatingToolbar.y
            val tbBottom = tbTop + floatingToolbar.height
            val overlapsVertically = cRect.bottom > tbTop - 12f && cRect.top < tbBottom + 12f
            if (overlapsVertically) {
                val density = resources.displayMetrics.density
                val topSafeY = 74f * density
                val parentH = (floatingToolbar.parent as? View)?.height ?: rootLayout.height
                // Place toolbar above the canvas if there's more room at the top, else at the very bottom
                val spaceAbove = cRect.top - topSafeY
                val spaceBelow = parentH - cRect.bottom
                val targetY = if (spaceAbove >= floatingToolbar.height || spaceAbove > spaceBelow) {
                    topSafeY
                } else {
                    (parentH - floatingToolbar.height - 12f * density).coerceAtLeast(topSafeY)
                }
                floatingToolbar.animate()
                    .y(targetY)
                    .setDuration(180)
                    .start()
            }
        }
    }

    private fun showVisualRatioModal() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.modal_aspect_ratio, null)
        bottomSheet.setContentView(view)

        val title = view.findViewById<TextView>(R.id.modal_ratio_title)
        title.text = if (currentLang == "th") {
            "สัดส่วน Widget (หรือลากมุมกรอบเพื่อปรับอิสระ)"
        } else {
            "Widget Aspect Ratio (or drag corners to resize)"
        }

        val cards = listOf(
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_square), CanvasRatio.SQUARE, view.findViewById<TextView>(R.id.sub_ratio_square)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_standard), CanvasRatio.STANDARD, view.findViewById<TextView>(R.id.sub_ratio_standard)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_wide), CanvasRatio.WIDE, view.findViewById<TextView>(R.id.sub_ratio_wide)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_banner), CanvasRatio.BANNER, view.findViewById<TextView>(R.id.sub_ratio_banner)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_5_4), CanvasRatio.PORTRAIT_5_4, view.findViewById<TextView>(R.id.sub_ratio_5_4)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_full), CanvasRatio.FULLSCREEN, view.findViewById<TextView>(R.id.sub_ratio_full))
        )

        // Subtitle localization
        if (currentLang == "th") {
            cards[0].third.text = "จัตุรัส (2x2)"
            cards[1].third.text = "คลาสสิก (4x3)"
            cards[2].third.text = "แนวกว้าง (4x2)"
            cards[3].third.text = "แถบยาว (5x2)"
            cards[4].third.text = "แนวตั้ง (4:5)"
            cards[5].third.text = "เต็มหน้าจอ"
        } else {
            cards[0].third.text = "Square (2x2)"
            cards[1].third.text = "Classic (4x3)"
            cards[2].third.text = "Wide (4x2)"
            cards[3].third.text = "Banner (5x2)"
            cards[4].third.text = "Portrait (4:5)"
            cards[5].third.text = "Full Screen"
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
                doodleView.setCanvasRatio(ratio, recordUndo = true)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_RATIO_KEY, ratio.key)
                    .apply()
                bottomSheet.dismiss()
            }
        }

        bottomSheet.show()
    }

    private fun showAboutModal() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.modal_about, null)
        bottomSheet.setContentView(view)

        val titleSettings = view.findViewById<TextView>(R.id.section_settings_title)
        val labelBg = view.findViewById<TextView>(R.id.label_bg_mode)
        val subBg = view.findViewById<TextView>(R.id.sub_bg_mode)
        val switchBg = view.findViewById<MaterialSwitch>(R.id.switch_bg_dark)

        val labelSpen = view.findViewById<TextView>(R.id.label_spen_pressure)
        val subSpen = view.findViewById<TextView>(R.id.sub_spen_pressure)
        val switchSpen = view.findViewById<MaterialSwitch>(R.id.switch_spen_pressure)

        val btnSaveImage = view.findViewById<MaterialButton>(R.id.btn_save_image)

        val labelLang = view.findViewById<TextView>(R.id.label_language)
        val toggleLang = view.findViewById<MaterialButtonToggleGroup>(R.id.toggle_language)
        val creatorText = view.findViewById<TextView>(R.id.about_creator_text)
        val btnLinkX = view.findViewById<MaterialButton>(R.id.btn_link_x)
        val btnLinkGithub = view.findViewById<MaterialButton>(R.id.btn_link_github)

        switchBg.isChecked = !isTransparentBg
        switchSpen.isChecked = doodleView.isPressureEnabled

        fun updateModalContent() {
            val isTh = currentLang == "th"
            titleSettings.text = if (isTh) "การตั้งค่าแคนวาสและปากกา" else "Canvas & Pen Settings"

            labelBg.text = if (isTh) "พื้นหลังสีดำทึบ" else "Solid Dark Background"
            subBg.text = if (switchBg.isChecked) {
                if (isTh) "โหมดสีดำทึบ (ไม่โปร่งใส)" else "Solid dark canvas (Opaque)"
            } else {
                if (isTh) "โหมดโปร่งใส (แนะนำสำหรับ Widget บนจอหลัก)" else "Transparent canvas (Ideal for widget)"
            }

            labelSpen.text = if (isTh) "น้ำหนักแรงกดปากกา (S-Pen)" else "S-Pen Pressure Sensitivity"
            subSpen.text = if (switchSpen.isChecked) {
                if (isTh) "เปิด: เส้นหนาตามแรงกด (Dynamic)" else "On: Dynamic stroke by pen pressure"
            } else {
                if (isTh) "ปิด: เส้นหนาสม่ำเสมอเท่ากัน (Mono-weight)" else "Off: Uniform stroke width (Mono-weight)"
            }

            btnSaveImage.text = if (isTh) "บันทึกรูปภาพลงแกลเลอรี" else "Save Image to Gallery"
            labelLang.text = if (isTh) "ภาษา (Language)" else "Language / ภาษา"
            creatorText.text = if (isTh) "พัฒนาโดย @iannnnn" else "Created by @iannnnn"
        }

        updateModalContent()

        if (currentLang == "th") {
            toggleLang.check(R.id.btn_lang_th)
        } else {
            toggleLang.check(R.id.btn_lang_en)
        }

        switchBg.setOnCheckedChangeListener { _, isChecked ->
            isTransparentBg = !isChecked
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BG_TRANSPARENT_KEY, isTransparentBg)
                .apply()
            applyBackgroundState()
            updateModalContent()

            // Immediately refresh any active widgets so background mode is reflected on Home Screen
            val appWidgetManager = AppWidgetManager.getInstance(this)
            val existingIds = appWidgetManager.getAppWidgetIds(ComponentName(this, DoodleWidget::class.java))
            for (id in existingIds) {
                DoodleWidget.updateAppWidget(this, appWidgetManager, id)
            }
        }

        switchSpen.setOnCheckedChangeListener { _, isChecked ->
            doodleView.isPressureEnabled = isChecked
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SPEN_PRESSURE_KEY, isChecked)
                .apply()
            updateModalContent()
        }

        btnSaveImage.setOnClickListener {
            bottomSheet.dismiss()
            showSaveImageDialog()
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
                updateModalContent()
            }
        }

        bottomSheet.show()
    }

    private fun applyBackgroundState() {
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        doodleView.isTransparentBg = isTransparentBg
    }

    private fun showSaveImageDialog() {
        val isTh = currentLang == "th"
        val title = if (isTh) "เลือกพื้นหลังของรูปภาพ" else "Choose Image Background"
        val options = if (isTh) {
            arrayOf("พื้นหลังใส (Transparent)", "พื้นหลังสีขาว (White)", "พื้นหลังสีดำ (Black)")
        } else {
            arrayOf("Transparent (PNG)", "Solid White", "Solid Black")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                val choice = when (which) {
                    0 -> SaveBgOption.TRANSPARENT
                    1 -> SaveBgOption.WHITE
                    else -> SaveBgOption.BLACK
                }
                saveImageToGallery(choice)
            }
            .setNegativeButton(if (isTh) "ยกเลิก" else "Cancel", null)
            .show()
    }

    private fun saveImageToGallery(choice: SaveBgOption) {
        val bmp = doodleView.getDoodleBitmap() ?: return
        val isTh = currentLang == "th"

        val width = bmp.width
        val height = bmp.height
        val finalBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBmp)

        when (choice) {
            SaveBgOption.TRANSPARENT -> canvas.drawColor(Color.TRANSPARENT)
            SaveBgOption.WHITE -> canvas.drawColor(Color.WHITE)
            SaveBgOption.BLACK -> canvas.drawColor(Color.parseColor("#181818"))
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)

        val filename = "anndoodle_${System.currentTimeMillis()}.png"

        lifecycleScope.launch(Dispatchers.IO) {
            var success = false
            try {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/anndoodle")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    success = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            } finally {
                finalBmp.recycle()
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    val msg = if (isTh) "บันทึกรูปลงใน Pictures/anndoodle แล้ว!" else "Saved to Pictures/anndoodle!"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    val msg = if (isTh) "ไม่สามารถบันทึกรูปได้" else "Failed to save image"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
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
        val bmpCopy = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return
        btnDone.isEnabled = false

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
                    for (id in existingIds) {
                        DoodleWidget.updateAppWidget(this@MainActivity, appWidgetManager, id)
                    }
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