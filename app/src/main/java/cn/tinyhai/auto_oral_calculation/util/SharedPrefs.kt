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

object Clean {
    val removeAds get() = modulePrefs.getBoolean(moduleStringRes.KEY_REMOVE_ADS, false)
    val noNickCooldown get() = modulePrefs.getBoolean(moduleStringRes.KEY_NO_NICK_COOLDOWN, false)
}

object Honor {
    // 并发线程数（1~10）
    val threads: Int
        get() = runCatching {
            modulePrefs.getString(moduleStringRes.KEY_HONOR_THREADS, "")?.toIntOrNull()?.coerceIn(1, 10) ?: 3
        }.getOrDefault(3)
    // 每 worker 拉题间隔 ms（200~10000）
    val reqInterval: Long
        get() = runCatching {
            modulePrefs.getString(moduleStringRes.KEY_HONOR_REQ_INTERVAL, "")?.toLongOrNull()?.coerceIn(200, 10000) ?: 1000L
        }.getOrDefault(1000L)
    // 每题模拟答题时间 ms 范围下限（50~2000）
    val answerTime: Int
        get() = runCatching {
            modulePrefs.getString(moduleStringRes.KEY_HONOR_ANSWER_TIME, "")?.toIntOrNull()?.coerceIn(50, 2000) ?: 200
        }.getOrDefault(200)
    // 免弹框自动刷的次数（0=弹框询问）
    val autoCount: Int
        get() = runCatching {
            modulePrefs.getString(moduleStringRes.KEY_HONOR_AUTO_COUNT, "")?.toIntOrNull()?.coerceIn(0, 999999) ?: 0
        }.getOrDefault(0)
}

object Debug {
    val debug
        get() = modulePrefs.getBoolean(moduleStringRes.KEY_DEBUG, false)
}