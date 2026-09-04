package cn.tinyhai.auto_oral_calculation.hook

import android.app.Dialog
import android.view.Window
import android.view.ViewGroup

import cn.tinyhai.auto_oral_calculation.util.UiKit

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.HOST_PACKAGE_NAME
import cn.tinyhai.auto_oral_calculation.KEY_START_SETTINGS
import cn.tinyhai.auto_oral_calculation.api.LegacyApiService
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import cn.tinyhai.auto_oral_calculation.ui.SettingsDialog
import cn.tinyhai.auto_oral_calculation.util.Clean
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.mainHandler
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor

class SettingHook : BaseHook() {

    override val name: String
        get() = "SettingHook"

    private var shouldStartSettings = false

    override fun startHook() {
        hookSettingActivity()
        hookRouterActivity()
        hookHomeActivity()
        hookRemoveAds()
    }

    private fun hookRouterActivity() {
        val routerActivityClass = findClass(Classname.ROUTER_ACTIVITY)
        routerActivityClass.findMethod("onCreate", Bundle::class.java).before { param ->
            val activity = param.thisObject as Activity
            val intent = activity.intent ?: return@before
            shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
        }
    }

    // 净化：隐藏/折叠广告横幅视图（FireworkBanner 等；只 GONE 不改测量，避免崩溃）
    private fun hookRemoveAds() {
        val adsOn = { runCatching { Clean.removeAds }.getOrDefault(false) }
        val adViews = arrayOf(
            "com.fenbi.android.firework.banner.FireworkBannerView",
            "com.fenbi.android.leo.ui.firework.AutoShowFireworkBannerView",
            "com.fenbi.android.leo.imgsearch.sdk.ui.LeoFireworkBannerView"
        )
        for (cn in adViews) {
            runCatching {
                val c = findClass(cn)
                c.allMethod("onAttachedToWindow").after { param ->
                    runCatching { if (adsOn()) { (param.thisObject as? View)?.visibility = View.GONE } }
                }
                logI("净化: 已hook去广告 $cn")
            }.onFailure { logI(it) }
        }
    }

    private fun hookHomeActivity() {
        val homeActivityClass = findClass(Classname.HOME_ACTIVITY)

        val apiServiceCompanionClass = findClass("${Classname.ORAL_API_SERVICE}\$a")
        val legacyApiServiceCompanionClass = findClass("${Classname.LEGACY_API_SERVICE}\$a")
        val gsonClass = findClass(Classname.GSON)
        val unhooks = arrayOf<Unhook?>(null)
        homeActivityClass.findMethod("onResume").after {
            runCatching {
                val apiServiceCompanion =
                    XposedHelpers.getStaticObjectField(apiServiceCompanionClass, "a")
                val apiService = XposedHelpers.callMethod(apiServiceCompanion, "a")
                OralApiService.init(apiService)
                val legacyApiServiceCompanion =
                    XposedHelpers.getStaticObjectField(legacyApiServiceCompanionClass, "a")
                val legacyApiService = XposedHelpers.callMethod(legacyApiServiceCompanion, "a")
                val gson = gsonClass.getDeclaredConstructor().newInstance()
                LegacyApiService.init(legacyApiService, gson)
            }.onFailure {
                logI(it)
            }
            unhooks.forEach { it?.unhook() }
        }.also { unhooks[0] = it }
    }

    private fun hookSettingActivity() {
        val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)
        val settingsActivityClass = findClass(Classname.SETTINGS_ACTIVITY)
        val sectionItemClass = findClass(Classname.SECTION_ITEM)
        // 新版 LeoSectionItemCell 已无 (Context) 构造器，只剩 (Context, AttributeSet) / (Context, AttributeSet, int)
        val sectionItemConstructor =
            sectionItemClass.getConstructor(Context::class.java, AttributeSet::class.java)
        settingsActivityClass.findMethod("onCreate", Bundle::class.java).after { param ->
            val activity = param.thisObject as Activity
            val scope =
                XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
            val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")
            LegacyApiService.setup(coroutineContext)

            addSectionItems(activity, sectionItemConstructor)
        }

        settingsActivityClass.findMethod("onResume").after { param ->
            if (shouldStartSettings) {
                shouldStartSettings = false
                cn.tinyhai.auto_oral_calculation.ui.SettingsScreenView.showAsScreen(param.thisObject as Activity)
            }
        }
    }

    private fun addSectionItems(activity: Activity, sectionItemConstructor: Constructor<*>) {
        val appWidgetId = activity.resources.getIdentifier(
            "cell_appwidget",
            "id",
            activity.packageName
        )
        val appWidget = activity.findViewById<View>(appWidgetId) ?: return
        // 新版设置页结构变化，cell 的父级未必直接是 LinearLayout，向上找最近的 LinearLayout
        val container = findParentLinearLayout(appWidget)
            ?: (appWidget.parent as? LinearLayout)
            ?: return
        val labelId =
            activity.resources.getIdentifier("text_label", "id", activity.packageName)

        val customScoreSectionItem =
            buildCustomScoreSectionItem(activity, sectionItemConstructor, labelId)
        container.addView(customScoreSectionItem, 0)
        val moduleSectionItem = buildModuleSectionItem(activity, sectionItemConstructor, labelId)
        container.addView(moduleSectionItem, 0)
    }

    private fun findParentLinearLayout(view: View): LinearLayout? {
        var p: android.view.ViewParent? = view.parent
        while (p != null) {
            if (p is LinearLayout) return p
            p = (p as? View)?.parent
        }
        return null
    }

    private fun buildModuleSectionItem(
        activity: Activity,
        itemConstructor: Constructor<*>,
        labelId: Int
    ): View {
        val item = itemConstructor.newInstance(activity, null) as View
        return buildSectionItem(item, labelId, "口算糕手设置") {
            cn.tinyhai.auto_oral_calculation.ui.SettingsScreenView.showAsScreen(activity)
        }
    }

    private fun buildSectionItem(
        item: View,
        labelId: Int,
        label: String,
        onClick: (() -> Unit)? = null
    ): View {
        val labelTv = item.findViewById<TextView>(labelId)
        if (labelTv != null) {
            labelTv.text = label
        } else {
            // 新版 LeoSectionItemCell 文本控件另有其名，用 setLableText(String) 兜底
            runCatching {
                item::class.java.getMethod("setLableText", String::class.java)
                    .invoke(item, label)
            }.onFailure { logI(it) }
        }
        item.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        onClick?.let {
            item.setOnClickListener { onClick() }
        }
        return item
    }

    private fun buildCustomScoreSectionItem(
        activity: Activity,
        itemConstructor: Constructor<*>,
        labelId: Int
    ): View {
        val item = itemConstructor.newInstance(activity, null) as View
        return buildSectionItem(item, labelId, "自定义分数") {
            showCustomScoreDialog(activity)
        }
    }

    private fun showCustomScoreDialog(activity: Activity) {
        var currentScore: Int? = null
        val dp = activity.resources.displayMetrics.density

        val targetScoreEditView = UiKit.input(activity, "请输入刷取分数").apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        fun infoCard(label: String, init: String): Pair<LinearLayout, TextView> {
            val v = UiKit.value(activity, init)
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiKit.subCard(activity)
                val p = (12 * dp).toInt()
                setPadding(p, p * 2 / 3, p, p * 2 / 3)
                addView(UiKit.label(activity, label))
                addView(v)
            }
            return box to v
        }
        val (curCard, currentScoreTextView) = infoCard("当前分数", "加载中")
        val (supCard, supposeScoreTextView) = infoCard("预计目标分数", "加载中")

        lateinit var dialogRef: Dialog
        fun updateCurrentScore() {
            getCurrentScore {
                currentScore = it
                currentScoreTextView.text = "$it"
                supposeScoreTextView.text = "$it"
            }
        }
        val confirmBtn = TextView(activity).apply {
            text = "确认刷分"; gravity = android.view.Gravity.CENTER
            textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); background = UiKit.accentBar(activity)
            val p = (12 * dp).toInt(); setPadding(p, p * 2 / 3, p, p * 2 / 3)
            setOnClickListener {
                val obtainScore = targetScoreEditView.text.toString().toLongOrNull()
                    ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) ?: return@setOnClickListener
                targetScoreEditView.text = null
                LegacyApiService.postSavedExp(obtainScore.toInt()) {
                    it.onSuccess { updateCurrentScore() }.onFailure { t -> logI(t) }
                }
            }
        }
        val cancelBtn = TextView(activity).apply {
            text = "取消"; gravity = android.view.Gravity.CENTER
            textSize = 15f; setTextColor(UiKit.textSub); background = UiKit.subCard(activity)
            val p = (12 * dp).toInt(); setPadding(p, p * 2 / 3, p, p * 2 / 3)
            setOnClickListener { dialogRef.dismiss() }
        }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (10 * dp).toInt() })
            addView(confirmBtn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * dp).toInt()
            setPadding(pad, (22 * dp).toInt(), pad, pad)
            setBackgroundColor(Color.WHITE)
            addView(UiKit.title(activity, "自定义分数"))
            addView(UiKit.sub(activity, "直接上报经验，服务端可能有单次/每日限制"))
            addView(View(activity), LinearLayout.LayoutParams(1, (14 * dp).toInt()))
            addView(curCard)
            addView(View(activity), LinearLayout.LayoutParams(1, (8 * dp).toInt()))
            addView(supCard)
            addView(View(activity), LinearLayout.LayoutParams(1, (14 * dp).toInt()))
            addView(targetScoreEditView)
            addView(View(activity), LinearLayout.LayoutParams(1, (16 * dp).toInt()))
            addView(btnRow)
        }
        confirmBtn.isEnabled = false; confirmBtn.alpha = 0.5f
        targetScoreEditView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val cur = currentScore
                val v = s.toString().toLongOrNull()
                val ok = cur != null && v != null && v != 0L
                confirmBtn.isEnabled = ok; confirmBtn.alpha = if (ok) 1f else 0.5f
                if (cur != null) {
                    val suppose = if (v != null) cur + v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() else cur
                    supposeScoreTextView.text = "$suppose"
                }
            }
        })
        dialogRef = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setLayout((320f * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            show()
        }
        updateCurrentScore()
    }

    private fun getCurrentScore(onResult: (Int) -> Unit) {
        LegacyApiService.getCurrentUserExp {
            it.onSuccess { data ->
                val curWeekScore = XposedHelpers.getIntField(data, "curWeekScore")
                logI("curWeekScore: $curWeekScore")
                mainHandler.post {
                    onResult(curWeekScore)
                }
            }.onFailure { th ->
                logI(th)
            }
        }
    }
}