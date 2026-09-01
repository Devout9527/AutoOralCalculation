package cn.tinyhai.auto_oral_calculation.hook

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ProgressBar
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.OralApiService
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

    private class ExerciseGeneralModelWrapper(modelClass: Class<*>) {
        // 新版模型 cl.l：c(long,List)=buildUri, g(Context,Intent,Uri,int)=gotoResult
        private val buildUri: Method = modelClass.methods.first {
            it.returnType == Uri::class.java && it.parameterCount == 2
        }.also { it.isAccessible = true }

        private val gotoResult: Method = modelClass.methods.first {
            it.returnType == Void.TYPE && it.parameterCount > 1 && it.parameterTypes[0] == Context::class.java
        }.also { it.isAccessible = true }

        fun Any.buildUri(costTime: Long, dataList: List<*>): Any? {
            return buildUri.invoke(this, costTime, dataList)
        }

        fun Any.gotoResult(context: Context, intent: Intent, uri: Uri, exerciseType: Int) {
            gotoResult.invoke(this, context, intent, uri, exerciseType)
        }
    }

    private class QuickExercisePresenterWrapper(presenterClass: Class<*>) {
        // 新版(3.140+) presenter 已混淆为 com.fenbi.android.leo.exercise.math.quick.c：
        //   c()                    = 当前题正确答案列表 (rightAnswers)
        //   f(String,List,Map)     = 提交识别结果（内部完成 setUserAnswer/isRight/刷新UI）
        //   b(boolean,List)        = 下一题（内部完成 costTime/strokes/切题）
        private val getAnswers: Method = presenterClass.declaredMethods.first {
            it.name == "c" && it.parameterCount == 0
        }

        private val commitAnswer: Method = presenterClass.declaredMethods.first {
            it.name == "f" && it.parameterCount == 3
        }

        private val nextQuestion: Method = presenterClass.declaredMethods.first {
            it.name == "b" && it.parameterCount == 2 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }

        fun Any.getAnswers(): List<*>? {
            return getAnswers.invoke(this) as? List<*>
        }

        fun Any.commitAnswer(answer: String, strokes: List<Array<PointF>>) {
            commitAnswer.invoke(this, answer, strokes, emptyMap<String, Any>())
        }

        fun Any.nextQuestion(autoJump: Boolean, strokes: List<Array<PointF>>) {
            nextQuestion.invoke(this, autoJump, strokes)
        }
    }

    override val name: String
        get() = "PracticeHook"

    private val executor: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(5, DiscardPolicy())
    }

    private lateinit var presenterRef: WeakReference<Any>

    private lateinit var presenterWrapper: QuickExercisePresenterWrapper

    private val presenter get() = presenterRef.get()

    // 防止同一道题被多个触发点重复作答
    private var lastAnsweredPos = -1

    private val performNext = Runnable {
        if (Practice.autoPractice) {
            with(presenterWrapper) {
                presenter?.run {
                    val curPos = XposedHelpers.getIntField(this, "curPos")
                    if (curPos == lastAnsweredPos) {
                        return@run
                    }
                    val answer = getAnswers()?.firstOrNull()?.toString() ?: return@run
                    lastAnsweredPos = curPos
                    commitAnswer(answer, answer.strokes)
                    nextQuestion(true, answer.strokes)
                }
            }
        }
    }

    override fun startHook() {
        hook = this
        val quickExerciseActivityClass = findClass(Classname.QUICK_EXERCISE_ACTIVITY)

        hookQuickExerciseActivity(quickExerciseActivityClass)

        hookQuickExercisePresenter(quickExerciseActivityClass)

        hookCountDownTimer()

        hookSimpleWebActivityCompanion()
    }

    private fun hookQuickExercisePresenter(quickExerciseActivityClass: Class<*>) {
        val quickExercisePresenterClass = findClass(Classname.PRESENTER)
        presenterWrapper = QuickExercisePresenterWrapper(quickExercisePresenterClass)

        // ready-go 动画结束（c$b.onAnimationEnd）→ 答第一题
        findClass("${Classname.PRESENTER}\$b")
            .findMethod("onAnimationEnd", Animator::class.java)
            .after {
                if (Practice.autoPractice) {
                    mainHandler.post(performNext)
                }
            }

        // 每题切题动画结束（QuickExerciseActivity$e.onAnimationEnd，原方法内已 N() 推进）→ 答下一题
        findClass("${Classname.QUICK_EXERCISE_ACTIVITY}\$e")
            .findMethod("onAnimationEnd", Animation::class.java)
            .after {
                if (Practice.autoPractice) {
                    mainHandler.post(performNext)
                }
            }

        // 题目加载完成（P(List)）→ 记录 presenter + 兜底触发
        quickExercisePresenterClass.declaredMethods.first {
            it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        }.after { param ->
            presenterRef = WeakReference(param.thisObject)
            lastAnsweredPos = -1
            if (!Practice.autoPractice) {
                return@after
            }
            if (Practice.autoPracticeQuick) {
                quickFinishAll(param)
            } else {
                mainHandler.postDelayed(performNext, 800)
            }
        }
    }

    private fun quickFinishAll(param: XC_MethodHook.MethodHookParam) {
        kotlin.runCatching {
            val presenter = param.thisObject
            val presenterClass = presenter::class.java
            val v = XposedHelpers.getObjectField(presenter, "a")
            val activity = XposedHelpers.callMethod(v, "getContext") as Activity
            val model = XposedHelpers.getObjectField(activity, "e")
            val modelWrapper = ExerciseGeneralModelWrapper(model::class.java)
            val dataList = presenterClass.declaredFields.firstOrNull {
                List::class.java.isAssignableFrom(it.type)
            }?.get(presenter) as List<*>
            var totalTime = 0
            dataList.subList(1, dataList.size - 1).forEach { data ->
                val answers = XposedHelpers.getObjectField(data, "rightAnswers") as? List<*>
                val answer = answers?.firstOrNull()?.toString() ?: ""
                XposedHelpers.callMethod(data, "setUserAnswer", answer)
                val costTime = Random.nextInt(150, 250)
                XposedHelpers.callMethod(data, "setCostTime", costTime)
                XposedHelpers.callMethod(data, "setStrokes", answer.strokes)
                totalTime += costTime
            }
            val exerciseTypeInt = runCatching {
                XposedHelpers.callMethod(
                    XposedHelpers.callMethod(model, "getType"),
                    "getExerciseType"
                ) as Int
            }.getOrDefault(0)
            mainHandler.postDelayed({
                with(modelWrapper) {
                    model.run {
                        val uri = buildUri(totalTime.toLong(), dataList) as Uri
                        gotoResult(activity, activity.intent, uri, exerciseTypeInt)
                        activity.finish()
                    }
                }
            }, totalTime.toLong())
        }.onFailure {
            logI(it)
        }
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
        val editText = EditText(context)
        editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(9))

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
    private fun showProgressDialog(context: Context, onDismiss: () -> Unit): (Int, Int) -> Unit {
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
        )
        val textView = TextView(context).apply {
            text = "0/0"
            textSize = 16f
            setTextColor(Color.rgb(0x33, 0x33, 0x33))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setHorizontalGravity(Gravity.END)
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(progressBar)
            addView(textView)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("练习进度")
            .setView(container)
            .setNegativeButton("停止", null)
            .setCancelable(false)
            .setOnDismissListener {
                onDismiss()
            }.show()
        return { current, target ->
            mainHandler.post {
                val progress = (100 * (current / target.toFloat())).toInt().coerceIn(0, 100)
                progressBar.setProgress(progress, true)
                textView.text = "$current/$target"
                if (progress >= 100) {
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text = "完成"
                }
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

    private fun hookSimpleWebActivityCompanion() {
        val exerciseResultActivityClass = findClass(Classname.EXERCISE_RESULT_ACTIVITY)
        val simpleWebActivityCompanionClass =
            findClass("${Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY}\$a")

        simpleWebActivityCompanionClass.allMethod("a").before { param ->
            if (!Practice.autoPracticeCyclic) {
                return@before
            }
            val activity = param.args[0] as? Activity ?: return@before
            if (exerciseResultActivityClass.isInstance(activity)) {
                val interval = Practice.autoPracticeCyclicInterval
                mainHandler.postDelayed({
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        kotlin.runCatching {
                            activity.findViewById<View>(
                                activity.resources.getIdentifier(
                                    "menu_button_again_btn", "id", activity.packageName
                                )
                            ).performClick()
                        }.onFailure {
                            logI(it)
                        }
                    }
                }, interval.toLong())
                param.result = null
            }
        }
    }

    private inner class HonorHelper(
        private val keyPointId: String,
        private val limit: Int,
        private val targetCount: Int = Int.MAX_VALUE,
        private val onProgress: (Int, Int) -> Unit
    ) {
        // 多线程并发上分：N 个 worker 各自拉题+提交，共享目标计数
        private val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        private val pendingCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile private var active: Boolean = true
        private val workers = mutableListOf<Thread>()

        // 并发线程数（可在代码里调整，建议 2~5，视服务器承受力）
        private val honorThreadCount = 3

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
                // 每线程请求间隔
                Thread.sleep(1000)
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
                val costTime = Random.nextInt(150, 250)
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
                        logI("upload exam elapsed: $elapsed, success=${successCount.get()}")
                        onProgress(successCount.get(), targetCount)
                    }
                }
            }
            executor.schedule(runnable, delay, TimeUnit.MILLISECONDS)
        }
    }
}
