package cn.tinyhai.auto_oral_calculation.util

import android.app.AndroidAppHelper
import android.content.Context
import cn.tinyhai.auto_oral_calculation.MODULE_PREFS_NAME
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode

private val currentContext by lazy { AndroidAppHelper.currentApplication() }

private val modulePrefs by lazy {
    currentContext.getSharedPreferences(MODULE_PREFS_NAME, Context.MODE_PRIVATE)
}

object Common {
    // 一切输入视为正确答案(口算/手写识别)
    val alwaysTrue get() = modulePrefs.getBoolean(moduleStringRes.KEY_ALWAYS_TRUE_ORAL, true)
    val doubleNicknameLength get() = modulePrefs.getBoolean(moduleStringRes.KEY_DOUBLE_NICKNAME_LENGTH, true)
    val removeRestrictionOnNickname get() = modulePrefs.getBoolean(moduleStringRes.KEY_REMOVE_RESTRICTION_ON_NICKNAME, false)
}

object Practice {
    val autoHonor get() = modulePrefs.getBoolean(moduleStringRes.KEY_AUTO_HONOR, false)
}

object PK {
    val pkWin get() = modulePrefs.getBoolean(moduleStringRes.KEY_PK_WIN, false)
    val mode: AutoAnswerMode
        get() {
            val index = runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_AUTO_ANSWER_CONFIG, "")!!)
            }.getOrElse { 0 }
            return AutoAnswerMode.entries[index]
        }
    val customJs get() = modulePrefs.getString(moduleStringRes.KEY_CUSTOM_ANSWER_CONFIG, "")!!
    val quickModeMustWin
        get() = mode == AutoAnswerMode.QUICK && modulePrefs.getBoolean(
            moduleStringRes.KEY_QUICK_MODE_MUST_WIN, false
        )
    val quickModeInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_QUICK_MODE_INTERVAL, "")!!)
            }.getOrElse { 200 }
        }
    val pkCyclic
        get() = mode in arrayOf(
            AutoAnswerMode.STANDARD,
            AutoAnswerMode.QUICK
        ) && modulePrefs.getBoolean(moduleStringRes.KEY_PK_CYCLIC, false)
    val pkCyclicInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(modulePrefs.getString(moduleStringRes.KEY_PK_CYCLIC_INTERVAL, "")!!)
            }.getOrElse { 1500 }
        }
}

object Debug {
    val debug
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_DEBUG, false)
}