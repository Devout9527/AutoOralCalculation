package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.util.Common

class RecognizerHook : BaseHook() {

    override val name: String
        get() = "RecognizerHook"

    override fun startHook() {
        // 新版(3.140.1) MathScriptRecognizer 被混淆为 ar.d。ScriptBoard 的字段名也被混淆（无 recognizer），
        // 因此通过「类型里含 a(int, List, List) 方法」动态定位识别器类，避免硬编码变量/类名
        val scriptBoardClass = findClass("com.fenbi.android.leo.exercise.view.ScriptBoard")
        val mathScriptRecognizerClass = scriptBoardClass.declaredFields
            .firstOrNull {
                runCatching {
                    it.type.declaredMethods.any { m ->
                        m.name == "a" && m.parameterCount == 3 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == List::class.java &&
                            m.parameterTypes[2] == List::class.java
                    }
                }.getOrDefault(false)
            }?.type
            ?: return
        mathScriptRecognizerClass.findMethod(
            "a",
            Int::class.javaPrimitiveType!!,
            List::class.java,
            List::class.java
        ).before { param ->
            if (!Common.alwaysTrue) {
                return@before
            }
            val answers = param.args[2] as List<*>
            param.result = if (answers.isNotEmpty()) {
                answers[0].toString()
            } else {
                ""
            }
        }
    }
}