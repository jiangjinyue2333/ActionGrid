package io.github.jiangjinyue2333.actiongrid.hook;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 微信长按菜单直连钩子（解包 8.0.77 得到的内部结构，2026-10-21）。
 *
 * <p>微信自己的长按菜单体系（混淆名，随版本会变，失效时本钩子整体停用并回退旧机制）：
 * <pre>
 *   hq5.r        原 com.tencent.mm.ui.widget.menu.MMPopupMenu
 *     x:Lnf5/g4  菜单容器（implements ContextMenu），项在 d:List&lt;nf5.h4&gt;
 *     v:Lnf5/t4  执行监听：onMMMenuItemSelected(MenuItem, int)   ← int 是菜单内下标
 *     f/g/h/i/j  入口：设锚点/监听 → x.clear() → builder 建菜单 → 弹窗
 *     fields     w:Lnf5/n4 builder / s:OnCreateContextMenuListener（备用路径）
 *   nf5.h4       MenuItem 实现（getItemId/getTitle）
 * </pre>
 *
 * <p>拦截方式：入口方法带"发现挂起"标志时直接替换——自己 new 一个 g4、
 * 调微信传入的 builder/listener 把菜单建进去，项列表同步到手，弹窗不创建。
 * 只影响静默发现；手动长按照常弹（标志未挂起时一律 chain.proceed()）。
 *
 * <p>会话列表和聊天消息都走这套（hq5.r 在 conversation 包同样有调用方），
 * 标签与执行的文案/顺序全部来自微信当场构建的菜单，不再按消息类型分别适配。
 */
final class WeChatMenuHook {
    private static final String TAG = "ActionGrid-WeChatMenu";
    /** 诊断日志总开关（稳定后默认关闭，省电）。排查时临时改 true。 */
    private static final boolean VERBOSE = false;
    private static XposedModule host;
    private static boolean installAttempted;

    /** 挂起窗口：静默长按链路可能异步进入入口方法，窗口期内才拦截。 */
    private static final long PENDING_WINDOW_MILLIS = 3000L;
    /** 异步收尾轮询：每 80ms 查一次是否命中，最多 ~0.8s。 */
    private static final long COLLECT_INTERVAL_MILLIS = 80L;
    private static final int COLLECT_MAX_TICKS = 10;
    /** 标签缓存：行 View 会被复用。缓存过期后仍然拿旧文案兜底（宁可文案旧一点，
     *  不能一个操作都没有），动作执行后立即失效。 */
    private static final long LABEL_TTL_MILLIS = 5000L;
    /** 同步建菜单限流：读屏遍历窗口时会连着查十几个节点，每个都建一遍会把主线程压死，
     *  读屏等超时就播报「无可用操作」。短窗口内建满就不再建，剩下的交给延迟补建。 */
    private static final long RATE_WINDOW_MILLIS = 120L;
    private static final int RATE_MAX_BUILDS = 3;
    private static final long[] RECENT_BUILDS = new long[RATE_MAX_BUILDS];
    private static int recentBuildCount;
    /** passthrough 一次性日志，避免刷屏。 */
    private static final java.util.Set<String> PASSTHROUGH_LOGS =
            new java.util.HashSet<>();

    private static volatile boolean available;
    private static Method pShowMethod;
    private static Class<?> menuClass;
    private static Constructor<?> menuCtor;
    private static Field menuItemsField;

    private enum PendingKind { NONE, LABELS, EXECUTE }

    private static final Object PENDING_LOCK = new Object();
    private static PendingKind pendingKind = PendingKind.NONE;
    private static long pendingAt;
    private static int pendingSlot = -1;
    private static boolean hookFired;
    private static boolean executeFinished;
    private static String[] capturedLabels;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<View, Cached> LABEL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 模板兜底：同类行第一次成功建出的菜单。
     *  某一行自己还没建好时先用模板顶上（用户 2026-10-21：不能出现「这一行没操作」）。
     *  key = spec + 行类名 + 公众号标记。 */
    private static final Map<String, String[]> TEMPLATES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static String[] templateLabels(String key) {
        return key == null ? null : TEMPLATES.get(key);
    }

    private WeChatMenuHook() {
    }

    private static final class Cached {
        final long at;
        final String[] labels;

        Cached(long at, String[] labels) {
            this.at = at;
            this.labels = labels;
        }
    }

    static synchronized boolean install(XposedModule module, ClassLoader appClassLoader) {
        if (installAttempted) {
            return available;
        }
        installAttempted = true;
        host = module;
        try {
            Class<?> popup = Class.forName("hq5.r", false, appClassLoader);
            menuClass = Class.forName("nf5.g4", false, appClassLoader);
            Class<?> listenerIf = Class.forName("nf5.t4", false, appClassLoader);
            Class<?> builderIf = Class.forName("nf5.n4", false, appClassLoader);
            menuCtor = menuClass.getDeclaredConstructor(android.content.Context.class);
            menuCtor.setAccessible(true);
            menuItemsField = menuClass.getField("d");
            menuItemsField.setAccessible(true);

            // {方法名, 参数类型…, builder/监听器参数下标, t4 参数下标}
            Object[][] entries = {
                    {"f", new Class<?>[]{View.class, builderIf, listenerIf,
                            int.class, int.class}, 1, 2},
                    {"g", new Class<?>[]{View.class, int.class, long.class,
                            View.OnCreateContextMenuListener.class, listenerIf,
                            int.class, int.class}, 3, 4},
                    {"h", new Class<?>[]{View.class,
                            View.OnCreateContextMenuListener.class, listenerIf,
                            int.class, int.class}, 1, 2},
                    {"i", new Class<?>[]{View.class, builderIf, listenerIf}, 1, 2},
                    {"j", new Class<?>[]{View.class,
                            View.OnCreateContextMenuListener.class, listenerIf}, 1, 2},
            };
            int hooked = 0;
            for (Object[] entry : entries) {
                String name = (String) entry[0];
                Class<?>[] parameterTypes = (Class<?>[]) entry[1];
                int buildIdx = (Integer) entry[2];
                int listenerIdx = (Integer) entry[3];
                try {
                    Method method = popup.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    module.hook(method)
                            .setId("actiongrid.wxmenu." + name)
                            .intercept(chain -> interceptEntry(chain, buildIdx, listenerIdx));
                    hooked++;
                } catch (Throwable throwable) {
                    log("hook miss " + name + " " + throwable.getClass().getSimpleName());
                }
            }
            // 兜底：个别页面不经过 f/g/h/i/j，直接设好字段后调 p/m/n 显示。
            try {
                Method pShow = popup.getDeclaredMethod("p", int.class, int.class);
                pShow.setAccessible(true);
                module.hook(pShow)
                        .setId("actiongrid.wxmenu.p")
                        .intercept(chain -> interceptEntry(chain, -1, -1));
                pShowMethod = pShow;
                hooked++;
            } catch (Throwable throwable) {
                log("hook miss p " + throwable.getClass().getSimpleName());
            }
            for (String name : new String[]{"m", "n"}) {
                try {
                    Method method = "m".equals(name)
                            ? popup.getDeclaredMethod("m")
                            : popup.getDeclaredMethod("n", int.class, int.class);
                    method.setAccessible(true);
                    module.hook(method)
                            .setId("actiongrid.wxmenu.skip." + name)
                            .intercept(chain -> pendingActive()
                                    ? Boolean.FALSE : chain.proceed());
                    hooked++;
                } catch (Throwable throwable) {
                    log("hook miss " + name + " " + throwable.getClass().getSimpleName());
                }
            }
            available = hooked > 0;
            log("wechat menu hook "
                    + (available ? "installed (" + hooked + " methods)" : "failed"));
            hapticHook(module);
        } catch (Throwable throwable) {
            available = false;
            log("install failed " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
        return available;
    }

    static boolean isAvailable() {
        return available;
    }

    /**
     * 该行的菜单标签是否已缓存（新鲜）。
     *
     * <p>用于避开主线程重建菜单：快速滑动时读屏会遍历窗口里所有节点，
     * 如果每个节点都同步重建一次菜单，主线程会被压死，读屏等超时后播报
     * 「无可用操作」——用户遇到的「快速滑动没有默认操作」就是这个。
     * 所以只有真正被聚焦的行才同步建菜单，其余行只用缓存或异步预热。
     */
    static boolean hasCachedLabels(View anchor) {
        if (!available || anchor == null) {
            return false;
        }
        Cached cached = LABEL_CACHE.get(anchor);
        return cached != null
                && SystemClock.elapsedRealtime() - cached.at <= LABEL_TTL_MILLIS;
    }

    /** 静默长按会走系统触感反馈（用户体感是"官家在振动"）：挂起期间一律静音。 */
    private static void hapticHook(XposedModule module) {
        for (Class<?>[] types : new Class<?>[][]{{int.class}, {int.class, int.class}}) {
            try {
                Method method = View.class.getDeclaredMethod(
                        "performHapticFeedback", types);
                method.setAccessible(true);
                module.hook(method)
                        .setId("actiongrid.wxmenu.haptic" + types.length)
                        .intercept(chain -> pendingActive() ? Boolean.FALSE : chain.proceed());
            } catch (Throwable throwable) {
                log("haptic hook miss " + throwable.getClass().getSimpleName());
            }
        }
    }

    /** 补抓节流：同一行 400ms 内只补一次，防止装饰风暴。 */
    private static final long RETRY_THROTTLE_MILLIS = 400L;
    private static final Map<View, Long> RETRY_ATTEMPTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 排队闸：允许少量补建排队（主线程串行执行，单个几毫秒），
     *  避免遍历风暴里只剩一个在跑、后面的行几秒都建不出来。 */
    private static final java.util.concurrent.atomic.AtomicInteger DEFERRED_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int DEFERRED_MAX = 4;

    /** 短窗口内同步建菜单超限 → true。 */
    private static boolean rateExceeded(long now) {
        synchronized (PENDING_LOCK) {
            int live = 0;
            for (long at : RECENT_BUILDS) {
                if (now - at <= RATE_WINDOW_MILLIS) {
                    live++;
                }
            }
            if (live >= RATE_MAX_BUILDS) {
                return true;
            }
            RECENT_BUILDS[recentBuildCount % RATE_MAX_BUILDS] = now;
            recentBuildCount++;
            return false;
        }
    }

    /**
     * 静默发现：对目标 View 走一次长按链路，返回微信自己建好的菜单标题
     * （数组顺序 = 弹窗顺序，元素与菜单项一一对应）。
     *
     * @param dropTitle 会话列表把弹窗里与行内文本相同的项（聊天标题）置空：
     *                  不注入也不朗读，但位置保留，执行下标仍与微信原菜单对齐。
     */
    static String[] labelsFor(View anchor, View origin, boolean dropTitle) {
        return labelsFor(anchor, origin, dropTitle, false, null);
    }

    /**
     * @param force       延迟补建时为 true：跳过节流。补建跑在遍历风暴之后的主线程尾部，
     *                    没有再和读屏抢时间的风险，必须真建出来，否则那一行永远少操作。
     * @param templateKey 成功建出菜单后存成模板，给同类行兜底。
     */
    static String[] labelsFor(View anchor, View origin, boolean dropTitle, boolean force,
                              String templateKey) {
        if (!available || anchor == null) {
            return null;
        }
        Cached cached = LABEL_CACHE.get(anchor);
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - cached.at <= LABEL_TTL_MILLIS) {
            return cached.labels;
        }
        if (!force && rateExceeded(now)) {
            // 遍历风暴中：不建。有旧缓存就先拿旧的顶上（用户 2026-10-21：
            // 会话列表「有时候没有默认操作」就是这里没兜底），同时交给延迟补建。
            return cached != null ? cached.labels : null;
        }
        String[] labels = (String[]) run(anchor, origin, PendingKind.LABELS, -1);
        if (labels != null) {
            if (dropTitle) {
                blankRowTitles(labels, anchor);
            }
            LABEL_CACHE.put(anchor, new Cached(SystemClock.elapsedRealtime(), labels));
            if (templateKey != null) {
                TEMPLATES.put(templateKey, labels);
            }
            if (VERBOSE) {
                log("labels " + String.join("/", labels));
            }
            return labels;
        }
        return cached != null ? cached.labels : null;
    }

    /**
     * 装饰时没拿到菜单（刚进页面、行还没绑好、抓取撞车）不阻塞也不放弃：
     * 稍后在主线程再抓一次，抓到后广播内容变化，让读屏重查这一行的操作。
     */
    /**
     * 只对读屏真正聚焦的那一行建菜单，建好直接塞进当前节点。
     *
     * <p>绝不能往行上广播 TYPE_WINDOW_CONTENT_CHANGED：读屏收到就会重读窗口、
     * 重新锚定无障碍焦点，用户正在操作菜单里单指滑动会被拽回列表，
     * 滑动就变成了「跳到下一个聊天」（实测反馈）。
     * 装饰本来就在 findNodeByAddress 里同步进行，读屏拿到的就是带动作的节点，
     * 不需要额外通知。
     */
    static void scheduleLabelsRetry(View anchor, View origin, boolean dropTitle) {
        if (!available || anchor == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = RETRY_ATTEMPTS.get(anchor);
        if (last != null && now - last < RETRY_THROTTLE_MILLIS) {
            return;
        }
        RETRY_ATTEMPTS.put(anchor, now);
        if (DEFERRED_IN_FLIGHT.get() >= DEFERRED_MAX) {
            // 排太多了：下一次读屏查询该行时会再次尝试（节流到期后）。
            return;
        }
        DEFERRED_IN_FLIGHT.incrementAndGet();
        // 主线程补一次（跳过节流），只写缓存不广播；下次读屏查询该行时自然吃到。
        MAIN.post(() -> {
            try {
                String[] labels = labelsFor(anchor, origin, dropTitle, true, null);
                if (labels != null && labels.length > 0) {
                    if (VERBOSE) {
                        log("deferred build got labels (" + labels.length + ")");
                    }
                }
            } finally {
                DEFERRED_IN_FLIGHT.decrementAndGet();
            }
        });
    }

    /**
     * 会话列表的弹窗有时把聊天标题本身当成一项夹在操作里。
     * 与行内任一 TextView 文本相同的项置空；位置保留，
     * 所以 execute(index) 的下标与微信原菜单仍然一一对应。
     */
    private static void blankRowTitles(String[] labels, View anchor) {
        java.util.Set<String> rowTexts = new java.util.HashSet<>();
        collectRowTexts(anchor, rowTexts, 0);
        for (int index = 0; index < labels.length; index++) {
            if (!labels[index].isEmpty() && rowTexts.contains(labels[index])) {
                log("drop row-title item [" + index + "] " + labels[index]);
                labels[index] = "";
            }
        }
    }

    private static void collectRowTexts(View view, java.util.Set<String> texts, int depth) {
        if (view == null || depth > 6 || texts.size() > 16) {
            return;
        }
        if (view instanceof android.widget.TextView textView) {
            CharSequence text = textView.getText();
            if (text != null) {
                String value = text.toString().trim();
                if (!value.isEmpty() && value.length() <= 60) {
                    texts.add(value);
                }
            }
        }
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectRowTexts(group.getChildAt(index), texts, depth + 1);
            }
        }
    }

    /**
     * 执行菜单第 index 项。必须当场重新构建菜单：
     * builder/监听器都绑着"当前这一行"的数据，复用旧菜单会 A 行选删除删掉 B 行。
     * 执行完立刻失效标签缓存（标为已读/置顶等状态已变）。
     */
    /** run() 因别的挂起正在跑而没能开始时返回的哨兵，调用方据此决定要不要稍后重试。 */
    private static final Object BUSY = new Object();
    private static volatile boolean lastRunBusy;

    /** 上一次 execute 是不是撞上别的挂起（没执行成功但可以重试）。 */
    static boolean lastRunBusy() {
        return lastRunBusy;
    }

    static boolean execute(View anchor, View origin, int index) {
        if (!available || anchor == null || index < 0) {
            return false;
        }
        LABEL_CACHE.remove(anchor);
        lastRunBusy = false;
        Object result = run(anchor, origin, PendingKind.EXECUTE, index);
        if (result == BUSY) {
            lastRunBusy = true;
            return false;
        }
        return Boolean.TRUE.equals(result);
    }

    private static Object run(View anchor, View origin, PendingKind kind, int slot) {
        if (!begin(kind, slot)) {
            log("pending busy, skip " + kind);
            return BUSY;
        }
        boolean pressed = press(anchor, origin);
        if (pressed && hookFired) {
            // 长按链路同步命中（hook 在 performLongClick 内触发）。
            Object result = settle(kind);
            resetPending();
            return result;
        }
        // 异步兜底：留窗口期收尾。标签迟到会进缓存（下一轮装饰用），
        // 执行迟到了只能回 false——读屏用户重按一次即可，旧机制同样如此。
        scheduleCollect(anchor);
        return null;
    }

    private static boolean press(View anchor, View origin) {
        try {
            return PhoneMenuBridge.pressForMenu(anchor, origin, true);
        } catch (Throwable throwable) {
            log("press failed " + throwable.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * f/g/h/i/j 入口拦截；p 兜底路径（buildIdx/listenerIdx 都为 -1）从 r 字段取。
     */
    private static final java.util.Set<String> LOGGED_INTERCEPTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static Object interceptEntry(XposedInterface.Chain chain,
                                         int buildIdx, int listenerIdx) throws Throwable {
        java.lang.reflect.Executable hookMethod = chain.getExecutable();
        // 只有 p 是 boolean 返回，f/g/h/i/j 都是 void，跳过时返回 null。
        boolean booleanEntry = chain.getExecutable() == pShowMethod;
        if (!pendingActive()) {
            if (PASSTHROUGH_LOGS.add(hookMethod.getName())) {
                log("wx menu passthrough " + hookMethod.getName());
            }
            return chain.proceed();
        }
        // hq5.r 实例：原 f()/g() 会注册弹窗 dismiss → r.onDismiss → 外部监听器。
        // 跳过弹窗后必须手动补这个链路，否则微信停在「菜单开着」。
        Object menuSelf = chain.getThisObject();
        if (LOGGED_INTERCEPTS.add(hookMethod.getName())) {
            log("intercepted " + hookMethod.getName() + " buildIdx=" + buildIdx);
        }
        try {
            View view;
            Object buildTarget;
            Object t4;
            if (buildIdx < 0) {
                Object self = chain.getThisObject();
                buildTarget = fieldOf(self, "w");
                if (buildTarget == null) {
                    buildTarget = fieldOf(self, "s");
                }
                t4 = fieldOf(self, "v");
                Object anchor = fieldOf(self, "f");
                if (!(anchor instanceof View) || buildTarget == null) {
                    log("p path incomplete (anchor=" + (anchor != null)
                            + " buildTarget=" + (buildTarget != null) + ")");
                    return booleanEntry ? Boolean.FALSE : null;
                }
                view = (View) anchor;
            } else {
                Object anchorArg = chain.getArg(0);
                if (!(anchorArg instanceof View)) {
                    return chain.proceed();
                }
                view = (View) anchorArg;
                buildTarget = buildIdx >= 0 ? chain.getArg(buildIdx) : null;
                t4 = listenerIdx >= 0 ? chain.getArg(listenerIdx) : null;
            }
            if (buildTarget == null) {
                log("no build target");
                return booleanEntry ? Boolean.FALSE : null;
            }
            buildAndUse(view, buildTarget, t4);
            // 假关闭：调用方在 f() 之后还会置「菜单开着」状态（聊天页
            // jg5.d.Q/W、会话列表同理），靠弹窗 dismiss 才能恢复——弹窗不存在，
            // 就手动补。post 到主线程让调用方后续代码先跑完，再收摊。
            scheduleFakeDismiss(menuSelf, view);
        } catch (Throwable throwable) {
            StackTraceElement[] stack = throwable.getStackTrace();
            log("intercept failed " + throwable.getClass().getSimpleName()
                    + (throwable.getMessage() != null ? ": " + throwable.getMessage() : "")
                    + (stack.length > 0 ? " @ " + stack[0] + " | " + stack[1] : ""));
            return booleanEntry ? Boolean.FALSE : null;
        }
        // 不调原方法：弹窗不创建。void 方法返回 null 即跳过。
        return booleanEntry ? Boolean.FALSE : null;
    }

    /**
     * 手动补一次菜单关闭链路（fake dismiss）。
     *
     * <p>微信调用方在 f()（弹窗构建+显示）之后会设置「菜单开着」状态：
     * 聊天页 r0 长按后 Q(false)+W(false)+needUpdateUI 标记、Rb(4) 场景；
     * 会话列表同理。这些状态直到弹窗 dismiss 时 invoke
     * r.onDismiss() → 外部 I 监听器（p0/r3 …）才会恢复页面焦点、
     * 回放被挂起的更新队列。我们根本不创建弹窗，
     * 状态机就会永远停在「菜单开着」——用户 2026-10-21 的
     * 「发了消息不上屏/会话列表不刷新」就是这个：更新全被收进
     * ChattingContext 的队列等回放，而回放永远不触发。
     *
     * <p>为避免 NPE 先补上 r.f/r（onDismiss 里 setSelected 用），
     * 然后 MAIN.post 让调用方 f() 之后的代码先跑完（它会把状态置成
     * 「菜单开」），我们再关一次——和真弹窗关闭同路。
     */
    private static void scheduleFakeDismiss(Object menuSelf, View anchor) {
        if (menuSelf == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                setFieldIfNull(menuSelf, "f", anchor);
                setFieldIfNull(menuSelf, "r", anchor);
                Method onDismiss = menuSelf.getClass().getMethod("onDismiss");
                onDismiss.setAccessible(true);
                onDismiss.invoke(menuSelf);
                if (VERBOSE) {
                    log("fake dismiss done");
                }
            } catch (Throwable throwable) {
                log("fake dismiss failed " + throwable.getClass().getSimpleName());
            }
        });
    }

    private static void setFieldIfNull(Object self, String name, Object value) {
        try {
            java.lang.reflect.Field field = self.getClass().getField(name);
            field.setAccessible(true);
            if (field.get(self) == null) {
                field.set(self, value);
            }
        } catch (Throwable ignored) {
            // 字段不存在/类型不符就不管：onDismiss 里相应判空。
        }
    }

    private static void buildAndUse(View view, Object buildTarget, Object t4) throws Exception {
        Object menu = menuCtor.newInstance(view.getContext());
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) menuItemsField.get(menu);
        // 微信的菜单构建器多数都读 menuInfo.position（如会话行 r3.onCreateContextMenu）。
        // 手动长按时这个 info 由 AdapterView 填，这里我们也自己隐：
        // 从锚点往上找 AdapterView，用 getPositionForView 还原真实位置。
        ContextMenu.ContextMenuInfo info = menuInfoFor(view);
        if (buildTarget instanceof View.OnCreateContextMenuListener occListener) {
            occListener.onCreateContextMenu((ContextMenu) menu, view, info);
        } else {
            Method build = findBuildMethod(buildTarget);
            if (build == null) {
                log("builder method not found on " + buildTarget.getClass().getName());
                return;
            }
            build.invoke(buildTarget, menu, view, info);
        }
        hookFired = true;
        if (VERBOSE) {
            log("menu built items=" + items.size() + " kind=" + pendingKind);
        }
        if (pendingKind == PendingKind.LABELS) {
            String[] titles = new String[items.size()];
            for (int index = 0; index < items.size(); index++) {
                Object raw = items.get(index);
                CharSequence title =
                        raw instanceof MenuItem menuItem ? menuItem.getTitle() : null;
                titles[index] = title == null ? "" : title.toString();
            }
            setCaptured(titles);
        } else if (pendingKind == PendingKind.EXECUTE) {
            invokeExecute(t4, items, pendingSlot);
        }
    }

    private static ContextMenu.ContextMenuInfo menuInfoFor(View anchor) {
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) anchor.getParent();
            while (parent != null && !(parent instanceof android.widget.AdapterView)) {
                parent = (android.view.ViewGroup)
                        (parent.getParent() instanceof android.view.ViewGroup
                                ? parent.getParent() : null);
            }
            if (parent instanceof android.widget.AdapterView<?> adapter) {
                int position = adapter.getPositionForView(anchor);
                if (position >= 0) {
                    long id;
                    try {
                        id = adapter.getItemIdAtPosition(position);
                    } catch (RuntimeException ignored) {
                        id = position;
                    }
                    return new android.widget.AdapterView.AdapterContextMenuInfo(
                            anchor, position, id);
                }
            }
        } catch (Throwable throwable) {
            log("menuInfo failed " + throwable.getClass().getSimpleName());
        }
        return null;
    }

    /** 执行：用当场捕获的监听器调 onMMMenuItemSelected(item, 下标)，与用户点弹窗完全同路。 */
    private static void invokeExecute(Object t4, List<Object> items, int slot) throws Exception {
        if (t4 == null) {
            log("no t4 listener for execute");
            return;
        }
        if (slot < 0 || slot >= items.size()) {
            log("execute slot out of range " + slot + "/" + items.size());
            return;
        }
        Method selected = findItemSelectedListener(t4);
        if (selected == null) {
            log("listener method not found on " + t4.getClass().getName());
            return;
        }
        selected.invoke(t4, items.get(slot), slot);
        synchronized (PENDING_LOCK) {
            executeFinished = true;
        }
    }

    private static Method findBuildMethod(Object builder) {
        for (Method method : builder.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 3 && types[0] == menuClass
                    && View.class.isAssignableFrom(types[1])) {
                return method;
            }
        }
        return null;
    }

    private static Method findItemSelectedListener(Object listener) {
        for (Method method : listener.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if ("onMMMenuItemSelected".equals(method.getName())
                    && types.length == 2
                    && MenuItem.class.isAssignableFrom(types[0])
                    && types[1] == int.class) {
                return method;
            }
        }
        return null;
    }

    private static Object fieldOf(Object self, String name) {
        try {
            Field field = self.getClass().getField(name);
            field.setAccessible(true);
            return field.get(self);
        } catch (Throwable throwable) {
            return null;
        }
    }

    // ---- 挂起状态管理 ----

    private static boolean begin(PendingKind kind, int slot) {
        synchronized (PENDING_LOCK) {
            if (pendingKind != PendingKind.NONE) {
                if (SystemClock.elapsedRealtime() - pendingAt <= PENDING_WINDOW_MILLIS) {
                    return false;
                }
                // 上一次挂起过了窗口期还没收尾（异步链路断了）：
                // 直接接管，不能把后面的抓取全堵死——那正是「进微信没操作」的一种成因。
                log("take over stale pending " + pendingKind);
            }
            pendingKind = kind;
            pendingAt = SystemClock.elapsedRealtime();
            pendingSlot = slot;
            hookFired = false;
            executeFinished = false;
            capturedLabels = null;
            return true;
        }
    }

    private static boolean pendingActive() {
        synchronized (PENDING_LOCK) {
            return pendingKind != PendingKind.NONE
                    && SystemClock.elapsedRealtime() - pendingAt <= PENDING_WINDOW_MILLIS;
        }
    }

    private static void setCaptured(String[] labels) {
        synchronized (PENDING_LOCK) {
            capturedLabels = labels;
        }
    }

    private static Object settle(PendingKind kind) {
        synchronized (PENDING_LOCK) {
            if (kind == PendingKind.LABELS) {
                return capturedLabels;
            }
            return executeFinished;
        }
    }

    private static void resetPending() {
        synchronized (PENDING_LOCK) {
            pendingKind = PendingKind.NONE;
            pendingSlot = -1;
            hookFired = false;
            capturedLabels = null;
        }
    }

    /** 异步兜底：每 80ms 查一次是否命中，命中/超时后收摊。 */
    private static void scheduleCollect(View anchor) {
        Runnable[] ticker = new Runnable[1];
        ticker[0] = new Runnable() {
            private int tick;

            @Override
            public void run() {
                String[] labels;
                boolean fired;
                boolean finished;
                PendingKind kind;
                synchronized (PENDING_LOCK) {
                    fired = hookFired;
                    labels = capturedLabels;
                    finished = executeFinished;
                    kind = pendingKind;
                }
                if (kind != PendingKind.NONE && !fired
                        && tick < COLLECT_MAX_TICKS) {
                    tick++;
                    MAIN.postDelayed(this, COLLECT_INTERVAL_MILLIS);
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                resetPending();
                if (fired && kind == PendingKind.LABELS && labels != null) {
                    LABEL_CACHE.put(anchor, new Cached(now, labels));
                    log("async labels cached (" + labels.length + ")");
                } else if (fired && kind == PendingKind.EXECUTE && finished) {
                    log("async execute finished after timeout window");
                } else {
                    log("menu window timeout");
                }
            }
        };
        MAIN.postDelayed(ticker[0], COLLECT_INTERVAL_MILLIS);
    }

    private static void log(String message) {
        if (!VERBOSE) {
            return;
        }
        XposedModule module = host;
        if (module != null) {
            try {
                module.log(android.util.Log.INFO, TAG, message);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
