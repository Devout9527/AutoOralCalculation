package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.PATTERN_NICKNAME
import cn.tinyhai.auto_oral_calculation.util.Clean
import cn.tinyhai.auto_oral_calculation.util.Common
import cn.tinyhai.auto_oral_calculation.util.logI
import java.nio.charset.Charset
import java.util.regex.Pattern

class NicknameHook : BaseHook() {
    override val name: String
        get() = "NicknameHook"

    override fun startHook() {
        Pattern::class.java.findConstructor(String::class.java, Int::class.javaPrimitiveType!!).before { param ->
            if (Common.removeRestrictionOnNickname && param.args[0] == PATTERN_NICKNAME) {
                param.args[0] = "[\\S]*"
            }
        }
        val gbk = Charset.forName("GBK")
        String::class.java.findMethod("getBytes", Charset::class.java).after { param ->
            if (!Common.doubleNicknameLength || param.args[0] != gbk) {
                return@after
            }
            (param.result as? ByteArray)?.let {
                param.result = it.copyOf(it.size / 2)
            }
        }

        // 去除改名时间冷却：hook UserVO.getNicknameUpdatedTime，返回很久前的时间（冷却早已过）
        runCatching {
            val userVoClass = findClass("com.yuanfudao.android.leo.user.data.UserVO")
            userVoClass.allMethod("getNicknameUpdatedTime").before { param ->
                val on: Boolean = runCatching { Clean.noNickCooldown }.getOrDefault(false)
                if (on) {
                    // 返回 2 天前，使 now - updatedTime > 86400000，绕过"1天内只能改一次"
                    param.result = System.currentTimeMillis() - 2 * 86400000L
                }
            }
        }.onFailure {
            logI("name cooldown hook fail: $it")
        }
    }
}
