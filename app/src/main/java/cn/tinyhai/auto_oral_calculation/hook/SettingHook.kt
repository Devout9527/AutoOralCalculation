package cn.tinyhai.auto_oral_calculation.hook

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
    }

    private fun hookRouterActivity() {
        val routerActivityClass = findClass(Classname.ROUTER_ACTIVITY)
        routerActivityClass.findMethod("onCreate", Bundle::class.java).before { param ->
            val activity = param.thisObject as Activity
            val intent = activity.intent ?: return@before
            shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
        }
    }

    private fun hookHomeActivity() {
        val homeActivityClass = findClass(Classname.HOME_ACTIVITY)
        homeActivityClass.findMethod("onResume").after { param ->
            if (shouldStartSettings) {
                val context = param.thisObject as Context
                val intent = Intent().apply {
                    component = ComponentName(HOST_PACKAGE_NAME, Classname.SETTINGS_ACTIVITY)
                }
                context.startActivity(intent)
            }
        }

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
                SettingsDialog(param.thisObject as Context).show()
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
            SettingsDialog(activity).show()
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

        val targetScoreEditView = EditText(activity).apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_SIGNED
            filters = arrayOf(InputFilter.LengthFilter(12))
            hint = "请输入刷取分数"
        }

        val currentScoreTextView = TextView(activity).apply {
            text = "当前分数：加载中"
            textSize = 16f
            setTextColor(Color.rgb(0x33, 0x33, 0x33))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        val supposeScoreTextView = TextView(activity).apply {
            text = "预计目标分数：加载中"
            textSize = 16f
            setTextColor(Color.rgb(0x33, 0x33, 0x33))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(currentScoreTextView)
            addView(supposeScoreTextView)
            addView(targetScoreEditView)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("自定义分数")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        targetScoreEditView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                val curScore = currentScore ?: return
                val obtainScore = targetScoreEditView.text.toString().toLongOrNull()
                    ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                positiveButton.isEnabled = obtainScore != null
                if (obtainScore != null) {
                    val suppose = curScore + obtainScore.toInt()
                    supposeScoreTextView.text = "预计目标分数：$suppose"
                } else {
                    supposeScoreTextView.text = "预计目标分数：$curScore"
                }
            }
        })

        fun updateCurrentScore() {
            getCurrentScore {
                currentScore = it
                currentScoreTextView.text = "当前分数：$currentScore"
                supposeScoreTextView.text = "预计目标分数：$currentScore"
            }
        }

        positiveButton.setOnClickListener {
            currentScore ?: return@setOnClickListener
            val obtainScore = targetScoreEditView.text.toString()
                .toLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            targetScoreEditView.text = null
            LegacyApiService.postSavedExp(obtainScore.toInt()) {
                it.onSuccess {
                    updateCurrentScore()
                }.onFailure {
                    logI(it)
                }
            }
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