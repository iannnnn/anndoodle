package com.example.anndoodle

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import java.io.File

class DoodleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val minW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            .takeIf { it > 0 } ?: newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (minW > 0 && maxH > 0) {
            context.getSharedPreferences("anndoodle_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("widget_${appWidgetId}_w", minW)
                .putInt("widget_${appWidgetId}_h", maxH)
                .apply()
        }
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_doodle)
            val file = File(context.filesDir, "doodle.png")
            val prefs = context.getSharedPreferences("anndoodle_prefs", Context.MODE_PRIVATE)
            val isTransparentBg = prefs.getBoolean("canvas_bg_transparent", true)

            val decoded = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            val widgetBitmap: Bitmap = if (decoded != null) {
                val maxDim = 1000
                val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height, 1f)
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)

                val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outBmp)
                if (!isTransparentBg) {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#181818")
                        style = Paint.Style.FILL
                    }
                    val radius = minOf(w, h) * 0.04f
                    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, bgPaint)
                }
                val paint = Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(decoded, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
                decoded.recycle()
                outBmp
            } else {
                // Ensure the ImageView always has content so launcher populates sourceBounds on click
                val fallback = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                if (!isTransparentBg) {
                    fallback.eraseColor(Color.parseColor("#181818"))
                }
                fallback
            }

            views.setImageViewBitmap(R.id.widget_image, widgetBitmap)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_image, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

