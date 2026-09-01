(function () {
    "use strict";
    var TAG = "[AutoOralPK]";
    var ALWAYS_TRUE = window._autoOralAlwaysTrue === true;
    function log(m) { try { console.log(TAG + " " + m); } catch (e) {} }

    // Vue3: 通过 _instance 拿根组件，并遍历 subTree 找组件
    function v3root() {
        var app = window.VUE_APP;
        return (app && app._instance) ? app._instance : null;
    }

    // 遍历 Vue3 组件树，找含 onChoose/onChooseRight 事件处理的组件
    function findComp(rootInst, depth) {
        if (!rootInst || depth > 20) return null;
        var p = rootInst.proxy;
        // 检查 setupState 是否有 onChoose/onAnswer/onChooseRight
        var st = rootInst.setupState || rootInst.ctx;
        if (st) {
            if (typeof st.onChoose === "function") return { inst: rootInst, kind: "poem", method: "onChoose" };
            if (typeof st.onChooseRight === "function") return { inst: rootInst, kind: "word", method: "onChooseRight" };
            if (typeof st.onAnswer === "function") return { inst: rootInst, kind: "poem", method: "onAnswer" };
        }
        // 遍历 subTree / children
        var subs = [];
        function collect(n) {
            if (!n) return;
            if (n.component) subs.push(n.component);
            if (n.children && n.children.component) subs.push(n.children.component);
            if (Array.isArray(n.children)) n.children.forEach(collect);
            if (n.dynamicChildren) n.dynamicChildren.forEach(collect);
        }
        if (rootInst.subTree) collect(rootInst.subTree);
        for (var i = 0; i < subs.length; i++) {
            var r = findComp(subs[i], depth + 1);
            if (r) return r;
        }
        return null;
    }

    function start() {
        var app = window.VUE_APP;
        if (!app) { log("no VUE_APP"); setTimeout(start, 500); return; }
        var r = findComp(v3root(), 0);
        if (!r) { log("Vue3 component NOT found (onChoose/onAnswer/onChooseRight), retry"); setTimeout(start, 900); return; }
        log("found comp kind=" + r.kind + " method=" + r.method);
        var st = r.inst.setupState || r.inst.ctx || {};
        log("setupState keys: " + Object.keys(st).join(","));

        // 「点啥都对」：拦截 emit 的答案事件，逼其正确
        if (ALWAYS_TRUE && typeof r.inst.proxy.$emit === "function") {
            var origEmit = r.inst.proxy.$emit.bind(r.inst.proxy);
            r.inst.proxy.$emit = function (name) {
                if (name === "onChoose" || name === "onChooseRight" || name === "onAnswer") {
                    var args = Array.prototype.slice.call(arguments);
                    // 把最后一个布尔(对错)强制为 true
                    for (var i = args.length - 1; i >= 0; i--) {
                        if (typeof args[i] === "boolean") { args[i] = true; break; }
                    }
                    log("patch emit " + name + " -> force correct");
                    return origEmit.apply(null, args);
                }
                return origEmit.apply(null, arguments);
            };
            log("tapCorrect: $emit patched");
        }
    }

    setTimeout(start, 900);
})();
