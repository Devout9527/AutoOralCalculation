package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

abstract class BaseHook {

    abstract val name: String

    private fun XC_MethodHook.MethodHookParam.runBlockCatching(block: (XC_MethodHook.MethodHookParam) -> Unit) {
        kotlin.runCatching {
            block(this)
        }.onFailure {
            logI("failure in $method")
            logI(it)
        }
    }

    fun findClass(className: String): Class<*> {
        return XposedHelpers.findClass(className, lp.classLoader)
    }

    fun Member.before(block: (XC_MethodHook.MethodHookParam) -> Unit): Unhook {
        return XposedBridge.hookMethod(this, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.runBlockCatching(block)
            }
        })
    }

    fun Member.after(block: (XC_MethodHook.MethodHookParam) -> Unit): Unhook {
        return XposedBridge.hookMethod(this, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.runBlockCatching(block)
            }
        })
    }

    fun List<Member>.before(block: (XC_MethodHook.MethodHookParam) -> Unit): List<Unhook> {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.runBlockCatching(block)
            }
        }
        return map {
            XposedBridge.hookMethod(it, callback)
        }
    }

    fun List<Member>.after(block: (XC_MethodHook.MethodHookParam) -> Unit): List<Unhook> {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.runBlockCatching(block)
            }
        }
        return map {
            XposedBridge.hookMethod(it, callback)
        }
    }

    fun Class<*>.allMethod(name: String): List<Member> {
        return declaredMethods.filter { it.name == name }
    }

    fun Class<*>.findConstructor(vararg parameters: Any): Constructor<*> {
        return XposedHelpers.findConstructorExact(this, *parameters)
    }

    fun Class<*>.findMethod(name: String, vararg parameters: Any): Method {
        return XposedHelpers.findMethodExact(this, name, *parameters)
    }

    fun startHookCatching(): Result<Unit> {
        return kotlin.runCatching {
            startHook()
        }.onFailure {
            logI("failure in $name >>>>>>")
            logI(it)
            logI("failure in $name <<<<<<")
        }
    }

    protected abstract fun startHook()

    companion object {
        lateinit var lp: XC_LoadPackage.LoadPackageParam

        fun startHook(lp: XC_LoadPackage.LoadPackageParam) {
            this.lp = lp
            PracticeHook().startHookCatching()
            RecognizerHook().startHookCatching()
            WebViewHook().startHookCatching()
            SettingHook().startHookCatching()
            NicknameHook().startHookCatching()
        }
    }
}