package cn.tinyhai.auto_oral_calculation.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.ClipDrawable
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ProgressBar
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import cn.tinyhai.auto_oral_calculation.util.Honor
import cn.tinyhai.auto_oral_calculation.util.UiKit
import cn.tinyhai.auto_oral_calculation.util.Practice
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.mainHandler
import cn.tinyhai.auto_oral_calculation.util.strokes
import cn.tinyhai.auto_oral_calculation.util.toJsonString
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class PracticeHook : BaseHook() {

    companion object {
        @Volatile
        private var hook: PracticeHook? = null

        // 供 H5 练习页（WebViewHook）触发自动上分
        fun triggerHonor(activity: Activity, keyPointId: String, limit: Int) {
            hook?.startHonor(activity, keyPointId, limit)
        }
    }

    private var honorHelper: HonorHelper? = null


    override val name: String
        get() = "PracticeHook"

    private val executor: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(5, DiscardPolicy())
    }

    override fun startHook() {
        hook = this
        val quickExerciseActivityClass = findClass(Classname.QUICK_EXERCISE_ACTIVITY)

        hookQuickExerciseActivity(quickExerciseActivityClass)

        hookCountDownTimer()
    }


    private fun hookCountDownTimer() {
        val countDownTimerClass = CountDownTimer::class.java
        val unhooks = arrayOf<Unhook?>(null)
        countDownTimerClass.findConstructor(
            Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!
        ).after { param ->
            val thisClass = param.thisObject::class.java
            if (!thisClass.name.startsWith(Classname.PRESENTER)) {
                return@after
            }
            logI("hook timer")
            thisClass.findMethod("onFinish").before {
                if (Practice.autoHonor) {
                    it.result = null
                }
            }
            unhooks.forEach { it?.unhook() }
        }.also { unhooks[0] = it }
    }

    private fun showEditAlertDialog(context: Context, onConfirm: (Int) -> Unit) {
        val editText = UiKit.input(context, "练习次数").apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(9))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(editText)
        }

        val dialog = AlertDialog.Builder(context)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetCount = editText.text.toString().toInt()
                onConfirm(targetCount)
            }.setNegativeButton(android.R.string.cancel, null)
            .setTitle("请输入练习次数")
            .setView(container).show()
        UiKit.styleDialog(dialog, context, 280f)
        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                positiveButton.isEnabled = s.isNotEmpty()
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun showProgressDialog(context: Context, onDismiss: () -> Unit): (Int, Int, Int, Double) -> Unit {
        val radius = 28f
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius * 2
        }
        fun card(): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(245, 247, 250))
            cornerRadius = 18f
        }
        fun accent(): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
            colors = intArrayOf(Color.rgb(52, 120, 246), Color.rgb(64, 158, 255))
            cornerRadius = 20f
        }
        val pad = (context.resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(pad, pad + 6, pad, pad)
        }
        val title = TextView(context).apply {
            text = "自动上分"
            textSize = 18f
            setTextColor(Color.rgb(24, 28, 36))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val sub = TextView(context).apply {
            text = "运行中，实时更新"
            textSize = 12f
            setTextColor(Color.rgb(140, 148, 160))
        }
        // 进度条（自绘，圆角渐变）
        val track = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(235, 238, 244)); cornerRadius = 20f
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.density * 14).toInt())
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
                track,
                android.graphics.drawable.ClipDrawable(
                    accent(), android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                setLayerInset(1, 0, 0, 0, 0)
            }
            max = 100
        }
        val percent = TextView(context).apply {
            text = "0%"; textSize = 22f
            setTextColor(Color.rgb(24, 28, 36)); setTypeface(null, android.graphics.Typeface.BOLD)
        }
        fun infoCard(label: String, init: String): Pair<LinearLayout, TextView> {
            val v = TextView(context).apply { text = init; textSize = 14f; setTextColor(Color.rgb(24,28,36)); setTypeface(null, android.graphics.Typeface.BOLD) }
            val l = TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.rgb(140,148,160)) }
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = card()
                setPadding((context.resources.displayMetrics.density*12).toInt(), (context.resources.displayMetrics.density*10).toInt(), (context.resources.displayMetrics.density*12).toInt(), (context.resources.displayMetrics.density*10).toInt())
                addView(l); addView(v)
            }
            return box to v
        }
        val (qCard, qVal) = infoCard("题目进度", "0 题")
        val (sCard, sVal) = infoCard("平均速度", "-- 题/秒")
        val (tCard, tVal) = infoCard("线程数", "${Honor.threads}")
        val (rCard, rVal) = infoCard("完成轮次", "0")
        val grid1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(qCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd=(context.resources.displayMetrics.density*8).toInt() }); addView(sCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        val grid2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(tCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd=(context.resources.displayMetrics.density*8).toInt() }); addView(rCard, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)) }
        val counterTv = TextView(context).apply { text="0/0"; textSize=13f; setTextColor(Color.rgb(140,148,160)) }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(percent); addView(View(context), LinearLayout.LayoutParams(0,0,1f)); addView(counterTv) }
        root.addView(title); root.addView(sub); root.addView(View(context), LinearLayout.LayoutParams(1,(context.resources.displayMetrics.density*12).toInt()))
        root.addView(progressBar); root.addView(View(context), LinearLayout.LayoutParams(1,(context.resources.displayMetrics.density*8).toInt()))
        root.addView(row); root.addView(View(context), LinearLayout.LayoutParams(1,(context.resources.displayMetrics.density*12).toInt()))
        root.addView(grid1); root.addView(View(context), LinearLayout.LayoutParams(1,(context.resources.displayMetrics.density*8).toInt()))
        root.addView(grid2)
        val dialog = android.app.Dialog(context).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setLayout((300f*context.resources.displayMetrics.density).toInt(), LayoutParams.WRAP_CONTENT) }
        }
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return { current, target, questions, qps ->
            mainHandler.post {
                val progress = (100 * (current / target.toFloat())).toInt().coerceIn(0, 100)
                progressBar.setProgress(progress, true)
                percent.text = "$progress%"
                counterTv.text = "$current/$target"
                qVal.text = "$questions 题"
                sVal.text = String.format("%.2f 题/秒", qps)
                rVal.text = "$current"
                if (progress >= 100) { sub.text = "已完成"; }
            }
        }
    }


    private fun testDelay(keyPointId: String, limit: Int) {
        thread {
            var delay = 10000L
            var lastReqTime: Long
            val lock = ReentrantLock()
            val condition = lock.newCondition()
            var active = true
            var count = 0
            Thread.sleep(delay)
            while (active) {
                lock.withLock {
                    lastReqTime = SystemClock.elapsedRealtime()
                    logI("req start with delay: $delay")
                    OralApiService.getExamInfo(keyPointId, limit) {
                        lock.withLock {
                            if (it.isSuccess) {
                                count++
                                if (count >= 10) {
                                    count = 0
                                    delay -= 500
                                    logI("cur delay: $delay")
                                }
                            } else {
                                active = false
                                logI("final delay: $delay")
                            }
                            condition.signalAll()
                        }
                    }
                    condition.await(delay, TimeUnit.MILLISECONDS)
                    val elapsed = SystemClock.elapsedRealtime() - lastReqTime
                    if (elapsed < delay) {
                        condition.await(delay - elapsed, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
    }

    // 供 H5 练习页触发自动上分：设置协程上下文 + 弹次数框 + 启动刷分循环
    fun startHonor(activity: Activity, keyPointId: String, limit: Int) {
        if (!Practice.autoHonor) return
        // 免弹框自动刷次数（>0 时直接按配置次数开刷）
        val autoCount = runCatching { Honor.autoCount }.getOrDefault(0)
        if (autoCount > 0) {
            try {
                val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)
                val scope = XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
                val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")
                OralApiService.setup(coroutineContext)
            } catch (e: Throwable) {
                logI("honor setup fail: $e")
            }
            honorHelper = HonorHelper(keyPointId, limit, autoCount) { cur, total, questions, qps ->
                logI("auto honor progress: $cur/$total, questions=$questions, qps=${"%.2f".format(qps)}")
            }.also { it.startHonor() }
            return
        }
        try {
            val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)
            val scope =
                XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
            val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")
            OralApiService.setup(coroutineContext)
        } catch (e: Throwable) {
            logI("honor setup fail: $e")
        }
        showEditAlertDialog(activity) { targetCount ->
            val onProgressChange = showProgressDialog(activity) {
                honorHelper?.stopHonor()
            }
            honorHelper = HonorHelper(keyPointId, limit, targetCount, onProgressChange).also {
                it.startHonor()
            }
        }
    }

    private fun hookQuickExerciseActivity(quickExerciseActivityClass: Class<*>) {
        val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)

        var helper: HonorHelper? = null
        quickExerciseActivityClass.findMethod("onCreate", Bundle::class.java).after { param ->
            val activity = param.thisObject as Activity
            val scope =
                XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
            val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")

            OralApiService.setup(coroutineContext)
            if (Practice.autoHonor) {
                // 新版 keypointId 在 Intent 里，limit 从模型/Intent 兜底取
                val keyPointId = activity.intent.getIntExtra("keypointId", -1).toString()
                val limit = runCatching {
                    XposedHelpers.getIntField(XposedHelpers.getObjectField(activity, "e"), "c")
                }.getOrElse {
                    activity.intent.getIntExtra("limit", 10)
                }
                showEditAlertDialog(activity as Context) { targetCount ->
                    val onProgressChange = showProgressDialog(activity) {
                        helper?.stopHonor()
                    }
                    helper = HonorHelper(keyPointId, limit, targetCount, onProgressChange).also {
                        it.startHonor()
                    }
                }
            }
        }

        quickExerciseActivityClass.findMethod("onDestroy").before {
            helper?.stopHonor()
        }
    }

    private inner class HonorHelper(
        private val keyPointId: String,
        private val limit: Int,
        private val targetCount: Int = Int.MAX_VALUE,
        private val onProgress: (Int, Int, Int, Double) -> Unit
    ) {
        // 多线程并发上分：N 个 worker 各自拉题+提交，共享目标计数
        private val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalQuestions = java.util.concurrent.atomic.AtomicInteger(0)
        private val startTime = SystemClock.elapsedRealtime()
        private val pendingCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var active: Boolean = true
        private val workers = mutableListOf<Thread>()

        // 并发线程数（设置可调 1~10，默认3）
        private val honorThreadCount = Honor.threads

        fun stopHonor() {
            active = false
            workers.forEach { it.interrupt() }
        }

        fun startHonor() {
            if (targetCount <= 0) {
                stopHonor()
                return
            }
            repeat(honorThreadCount.coerceAtLeast(1)) { i ->
                val t = thread(name = "auto-honor-$i") {
                    work()
                }
                workers.add(t)
            }
        }

        private fun work() {
            while (active && !Thread.interrupted()) {
                if (successCount.get() >= targetCount) {
                    break
                }
                // 控制待处理数量，避免过多在途请求
                if (pendingCount.get() >= honorThreadCount * 2) {
                    Thread.sleep(500)
                    continue
                }
                pendingCount.incrementAndGet()
                OralApiService.getExamInfo(keyPointId, limit) { result ->
                    result.onSuccess {
                        logI("get exam elapsed success")
                        handleExamVO(it)
                    }.onFailure {
                        pendingCount.decrementAndGet()
                        if (it !is CancellationException) {
                            logI("get exam failed: ${it.message}")
                        }
                    }
                }
                // 每线程请求间隔（设置可调）
                Thread.sleep(Honor.reqInterval)
            }
        }

        private fun handleExamVO(examVO: Any) {
            if (!active) {
                return
            }
            executor.execute {
                kotlin.runCatching {
                    buildAndUploadExamResult(examVO)
                }.onFailure {
                    logI(it)
                }
            }
        }

        private fun buildExamResult(examVO: Any): Pair<String, Long> {
            val examId = XposedHelpers.getObjectField(examVO, "idString").toString()
            val questions = XposedHelpers.getObjectField(examVO, "questions") as List<*>
            var totalTime = 0L
            questions.forEach {
                val answers = XposedHelpers.getObjectField(it, "answers") as? List<*>
                val answer = answers?.firstOrNull()?.toString() ?: ""
                XposedHelpers.callMethod(it, "setUserAnswer", answer)
                val base = Honor.answerTime
                val costTime = Random.nextInt(base, base + 100)
                XposedHelpers.callMethod(it, "setCostTime", costTime)
                XposedHelpers.callMethod(it, "setScript", answer.strokes.toJsonString())
                XposedHelpers.callMethod(it, "setStatus", 1)
                totalTime += costTime
            }
            val questionCnt = XposedHelpers.getIntField(examVO, "questionCnt")
            XposedHelpers.callMethod(examVO, "setCorrectCnt", questionCnt)
            XposedHelpers.callMethod(examVO, "setCostTime", totalTime)
            return examId to (totalTime + 200)
        }

        private fun buildAndUploadExamResult(examVO: Any) {
            val (examId, delay) = buildExamResult(examVO)
            val uploadReqTime = SystemClock.elapsedRealtime()
            val runnable = Runnable {
                OralApiService.uploadExamResult(examId, examVO) {
                    val elapsed = SystemClock.elapsedRealtime() - uploadReqTime
                    pendingCount.decrementAndGet()
                    it.onFailure {
                        if (it !is CancellationException) {
                            logI("upload exam failed: ${it.message}")
                        }
                    }.onSuccess {
                        successCount.incrementAndGet()
                        val questions = totalQuestions.addAndGet(
                            XposedHelpers.getIntField(examVO, "questionCnt")
                        )
                        val elapsedSec = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                        val qps = if (elapsedSec > 0) questions / elapsedSec else 0.0
                        logI("upload exam elapsed: $elapsed, success=${successCount.get()}, questions=$questions, qps=$qps")
                        onProgress(successCount.get(), targetCount, questions, qps)
                    }
                }
            }
            executor.schedule(runnable, delay, TimeUnit.MILLISECONDS)
        }
    }
}
