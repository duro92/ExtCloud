package com.oploverz

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object StarPopupHelper {

    private const val TAG = "StarPopupHelper"
    private const val PREFS_NAME = "ExtCloudsPrefs"
    private const val KEY_SHOWN_POPUP = "shown_welcome_popup"

    fun showStarPopupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Jika sudah pernah tampil, jangan tampilkan lagi
        if (prefs.getBoolean(KEY_SHOWN_POPUP, false)) {
            return
        }

        // Simpan status sudah tampil
        prefs.edit().putBoolean(KEY_SHOWN_POPUP, true).apply()

        Handler(Looper.getMainLooper()).post {
            try {
                val activity = context as? Activity ?: return@post
                showStyledDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing popup: ${e.message}")
            }
        }
    }

    private fun showStyledDialog(activity: Activity) {

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24, activity), dp(20, activity), dp(24, activity), dp(20, activity))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }

        // ===== TITLE =====
        val titleView = TextView(activity).apply {
            text = "🎬 Selamat Menonton Film Secara Gratis"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16, activity))
        }
        layout.addView(titleView)

        // ===== MESSAGE =====
        val messageView = TextView(activity).apply {
            text = "Selamat menikmati film dan serial favorit Anda secara gratis.\n\nSemoga pengalaman menonton Anda menyenangkan dan lancar 🍿"
            setTextColor(Color.parseColor("#b0b0b0"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16, activity))
            setLineSpacing(dp(4, activity).toFloat(), 1f)
        }
        layout.addView(messageView)

        // ===== SAWERIA TEXT =====
        val saweriaView = TextView(activity).apply {
            text = "Dukung kami di: https://saweria.co/duro92"
            setTextColor(Color.parseColor("#74b9ff"))
            textSize = 14f
            gravity = Gravity.CENTER
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 0, 0, dp(24, activity))

            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/duro92"))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening Saweria link: ${e.message}")
                }
            }
        }
        layout.addView(saweriaView)

        // ===== BUTTON =====
        val button = Button(activity).apply {
            text = "Mulai Menonton"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            background = createRoundedBackground(Color.parseColor("#6c5ce7"))
            setPadding(dp(20, activity), dp(12, activity), dp(20, activity), dp(12, activity))
            gravity = Gravity.CENTER
        }
        layout.addView(button)

        val dialog = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        button.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun dp(value: Int, context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun createRoundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f
        }
    }
}
