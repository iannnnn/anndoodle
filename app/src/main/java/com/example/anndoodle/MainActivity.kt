package com.example.anndoodle

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    private val PREF_BG_TRANSPARENT_KEY = "canvas_bg_transparent"
    private val PREF_SPEN_PRESSURE_KEY = "spen_pressure"

    private var currentLang = "en" // Default is English

    private lateinit var rootLayout: FrameLayout
    private lateinit var doodleView: DoodleView
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
        val floatingToolbar = findViewById<MaterialCardView>(R.id.floating_toolbar)
        val dragHandle = findViewById<View>(R.id.drag_handle_area)

        setupCanvasRatio()
        setupColorSwatches(colorContainer)
        applyBackgroundState()

        btnDone.setOnClickListener {
            saveAndPinWidget()
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
                    detectedRatio < 0.85f -> CanvasRatio.PORTRAIT_5_4
                    else -> CanvasRatio.SQUARE
                }
            }
        }

        doodleView.setCanvasRatio(initialRatio)

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
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_5_4), CanvasRatio.PORTRAIT_5_4, view.findViewById<TextView>(R.id.sub_ratio_5_4)),
            Triple(view.findViewById<MaterialCardView>(R.id.card_ratio_full), CanvasRatio.FULLSCREEN, view.findViewById<TextView>(R.id.sub_ratio_full))
        )

        // Subtitle localization
        if (currentLang == "th") {
            cards[0].third.text = "จัตุรัส (2x2)"
            cards[1].third.text = "คลาสสิก (4x3)"
            cards[2].third.text = "แนวกว้าง (4x2)"
            cards[3].third.text = "แถบยาว (5x2)"
            cards[4].third.text = "แนวตั้ง (5:4)"
            cards[5].third.text = "เต็มหน้าจอ"
        } else {
            cards[0].third.text = "Square (2x2)"
            cards[1].third.text = "Classic (4x3)"
            cards[2].third.text = "Wide (4x2)"
            cards[3].third.text = "Banner (5x2)"
            cards[4].third.text = "Portrait (5:4)"
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
                doodleView.setCanvasRatio(ratio)
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
        if (isTransparentBg) {
            rootLayout.setBackgroundColor(Color.TRANSPARENT)
        } else {
            rootLayout.setBackgroundColor(Color.parseColor("#181818"))
        }
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