(function () {
    "use strict";
    var TAG = "[AutoOral]";
    function log(m) { try { console.log(TAG + " " + m); } catch (e) {} }

    // Vue2.7 script-setup 数据在 $_setupProxy（向下递归找持有 exerciseRecord 的根组件）
    function findRoot(comp) {
        var stack = [comp];
        while (stack.length) {
            var c = stack.pop();
            if (!c) continue;
            if (c._setupProxy && c._setupProxy.exerciseRecord) return c;
            if (c._setupState && c._setupState.exerciseRecord) return c;
            if (c.$children) for (var i = c.$children.length - 1; i >= 0; i--) stack.push(c.$children[i]);
        }
        return null;
    }

    // 配置（由 native 注入）：_autoOral_quick 极速、_autoOral_interval 每步间隔ms
    var QUICK = window._autoOral_quick === true;
    var INTERVAL = (typeof window._autoOral_interval === "number" && window._autoOral_interval > 0)
        ? window._autoOral_interval : (QUICK ? 120 : 500);

    function start() {
        var app = window.VUE_APP;
        if (!app) { setTimeout(start, 400); return; }
        var root = findRoot(app.$root) || findRoot(app);
        if (!root) { log("root not found, retry"); setTimeout(start, 700); return; }
        var p = root._setupProxy || root._setupState;
        log("root found questions=" + ((p.formatQuestionList || p.questionList || []).length));

        var lastIdx = -1;

        function questions() { return p.formatQuestionList || p.questionList || []; }
        function curAns() {
            var qs = questions();
            var q = qs[p.questionIndex] || p.questionVO;
            if (q && q.answers && q.answers.length) return String(q.answers[0]);
            if (p.questionVO && p.questionVO.answers && p.questionVO.answers.length) return String(p.questionVO.answers[0]);
            return null;
        }

        setInterval(function () {
            try {
                var qs = questions();
                var idx = p.questionIndex;
                var cur = p.curTrueAnswer;
                // 仅当切到新题、且当前尚未正确作答时注入正确结果，驱动 App 自动记录+切题(+结算)
                if (cur && cur.answer === 1) return;
                if (idx === lastIdx) return;
                var ans = curAns();
                if (!ans) return;
                var rec = { recognizeResult: ans, pathPoints: [], answer: 1, showReductionFraction: 0 };
                p.curTrueAnswer = rec;
                // 同步记录，兜底
                var recArr = p.exerciseRecord || [];
                var q = qs[idx] || p.questionVO;
                recArr[idx] = Object.assign({}, q, {
                    status: 1, userAnswer: ans, script: "[]", errorState: 0, curTrueAnswer: rec
                });
                lastIdx = idx;
                log("answered q" + idx + " = " + ans);
            } catch (e) {
                log("err: " + e);
            }
        }, INTERVAL);
    }

    setTimeout(start, 900);
})();
