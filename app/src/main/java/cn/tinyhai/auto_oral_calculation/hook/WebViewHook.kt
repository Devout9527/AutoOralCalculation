package cn.tinyhai.auto_oral_calculation.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.XposedInit.Companion.moduleRes
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.hook.PracticeHook
import cn.tinyhai.auto_oral_calculation.util.Common
import cn.tinyhai.auto_oral_calculation.util.Debug
import cn.tinyhai.auto_oral_calculation.util.PK
import cn.tinyhai.auto_oral_calculation.util.Practice
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.pathPoints
import cn.tinyhai.auto_oral_calculation.util.toJSONArray
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class WebViewHook : BaseHook() {

    override val name: String
        get() = "WebViewHook"

    private val standardJs by lazy {
        moduleRes.assets.open("js/standard.js")
            .bufferedReader().use { it.readText() }
    }

    private val quickJs by lazy {
        moduleRes.assets.open("js/quick.js")
            .bufferedReader().use { it.readText() }
    }

    private val cyclicJs by lazy {
        moduleRes.assets.open("js/cyclic.js")
            .bufferedReader().use { it.readText() }
    }

    // 诗词PK / 单词PK 实时自动答题
    private val pkBattleJs by lazy {
        moduleRes.assets.open("js/pk-battle.js")
            .bufferedReader().use { it.readText() }
    }

    private val pkBattlePageLoaded = AtomicBoolean(false)
    private val pkPageLoaded = AtomicBoolean(false)

    private val resultPageLoaded = AtomicBoolean(false)

    private val appropriateCostTime = AtomicLong(0L)

    private var webViewRef: WeakReference<View>? = null

    private val webView get() = webViewRef?.get()

    private var loadUrl: Method? = null

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    @JavascriptInterface
    fun targetCostTime(costTime: Long) {
        appropriateCostTime.set(costTime - 100)
    }

    @JavascriptInterface
    fun quickModeAwait(questionCnt: Int, callback: String) {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        val appropriateCostTime = appropriateCostTime.get()
        val waitTime = if (PK.quickModeMustWin && appropriateCostTime > 0) {
            appropriateCostTime
        } else {
            getSimulateCostTime(questionCnt).coerceAtLeast(questionCnt * 200L)
        }
        logI("waitTime: $waitTime, callback: $callback")
        webView.postDelayed({
            injectJsCode("window.$callback && window.$callback();", loadUrl, webView)
        }, waitTime)
    }

    private fun hookConsoleLog() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();")
        )
    }

    override fun startHook() {
        val baseWebAppClass = findClass(Classname.BASE_WEB_APP)
        val simpleWebAppFireworkClass =
            findClass(Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY)
        val webViewField =
            simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }

        loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        addJavascriptInterface?.let(::hookAddJavascriptInterface)

        simpleWebAppFireworkClass.findMethod("onCreate", Bundle::class.java).after { param ->
            logI("simpleWebApp onCreate")
            webViewField?.get(param.thisObject)?.let {
                webViewRef = WeakReference(it as View)
                addJavascriptInterface?.invoke(
                    it,
                    this,
                    "AutoOral"
                )
            }
        }

        loadUrl?.after { param ->
            val str = param.args[0].toString()
            logI("loadUrl: " + str.take(160))
            when {
                str.startsWith("javascript:") -> return@after
                str.contains("/bh5/leo-web-poem-pk/exercise.html") -> {
                    logI("poetry pk page loaded")
                    hookConsoleLog()
                    if (PK.pkWin) {
                        pkBattlePageLoaded.set(true)
                    } else {
                        logI("诗词PK自动答题未开启")
                    }
                }

                str.contains("/bh5/leo-web-oral-pk/english-word-match.html") -> {
                    logI("word pk page loaded")
                    hookConsoleLog()
                    if (PK.pkWin) {
                        pkBattlePageLoaded.set(true)
                    } else {
                        logI("单词PK自动答题未开启")
                    }
                }

                str.contains("/bh5/leo-web-oral-pk/exercise.html") -> {
                    logI("exercise.html loaded")
                    hookConsoleLog()
                    pkPageLoaded.set(true)
                }

                str.contains("/bh5/leo-web-oral-pk/result.html") -> {
                    logI("result.html loaded")
                    hookConsoleLog()
                    resultPageLoaded.set(true)
                }

                str.contains("/bh5/leo-web-math-exercise/animation-oral.html") -> {
                    logI("oral practice page loaded")
                    hookConsoleLog()
                    // 解析 keypointId / limit，触发自动上分（刷分循环）
                    if (Practice.autoHonor) {
                        val uri = Uri.parse(str)
                        val keyPointId = uri.getQueryParameter("keypointId") ?: "0"
                        val limit = uri.getQueryParameter("limit")?.toIntOrNull() ?: 10
                        val ctx = (param.thisObject as? View)?.context
                        runCatching {
                            PracticeHook.triggerHonor(ctx as Activity, keyPointId, limit)
                        }.onFailure {
                            logI("triggerHonor fail: $it")
                        }
                    }
                }
            }
        }

        hookJsLoadComplete()
    }

    private fun hookJsLoadComplete() {
        val commonWebViewInterfaceClass = findClass(Classname.COMMON_WEB_VIEW_INTERFACE)
        commonWebViewInterfaceClass.findMethod("jsLoadComplete", String::class.java)
            .after {
                when {
                    pkPageLoaded.compareAndSet(true, false) -> {
                        injectJs2PkPage()
                    }

                    resultPageLoaded.compareAndSet(true, false) -> {
                        injectJs2ResultPage()
                    }

                    pkBattlePageLoaded.compareAndSet(true, false) -> {
                        injectJs2PkBattle()
                    }
                }
            }
    }

    private fun injectConfig(loadUrl: Method, webView: View, key: String, value: Any) {
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript: (function(){window._$key=$value;})();")
        )
    }

    private fun injectJsCode(jsCode: String, loadUrl: Method, webView: View) {
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript:(function() { $jsCode })();")
        )
        logI("js injected")
    }

    private fun injectJs2PkPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            val mode = PK.mode
            val jsCode = when (mode) {
                AutoAnswerMode.QUICK -> quickJs

                AutoAnswerMode.CUSTOM -> PK.customJs

                AutoAnswerMode.STANDARD -> standardJs

                AutoAnswerMode.DISABLE -> ""
            }
            if (jsCode.isEmpty()) {
                logI("自动答题配置: ${mode.value}")
            } else {
                injectJsCode(jsCode, loadUrl, webView)
            }
        }
    }

    private fun injectJs2ResultPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            injectConfig(loadUrl, webView, "pk_cyclic_interval", PK.pkCyclicInterval)

            if (PK.pkCyclic) {
                injectJsCode(cyclicJs, loadUrl, webView)
            }
        }
    }


    private fun injectJs2PkBattle() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            injectConfig(loadUrl, webView, "autoOralAlwaysTrue", Common.alwaysTrue)
            injectJsCode(pkBattleJs, loadUrl, webView)
        }
    }

    private fun hookAddJavascriptInterface(addJavascriptInterface: Method) {
        val openSchemaBeanClass = findClass(Classname.OPEN_SCHEMA_BEAN)
        val dataEncryptBeanClass = findClass(Classname.DATA_ENCRYPT_BEAN)
        val unhooks = arrayOf<Unhook?>(null)
        var count = 0
        addJavascriptInterface.before { param ->
            val obj = param.args[0]
            val name = param.args[1]
            logI(name)
            when (name) {
                "CommonWebView" -> {
                    val caller = XposedHelpers.callMethod(obj, "get", openSchemaBeanClass)
                    hookOpenSchema(caller::class.java)
                    count++
                }

                "LeoSecureWebView" -> {
                    obj::class.java.declaredFields.firstOrNull {
                        Map::class.java.isAssignableFrom(it.type)
                    }?.let {
                        val caller = (it.get(obj) as Map<*, *>)[dataEncryptBeanClass]!!
                        hookDataEncrypt(caller::class.java)
                    }
                    count++
                }

                else -> {}
            }
            if (count >= 2) {
                unhooks.forEach { it?.unhook() }
            }
        }.also {
            unhooks[0] = it
        }
    }

    private fun hookOpenSchema(caller: Class<*>) {
        var lastSchemas: Any? = null
        caller.allMethod("call").before {
            if (!PK.pkCyclic) {
                return@before
            }
            val schemas = XposedHelpers.getObjectField(it.args[0], "schemas") as Array<*>
            val url = Uri.parse(schemas[0].toString()).getQueryParameter("url")!!
            val targetUri = Uri.parse(url)
            when (targetUri.path) {
                "/bh5/leo-web-study-group/motivation-honor-roll.html" -> {
                    when (targetUri.getQueryParameter("fromType")) {
                        "oralPkResult" -> {
                            XposedHelpers.callMethod(
                                it.args[0],
                                "trigger",
                                webView,
                                null,
                                emptyArray<Any>()
                            )
                            it.result = null
                        }

                        "resultPageJs" -> {
                            XposedHelpers.setObjectField(it.args[0], "schemas", lastSchemas)
                            XposedHelpers.setBooleanField(it.args[0], "close", true)
                        }
                    }
                }

                "/bh5/leo-web-oral-pk/result.html" -> {}
                else -> {
                    lastSchemas = schemas.copyOf(schemas.size)
                }
            }
        }
    }

    private fun getSimulateCostTime(questionCnt: Int): Long {
        val interval = PK.quickModeInterval
        return questionCnt * interval.toLong()
    }

    private fun hookDataEncrypt(caller: Class<*>) {
        caller.allMethod("call").before { param ->
            val mode = PK.mode
            val alwaysTrue = Common.alwaysTrue
            val doPk = mode in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD)
            // 无差别正确(一切输入视为正确答案) 或 口算PK自动答题 任一开启才处理
            if (!alwaysTrue && !doPk) {
                return@before
            }
            val bean = param.args[0]
            val base64 = XposedHelpers.getObjectField(bean, "base64").toString()
            if (base64.isBlank()) {
                return@before
            }
            // Debug：把所有 DataEncrypt 载荷原样转储（口算/诗词/单词PK 提交都可抓到）
            if (Debug.debug) {
                val raw = kotlin.runCatching { Base64.decode(base64, 0).decodeToString() }.getOrElse { base64 }
                thread {
                    kotlin.runCatching {
                        val f = File(
                            AndroidAppHelper.currentApplication().externalCacheDir,
                            "${System.currentTimeMillis()}.json"
                        )
                        f.writeText(raw)
                        logI("dump payload -> ${f.absolutePath} (${raw.length} chars)")
                    }
                }
            }
            val json =
                kotlin.runCatching { JSONObject(Base64.decode(base64, 0).decodeToString()) }
                    .getOrNull()
                    ?: return@before
            if (!json.has("pkIdStr")) {
                return@before
            }
            runCatching {
                val questions = json.getJSONArray("questions")
                for (i in 0 until questions.length()) {
                    val question = questions.getJSONObject(i)
                    // 无差别正确：发包前把用户答案改为正确答案（诗词/单词/口算通用）
                    if (alwaysTrue) {
                        // 诗词PK格式：selfAnswer/correctAnswer
                        if (question.has("correctAnswer")) {
                            val correct = question.getString("correctAnswer")
                            question.put("selfAnswer", correct)
                            if (question.has("userAnswer")) question.put("userAnswer", correct)
                            if (question.has("answer")) question.put("answer", correct)
                        }
                        // 单词PK格式：每题 correct 布尔
                        if (question.has("correct")) {
                            question.put("correct", true)
                        }
                    }
                    // 口算PK 原有重写（容错：用 optString 避免无 userAnswer 的题抛异常）
                    if (doPk && question.has("userAnswer")) {
                        val answer = question.optString("userAnswer")
                        val pathPoints = answer.pathPoints.toJSONArray()
                        val curTrueAnswer = question.optJSONObject("curTrueAnswer")
                        curTrueAnswer?.put("pathPoints", pathPoints)
                        if (question.has("script")) {
                            question.put("script", pathPoints.toString())
                        }
                    }
                }
                val questionCnt = json.optInt("questionCnt")
                if (alwaysTrue) {
                    json.put("correctCnt", questionCnt)
                }
                if (doPk && mode == AutoAnswerMode.QUICK) {
                    val appropriateCostTime = appropriateCostTime.get()
                    val costTime = if (PK.quickModeMustWin && appropriateCostTime > 0) {
                        appropriateCostTime
                    } else {
                        getSimulateCostTime(questionCnt).coerceAtLeast(questionCnt * 200L)
                    }
                    logI("originCostTime: ${json.get("costTime")}, costTime: $costTime")
                    json.put("costTime", costTime)
                }
                if (Debug.debug) {
                    thread {
                        kotlin.runCatching {
                            val file = File(
                                AndroidAppHelper.currentApplication().externalCacheDir,
                                "${System.currentTimeMillis()}.json"
                            )
                            file.writeText(json.toString())
                            logI("dump rewritten -> ${file.absolutePath}")
                        }
                    }
                }
                val newBase64 = Base64.encode(json.toString().toByteArray(), 0).decodeToString()
                XposedHelpers.setObjectField(bean, "base64", newBase64)
            }.onFailure {
                logI(it)
            }
        }
    }
}
