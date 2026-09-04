package cn.tinyhai.auto_oral_calculation.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

object UiKit {
    val textMain = Color.rgb(24, 28, 36)
    val textSub = Color.rgb(140, 148, 160)
    val accentStart = Color.rgb(52, 120, 246)
    val accentEnd = Color.rgb(64, 158, 255)

    fun solid(c: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable().apply { setColor(c); cornerRadius = radiusDp * context.resources.displayMetrics.density }

    fun gradient(start: Int, end: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
            .apply { cornerRadius = radiusDp * context.resources.displayMetrics.density }

    fun whiteCard(context: Context): GradientDrawable = solid(Color.WHITE, 20f, context)

    fun subCard(context: Context): GradientDrawable = solid(Color.rgb(245, 247, 250), 14f, context)

    fun accentBar(context: Context): GradientDrawable = gradient(accentStart, accentEnd, 10f, context)

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 18f; setTextColor(textMain); setTypeface(null, Typeface.BOLD)
    }

    fun sub(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(textSub)
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(textSub)
    }

    fun value(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text; textSize = 15f; setTextColor(textMain); setTypeface(null, Typeface.BOLD)
    }

    fun gap(context: Context, heightPx: Int): View = View(context).apply { minimumHeight = heightPx }

    fun input(context: Context, hint: String): EditText = EditText(context).apply {
        this.hint = hint; textSize = 15f; setTextColor(textMain); setHintTextColor(textSub)
        background = solid(Color.rgb(235, 238, 244), 12f, context)
        val p = (12 * context.resources.displayMetrics.density).toInt()
        setPadding(p, p * 3 / 4, p, p * 3 / 4)
    }

    /** 统一弹窗外壳：白卡片 + 可设宽度；widthDp<=0 表示全屏宽 */
    fun styleDialog(dialog: Dialog, context: Context, widthDp: Float = 300f) {
        dialog.window?.apply {
            setBackgroundDrawable(whiteCard(context))
            val w = if (widthDp <= 0) ViewGroup.LayoutParams.MATCH_PARENT
            else (widthDp * context.resources.displayMetrics.density).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
