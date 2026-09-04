package cn.tinyhai.auto_oral_calculation.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.BuildConfig
import cn.tinyhai.auto_oral_calculation.MODULE_PREFS_NAME
import cn.tinyhai.auto_oral_calculation.util.UiKit
import cn.tinyhai.auto_oral_calculation.util.openGithub

/** 卡片式设置页（AlertDialog 承载，分组与旧版 host_settings 完全一致） */
class SettingsScreenView private constructor(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
    private val dp = activity.resources.displayMetrics.density

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; background = UiKit.subCard(activity)
    }
    private fun text(str: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply { this.text = str; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD) }
    private fun gap(h: Int) = View(activity).apply { minimumHeight = h }
    private fun title(str: String): TextView = text(str, 13f, UiKit.textSub).apply {
        setPadding((4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
    }

    private fun switchRow(label: String, key: String, def: Boolean): View {
        val dot = TextView(activity).apply {
            textSize = 11f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (24 * dp).toInt())
        }
        fun refresh() {
            val on = prefs.getBoolean(key, def)
            dot.background = if (on) UiKit.accentBar(activity) else UiKit.solid(Color.rgb(225, 228, 234), 12f, activity)
            dot.text = if (on) "开" else "关"
            dot.setTextColor(if (on) Color.WHITE else UiKit.textSub)
        }
        refresh()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
            addView(text(label, 15f, UiKit.textMain), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dot)
            setOnClickListener {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply(); refresh()
            }
        }
        return card().apply { addView(row) }
    }

    private fun inputRow(label: String, key: String, def: String, hint: String, suffix: String = ""): View {
        val valueTv = text((prefs.getString(key, def) ?: def) + suffix, 14f, UiKit.textSub)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
            addView(text(label, 15f, UiKit.textMain), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueTv)
            setOnClickListener {
                val edit = UiKit.input(activity, hint).apply { setText(prefs.getString(key, def)) }
                android.app.AlertDialog.Builder(activity)
                    .setTitle(label).setView(edit)
                    .setPositiveButton("确定") { _, _ ->
                        prefs.edit().putString(key, edit.text.toString()).apply()
                        valueTv.text = edit.text.toString() + suffix
                    }.setNegativeButton("取消", null).show()
            }
        }
        return card().apply { addView(row) }
    }

    private fun selectRow(label: String, key: String, def: String, options: List<String>): View {
        val valueTv = text(options.getOrElse(prefs.getString(key, def)?.toIntOrNull() ?: 0) { options[0] }, 14f, UiKit.textSub)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
            addView(text(label, 15f, UiKit.textMain), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueTv)
            setOnClickListener {
                val names = options.toTypedArray()
                android.app.AlertDialog.Builder(activity)
                    .setTitle(label).setItems(names) { _, which ->
                        prefs.edit().putString(key, which.toString()).apply()
                        valueTv.text = names[which]
                    }.show()
            }
        }
        return card().apply { addView(row) }
    }

    private fun linkRow(label: String, right: String, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
            addView(text(label, 15f, UiKit.textMain), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(right, 14f, UiKit.textSub))
            if (onClick != null) setOnClickListener { onClick() }
        }
        return card().apply { addView(row) }
    }

    fun build(): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        // 通用
        container.addView(title("通用"))
        container.addView(switchRow("一切输入视为正确答案(口算/手写)", "always_true_oral", true))
        container.addView(gap((8 * dp).toInt()))
        container.addView(switchRow("双倍昵称长度", "double_nickname_length", true))
        container.addView(gap((8 * dp).toInt()))
        container.addView(switchRow("解除昵称字符限制", "remove_restriction_on_nickname", false))
        // 练习
        container.addView(title("练习"))
        container.addView(switchRow("自动上分", "auto_honor", false))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("上分并发线程数", "honor_threads", "3", "1-10，默认3", " 线程"))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("每线程拉题间隔", "honor_req_interval", "1000", "毫秒，200-10000，默认1000", " ms"))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("每题模拟答题时间", "honor_answer_time", "200", "毫秒，50-2000，默认200", " ms"))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("免弹框自动刷次数", "honor_auto_count", "0", "0=每次弹框询问"))
        // PK
        container.addView(title("PK"))
        container.addView(switchRow("PK包赢", "pk_win", false))
        container.addView(gap((8 * dp).toInt()))
        container.addView(selectRow("自动答题配置", "auto_answer_config", "0", listOf("标准", "极速", "自定义")))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("自定义答题", "custom_answer_config", "", "请输入自定义的js代码"))
        container.addView(gap((8 * dp).toInt()))
        container.addView(switchRow("极速模式稳赢", "quick_mode_must_win", false))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("极速模式模拟答题间隔", "quick_mode_interval", "200", "单位毫秒，默认值200", " ms"))
        container.addView(gap((8 * dp).toInt()))
        container.addView(switchRow("循环PK", "pk_cyclic", false))
        container.addView(gap((8 * dp).toInt()))
        container.addView(inputRow("循环时间间隔", "pk_cyclic_interval", "1500", "单位毫秒，默认值1500", " ms"))
        // 净化
        container.addView(title("净化"))
        container.addView(switchRow("去除广告", "remove_ads", false))
        container.addView(gap((8 * dp).toInt()))
        container.addView(switchRow("去除改名时间冷却", "no_nick_cooldown", false))
        // Debug
        container.addView(title("Debug"))
        container.addView(switchRow("DEBUG", "debug", false))
        // 关于
        container.addView(title("关于"))
        container.addView(linkRow("Github", "打开 ›") { activity.openGithub() })
        container.addView(gap((8 * dp).toInt()))
        container.addView(linkRow("版本", BuildConfig.VERSION_NAME))

        return ScrollView(activity).apply { addView(container) }
    }

    companion object {
        fun showAsScreen(activity: Activity) {
            val view = SettingsScreenView(activity).build()
            val dialog = android.app.AlertDialog.Builder(activity)
                .setView(view)
                .create()
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt())
            }
            dialog.show()
        }
    }
}
