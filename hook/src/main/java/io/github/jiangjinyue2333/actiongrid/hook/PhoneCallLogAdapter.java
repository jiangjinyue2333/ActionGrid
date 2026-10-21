package io.github.jiangjinyue2333.actiongrid.hook;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 电话/短信 App：给列表行补「默认操作」。
 *
 * 动作顺序：行本身的默认动作 → 行内按钮 → 官方长按菜单的各项。
 * 菜单项不缓存，每次让这一行自己构建菜单，再在窗口添加前拦下并直接执行，
 * 用户体感是双击即执行。
 */
final class PhoneCallLogAdapter {
    private static final String TAG = "ActionGrid-Phone";
    private static final String CONTACTS = "com.android.contacts";
    private static final String MMS = "com.android.mms";

    /** 通话记录行：点击=拨打，行内按钮=通话记录详情，其余来自官方菜单。 */
    private static final Spec CALL_LOG = new Spec(CONTACTS,
            CONTACTS + ":id/content_layout", null, 0x6B000001,
            new Entry(0, "拨打电话", Kind.ROW_CLICK, null),
            new Entry(1, "通话记录详情", Kind.CHILD_CLICK, "secondary_action_view"),
            new Entry(2, "发送短信", Kind.MENU, null),
            new Entry(3, "拨打IP电话", Kind.MENU, null),
            new Entry(4, "呼叫前编辑", Kind.MENU, null),
            new Entry(5, "复制号码", Kind.MENU, null),
            new Entry(6, "标记", Kind.MENU, null),
            new Entry(7, "删除", Kind.MENU, null),
            new Entry(8, "多选", Kind.MENU, null));

    /** 联系人行：点击=查看详情，其余来自官方菜单。 */
    private static final Spec CONTACT = new Spec(CONTACTS,
            CONTACTS + ":id/contact_list_item_view", null, 0x6B000101,
            new Entry(0, "查看", Kind.ROW_CLICK, null),
            new Entry(1, "编辑", Kind.MENU, null),
            new Entry(2, "添加收藏", Kind.MENU, null, "添加收藏", "取消收藏"),
            new Entry(3, "发送到桌面", Kind.MENU, null),
            new Entry(4, "删除", Kind.MENU, null),
            new Entry(5, "多选", Kind.MENU, null));

    /** 已存联系人的号码块。 */
    private static final Spec NUMBER_ACTIONS = new Spec(CONTACTS,
            CONTACTS + ":id/content_actions_view_container", null, 0x6B000201,
            new Entry(0, "拨打电话", Kind.CHILD_CLICK, "first_action_button"),
            new Entry(1, "发送短信", Kind.CHILD_CLICK, "secondary_action_button"),
            new Entry(2, "复制到剪贴板", Kind.MENU, null),
            new Entry(3, "拨打IP电话", Kind.MENU, null));

    /** 未存联系人的号码详情行。 */
    private static final Spec DETAIL_NUMBER = new Spec(CONTACTS,
            CONTACTS + ":id/detail_list_item", null, 0x6B000301,
            new Entry(0, "拨打电话", Kind.CHILD_CLICK, "first_action_button"),
            new Entry(1, "发送短信", Kind.CHILD_CLICK, "secondary_action_button"),
            new Entry(2, "复制到剪贴板", Kind.MENU, null),
            new Entry(3, "拨打IP电话", Kind.MENU, null));

    /** 短信会话行：这一版没有 resource-id，只能按类名匹配。 */
    private static final Spec SMS_CONVERSATION = new Spec(MMS,
            null, null, "HyperCellLayout", 0x6B000401,
            new Entry(0, "打开会话", Kind.ROW_CLICK, null),
            new Entry(1, "标为已读", Kind.MENU, null, "标为已读", "标为未读"),
            new Entry(2, "置顶", Kind.MENU, null, "置顶", "取消置顶"),
            new Entry(3, "删除", Kind.MENU, null),
            new Entry(4, "多选", Kind.MENU, null));

    /** 短信气泡：收藏/复制/转发/详情 等来自官方菜单。 */
    private static final Spec SMS_MESSAGE = new Spec(MMS,
            MMS + ":id/message_list_item", null, 0x6B000501,
            new Entry(0, "收藏", Kind.MENU, null, "收藏", "取消收藏"),
            new Entry(1, "复制", Kind.MENU, null),
            new Entry(2, "转发", Kind.MENU, null),
            new Entry(3, "详情", Kind.MENU, null),
            new Entry(4, "删除", Kind.MENU, null),
            new Entry(5, "反馈", Kind.MENU, null),
            new Entry(6, "多选", Kind.MENU, null));

    private static final java.util.Set<String> LOGGED_TYPES = new java.util.HashSet<>();
    private static final java.util.Map<String, String[]> DISCOVERED_MENUS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> DISCOVERING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Map<String, Integer> DISCOVERY_FAILS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 诊断开关：平时关闭（省电、防 log 刷屏）；排查问题改 true 重编。 */
    private static final boolean DEBUG = false;
    /** 诊断/探针总开关（功能稳定后默认关闭，省电）。
     *  排查问题临时改 true 重新构建即可. */
    private static final boolean VERBOSE = false;

    /** 消息类型指纹：行的子 View 类名组合。
     *  自研容器拿不到数据位置（getPositionForView 恒 -1），但每种消息类型的
     *  子 View 结构天然不同（文字/图片/语音气泡类名不一样），足够区分菜单。 */
    private static String typeFingerprint(View item) {
        if (!(item instanceof ViewGroup group)) {
            return "?";
        }
        StringBuilder fingerprint = new StringBuilder();
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child != null) {
                fingerprint.append(child.getClass().getSimpleName()).append('.');
            }
        }
        return fingerprint.toString();
    }

    /** 聊天消息的菜单缓存键：行类名 + 类型指纹。 */
    private static String chatMenuKey(View row) {
        return "wxmsg:" + row.getClass().getSimpleName() + ":" + typeFingerprint(row);
    }

    /** 气泡子 View 往上找所属的消息 item：
     *  ① 类名含 viewitems.ChattingItem；或
     *  ② 父 View 是 ScrollControlRecyclerView（聊天列表，结构判定不依赖混淆名）。 */
    private static View findChatItemAncestor(View view) {
        StringBuilder chain = new StringBuilder();
        View current = view;
        for (int i = 0; i < 10 && current != null; i++) {
            String name = current.getClass().getName();
            if (name.endsWith("viewitems.ChattingItem")) {
                return current;
            }
            ViewParent parent = current.getParent();
            if (parent != null
                    && parent.getClass().getName().endsWith("ScrollControlRecyclerView")) {
                return current;
            }
            chain.append(current.getClass().getSimpleName()).append(" <- ");
            current = parent instanceof View v ? v : null;
        }
        if (DEBUG) {
            // 真身未知时把走过的父链记下来（每条不同链只记一次）。
            String chainStr = chain.toString();
            if (ANCESTOR_CHAINS_LOGGED.add(chainStr)) {
                log("chat ancestor chain: " + chainStr + "end");
            }
        }
        return null;
    }

    private static final java.util.Set<String> ANCESTOR_CHAINS_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int MENU_DYNAMIC_OFFSET = 100;
    private static final int MENU_DYNAMIC_LIMIT = 32;

    /** 验证码类会话行：自定义 View，类名与普通会话不同。 */
    private static final Spec SMS_VERIFY_CODE = new Spec(MMS,
            null, null, "VerificationCodeListItem", 0x6B000421,
            new Entry(0, "打开会话", Kind.ROW_CLICK, null),
            new Entry(1, "标为已读", Kind.MENU, null, "标为已读", "标为未读"),
            new Entry(2, "置顶", Kind.MENU, null, "置顶", "取消置顶"),
            new Entry(3, "删除", Kind.MENU, null),
            new Entry(4, "多选", Kind.MENU, null));

    /** 微信会话行：默认操作改为「激活」，菜单项自动 dismiss 弹窗。 */
    private static final Spec WECHAT_CONVERSATION = new Spec("com.tencent.mm",
            null, null, "ConversationFolderItemView", 0x6B000601, false,
            // 标为已读/未读：根据当前已读状态动态显示文案。
            new Entry(0, Kind.MENU, null, view -> {
                        boolean[] state = WeChatProbe.probe(view);
                        if (state == null) {
                            return new String[]{"标为已读", "标为未读"};
                        }
                        return state[1] ? new String[]{"标为已读"} : new String[]{"标为未读"};
                    }, "标为已读", "标为未读"),
            // 置顶/取消置顶：根据当前置顶状态动态显示文案。
            new Entry(1, Kind.MENU, null, view -> {
                        boolean[] state = WeChatProbe.probe(view);
                        if (state == null) {
                            return new String[]{"置顶该聊天", "取消置顶", "置顶", "取消置顶该聊天"};
                        }
                        return state[0] ? new String[]{"取消置顶"} : new String[]{"置顶该聊天"};
                    }, "置顶该聊天", "取消置顶", "置顶", "取消置顶该聊天"),
            new Entry(2, "不显示该聊天", Kind.MENU, null),
            new Entry(3, "删除该聊天", Kind.MENU, null));

    private static final Spec WECHAT_CHAT = new Spec(
            "com.tencent.mm", null, null, "viewitems.ChattingItem",
            0x6B000700, false);

    private static final Spec[] SPECS = {
            CALL_LOG, CONTACT, NUMBER_ACTIONS, DETAIL_NUMBER,
            SMS_CONVERSATION, SMS_VERIFY_CODE, SMS_MESSAGE, WECHAT_CONVERSATION, WECHAT_CHAT};

    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    private static XposedModule host;
    private static Method nodeIsSealed;
    private static Method nodeSetSealed;
    private static Method findViewByAccessibilityId;
    private static Method nodeSourceId;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean installed;

    private PhoneCallLogAdapter() {
    }

    static synchronized boolean install(XposedModule module, ClassLoader appClassLoader) {
        if (installed) {
            return true;
        }
        try {
            Class<?> controllerClass =
                    Class.forName("android.view.AccessibilityInteractionController");
            Class<?> callbackClass = Class.forName(
                    "android.view.accessibility.IAccessibilityInteractionConnectionCallback");

            Method returnSingleResult = accessible(controllerClass.getDeclaredMethod(
                    "returnFindNodeResult", AccessibilityNodeInfo.class, callbackClass, int.class));
            Method returnListResult = accessible(controllerClass.getDeclaredMethod(
                    "returnFindNodesResult", List.class, callbackClass, int.class));
            Method performActionClientThread = accessible(controllerClass.getDeclaredMethod(
                    "performAccessibilityActionClientThread", long.class, int.class,
                    Bundle.class, int.class, callbackClass, int.class, int.class, long.class));
            findViewByAccessibilityId = accessible(controllerClass.getDeclaredMethod(
                    "findViewByAccessibilityId", int.class));
            nodeSourceId = accessible(
                    AccessibilityNodeInfo.class.getDeclaredMethod("getSourceNodeId"));
            nodeIsSealed = accessible(
                    AccessibilityNodeInfo.class.getDeclaredMethod("isSealed"));
            nodeSetSealed = accessible(
                    AccessibilityNodeInfo.class.getDeclaredMethod("setSealed", boolean.class));
            host = module;

            module.hook(returnSingleResult)
                    .setId("actiongrid.phone.node.single.v1")
                    .intercept(chain -> {
                        if (chain.getArg(0) instanceof AccessibilityNodeInfo node) {
                            decorate(node, chain.getThisObject());
                        }
                        return chain.proceed();
                    });
            module.hook(returnListResult)
                    .setId("actiongrid.phone.node.list.v1")
                    .intercept(chain -> {
                        if (chain.getArg(0) instanceof List<?> nodes) {
                            Object controller = chain.getThisObject();
                            for (Object item : nodes) {
                                if (item instanceof AccessibilityNodeInfo node) {
                                    decorate(node, controller);
                                }
                            }
                        }
                        return chain.proceed();
                    });
            module.hook(performActionClientThread)
                    .setId("actiongrid.phone.action.v1")
                    .intercept(chain -> handleAction(chain));

            PhoneMenuBridge.install(module);

            boolean wxMenuHook = WeChatMenuHook.install(module, appClassLoader);
            log("wechat menu hook " + (wxMenuHook ? "installed" : "unavailable"));

            installed = true;
            log("app adapter installed");
            return true;
        } catch (Throwable throwable) {
            log("install failed " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
            return false;
        }
    }

    private static final java.util.Set<String> SEEN_NODE_CLASSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Set<String> ROW_CLASSES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void decorate(AccessibilityNodeInfo node, Object controller) {
        try {
            String viewId = node.getViewIdResourceName();
            String className = String.valueOf(node.getClassName());
            String packageName = String.valueOf(node.getPackageName());
            if (WECHAT_PACKAGE.equals(packageName) && SEEN_NODE_CLASSES.add(className)) {
                // 诊断：微信里出现的每个节点类名记一次，用来找折叠行这类特殊行的真身。
                log("wx node class " + className + " viewId=" + viewId);
            }
            View rowView = null;
            String viewClassName = null;
            boolean resolved = false;
            View focusView = null;
            for (Spec spec : SPECS) {
                if (!spec.packageName.equals(packageName)) {
                    continue;
                }
                boolean idMatch = spec.rowViewId != null && spec.rowViewId.equals(viewId);
                boolean classMatch = spec.classSuffix != null
                        && className.endsWith(spec.classSuffix);
                if (!idMatch && !classMatch) {
                    if (spec.viewClassSuffix == null) {
                        continue;
                    }
                    if (!resolved) {
                        resolved = true;
                        rowView = resolveRowView(node, controller);
                        viewClassName = rowView == null ? null : rowView.getClass().getName();
                        if (DEBUG && WECHAT_PACKAGE.equals(packageName)
                                && rowView != null) {
                            if (ROW_CLASSES.add(viewClassName)) {
                                log("wx row class " + viewClassName);
                            }
                            // 会话行、各类消息行各 dump 一份结构（按类去重）。
                            WeChatDumper.dumpOnce(rowView);
                        }
                        focusView = rowView;
                    }
                    if (viewClassName == null
                            || !viewClassName.endsWith(spec.viewClassSuffix)) {
                        if (spec == WECHAT_CHAT && rowView != null) {
                            // 天坦常聚焦气泡子 View，往上找所属的消息 item，
                            // 动作挂在聚焦的节点上。
                            View item = findChatItemAncestor(rowView);
                            if (item == null) {
                                continue;
                            }
                            if (DEBUG) {
                                log("chat ancestor hit "
                                        + item.getClass().getSimpleName());
                            }
                            rowView = item;
                        } else if (spec == WECHAT_CONVERSATION && rowView != null) {
                            // 同理：聚焦的在会话行里面的文字子 View 时，
                            // 沿视图树往上找会话行根，动作仍然挂在聚焦节点上。
                            View ancestor = findRowAncestor(rowView, spec.viewClassSuffix);
                            if (ancestor == null) {
                                continue;
                            }
                            rowView = ancestor;
                        } else {
                            continue;
                        }
                    }
                }
                decorateRow(node, spec, rowView, focusView, controller,
                        node.isAccessibilityFocused() || node.isFocused());
                return;
            }
        } catch (Throwable throwable) {
            // 节点读取失败不影响原行为，但要留痕：聊天页曾在这里默默折断。
            log("decorate failed " + throwable);
        }
    }

    private static void decorateRow(AccessibilityNodeInfo node, Spec spec,
                                    View rowView, View focusView, Object controller,
                                    boolean focused) {
        if (DEBUG && WECHAT_PACKAGE.equals(spec.packageName)) {
            // 一次性诊断：拿真实结构，不再盲猜字段名。
            WeChatDumper.dumpOnce(rowView);
        }
        // 聊天页：直连钩子（今天这套）。
        // 会话列表：实测后回退到更稳的「预设+静默发现+弹窗拦截」方案，
        // 直连钩子只留给聊天页（消息类型多、直连更准）。
        if (WECHAT_PACKAGE.equals(spec.packageName) && spec == WECHAT_CHAT) {
            int injected = 0;
            if (WeChatMenuHook.isAvailable() && rowView != null) {
                // 会话列表弹窗会把聊天标题夹成一项，喂给钩子过滤；
                // 聊天页菜单都是操作名，不筛。
                boolean dropTitle = spec == WECHAT_CONVERSATION;
                // 快速滑动时读屏会遍历窗口里所有节点，每个都同步重建一次菜单会把
                // 主线程压死，读屏等超时就播报「无可用操作」。所以：
                //   · 真正被聚焦的行 → 同步建，立刻可用；
                //   · 其余行 → 只吃缓存，没缓存就异步预热，不堵主线程。
                // origin=聚焦的节点：长按从它往上找监听器，别按到头像。
                String[] menu = WeChatMenuHook.labelsFor(rowView, focusView, false, focused,
                        wxTemplateKey(spec, rowView));
                if (menu == null || menu.length == 0) {
                    // 这一行自己的菜单还没建好（限流/刚进页面）：
                    // 先用同类行的模板把操作顶上——绝不能出现「这一行没操作」。
                    menu = WeChatMenuHook.templateLabels(wxTemplateKey(spec, rowView));
                    WeChatMenuHook.scheduleLabelsRetry(rowView, focusView, false);
                }
                if (menu != null) {
                    for (int menuIndex = 0;
                         menuIndex < menu.length && menuIndex < MENU_DYNAMIC_LIMIT;
                         menuIndex++) {
                        if (!menu[menuIndex].isEmpty()) {
                            addAction(node, spec.base + MENU_DYNAMIC_OFFSET + menuIndex,
                                    menu[menuIndex]);
                            injected++;
                        }
                    }
                }
                if (WECHAT_PACKAGE.equals(spec.packageName)) {
                    // 直接看节点上真实带了哪些动作：读屏显示的菜单就来自这里，
                    // 与日志里的 injected 数对不上时靠它定位。
                    dumpActionsOnce(spec, rowView, node);
                }
            }
            if (VERBOSE) {
                log("wx fastpath " + spec.key()
                        + " hook=" + WeChatMenuHook.isAvailable()
                        + " injected=" + injected);
            }
            return;
        }
        String key = spec.key();
        if (WECHAT_CHAT == spec && rowView != null) {
            // 聊天消息：同一 spec 覆盖多种消息行（文本/图片/语音…），
            // 菜单按行类名+消息类型分开发现、分开缓存，避免拿文本的菜单塞给图片。
            key = chatMenuKey(rowView);
            if (DISCOVERED_MENUS.get(key) == null) {
                // autoDiscover 关着（防 B 段重复注入），发现在这里手动触发。
                scheduleDiscovery(key, spec, node, controller, rowView, focusView);
            }
        }
        String[] menu = DISCOVERED_MENUS.get(key);
        if (menu == null) {
            menu = spec.presetMenuLabels();
            if (spec.autoDiscover) {
                scheduleDiscovery(key, spec, node, controller, rowView, focusView);
            }
        }
        // 公众号/服务号等聚合行：官方菜单只有不显示/删除，置顶和已读不适用，直接不注入。
        boolean officialRow = WECHAT_PACKAGE.equals(spec.packageName)
                && WeChatProbe.isOfficialAccount(rowView);
        if (DEBUG && WECHAT_PACKAGE.equals(spec.packageName)) {
            // 诊断：记录每个出现过的 username（特殊行排查用）。
            WeChatProbe.logUsernameOnce(rowView);
        }
        for (Entry entry : spec.entries) {
            if (officialRow && entry.labelResolver != null) {
                continue;
            }
            if (entry.kind != Kind.MENU) {
                addAction(node, entry.actionId(spec), entry.displayLabel(rowView));
            } else if (entry.labelResolver != null) {
                // 动态朗读文案：根据当前行状态显示「置顶/取消置顶」或「标为已读/标为未读」。
                addAction(node, entry.actionId(spec), entry.displayLabel(rowView));
            }
        }
        for (int index = 0; index < menu.length && index < MENU_DYNAMIC_LIMIT; index++) {
            addAction(node, spec.base + MENU_DYNAMIC_OFFSET + index, menu[index]);
        }
        if (DEBUG) {
            dumpActionsOnce(spec, rowView, node);
        }
    }

    /** 第一次遇到这类行：后台静默长按一次，把官方菜单项全量读下来。 */
    private static void scheduleDiscovery(String key, Spec spec, AccessibilityNodeInfo node,
                                          Object controller, View rowView, View focusView) {
        Integer fails = DISCOVERY_FAILS.get(key);
        if (fails != null && fails >= 6) {
            return;
        }
        if (!DISCOVERING.add(key)) {
            return;
        }
        MAIN.post(() -> {
            View target = rowView != null ? rowView : resolveRowView(node, controller);
            if (target == null) {
                DISCOVERING.remove(key);
                return;
            }
            PhoneMenuBridge.discoverMenu(target, focusView, labels -> {
                try {
                    if (labels != null && labels.length > 0) {
                        DISCOVERED_MENUS.put(key, labels);
                        log("discovered " + key + " -> " + String.join("/", labels));
                    } else {
                        DISCOVERY_FAILS.merge(key, 1, Integer::sum);
                        log("discovered " + key + " empty (fail "
                                + DISCOVERY_FAILS.get(key) + ")");
                    }
                } finally {
                    DISCOVERING.remove(key);
                }
            });
        });
    }

    /** 无障碍节点没有 id 时，用节点反查真实 View 的类名。 */
    private static View resolveRowView(AccessibilityNodeInfo node, Object controller) {
        try {
            if (!node.isClickable() || controller == null) {
                return null;
            }
            long nodeId = ((Number) nodeSourceId.invoke(node)).longValue();
            View view = (View) findViewByAccessibilityId.invoke(controller, (int) nodeId);
            if (view != null) {
                String key = "view:" + view.getClass().getName();
                synchronized (LOGGED_TYPES) {
                    if (LOGGED_TYPES.size() < 30 && LOGGED_TYPES.add(key)) {
                        log("resolved view class " + view.getClass().getName());
                    }
                }
            }
            return view;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 从子 View 沿视图树往上找类名匹配的行根（最多 10 层）。 */
    private static View findRowAncestor(View start, String classSuffix) {
        ViewParent parent = start.getParent();
        for (int depth = 0; depth < 10 && parent instanceof View view; depth++) {
            if (view.getClass().getName().endsWith(classSuffix)) {
                return view;
            }
            parent = view.getParent();
        }
        return null;
    }

    /** 每类行 dump 前 3 个节点，便于看行间差异；再多是噪音。 */
    private static final int ACTIONS_DUMP_LIMIT = 3;
    private static final java.util.Map<String, Integer> ACTIONS_DUMPED =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** 每类行只打印一次最终注入的全部 action，验证到底塞了几条、有没有重复。 */
    /** 模板键：同一类行共用一个菜单模板（公众号行只有两项，单独一类）。 */
    private static String wxTemplateKey(Spec spec, View rowView) {
        String cls = rowView == null ? "" : rowView.getClass().getName();
        String suffix = "";
        if (spec == WECHAT_CONVERSATION && rowView != null) {
            try {
                if (WeChatProbe.isOfficialAccount(rowView)) {
                    suffix = "|official";
                }
            } catch (Throwable ignored) {
                // 探测失败就当普通行
            }
        }
        return spec.key() + "|" + cls + suffix;
    }

    private static void dumpActionsOnce(Spec spec, View rowView, AccessibilityNodeInfo node) {
        String key = spec.key();
        if (WECHAT_CHAT == spec && rowView != null) {
            key = chatMenuKey(rowView);
        }
        if (!WECHAT_PACKAGE.equals(spec.packageName)) {
            return;
        }
        synchronized (ACTIONS_DUMPED) {
            int seen = ACTIONS_DUMPED.getOrDefault(key, 0);
            if (seen >= ACTIONS_DUMP_LIMIT) {
                return;
            }
            ACTIONS_DUMPED.put(key, seen + 1);
        }
        try {
            List<AccessibilityNodeInfo.AccessibilityAction> list = node.getActionList();
            StringBuilder builder = new StringBuilder("actions for ")
                    .append(spec.key()).append(" count=").append(list.size());
            for (AccessibilityNodeInfo.AccessibilityAction action : list) {
                builder.append("\n  id=0x").append(Integer.toHexString(action.getId()))
                        .append(" label=").append(action.getLabel());
            }
            log(builder.toString());
        } catch (Throwable throwable) {
            log("dumpActions failed " + throwable.getClass().getSimpleName());
        }
    }

    private static void addAction(AccessibilityNodeInfo node, int id, String label) {
        try {
            // 去重：同一个节点在一次窗口遍历里会被反复装饰，
            // 不去重就会把同名动作堆上去，读屏菜单里出现重复项。
            List<AccessibilityNodeInfo.AccessibilityAction> existing = node.getActionList();
            for (int index = 0; index < existing.size(); index++) {
                if (existing.get(index).getId() == id) {
                    return;
                }
            }
            boolean sealed = (boolean) nodeIsSealed.invoke(node);
            if (sealed) {
                nodeSetSealed.invoke(node, false);
            }
            node.addAction(new AccessibilityNodeInfo.AccessibilityAction(id, label));
            if (sealed) {
                nodeSetSealed.invoke(node, true);
            }
        } catch (Throwable throwable) {
            log("addAction failed " + throwable.getClass().getSimpleName());
        }
    }

    /** 每个 action id 只打一次，确认读屏到底有没有把动作发进来。 */
    private static final java.util.Set<Integer> SEEN_ACTIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static Object handleAction(XposedInterface.Chain chain) throws Throwable {
        long nodeId = (long) chain.getArg(0);
        int action = (int) chain.getArg(1);
        if (VERBOSE && SEEN_ACTIONS.add(action)) {
            log("action in id=0x" + Integer.toHexString(action) + " node=" + nodeId);
        }
        Match match = resolve(action);
        if (match == null) {
            return chain.proceed();
        }
        Object controller = chain.getThisObject();
        Object callback = chain.getArg(4);
        int interactionId = (int) chain.getArg(3);
        View row = rowView(controller, nodeId);
        if (row == null) {
            deliver(callback, interactionId, false);
            return null;
        }
        MAIN.post(() -> {
            Entry entry = match.entry;
            String dynamicLabel = match.dynamicLabel;
            boolean handled = false;
            try {
                if (match.menuIndex >= 0) {
                    // 微信直连：当场重建菜单（监听器绑着“当前这一行”），
                    // 用微信自己的 onMMMenuItemSelected 执行，与点弹窗完全同路。
                    View anchor = row;
                    if (match.spec != WECHAT_CONVERSATION) {
                        View item = findChatItemAncestor(row);
                        if (item != null) {
                            anchor = item;
                        }
                    }
                    // 撞上别的菜单构建就先等一等再重试：读屏用户点的就是这一下，
                    // 直接回 false 就会出现「有时候不灵」。
                    retryWxExecute(anchor, row, match.menuIndex, callback, interactionId, 0);
                    return;
                } else if (dynamicLabel != null) {
                    handled = PhoneMenuBridge.runMenuAction(row, null, dynamicLabel);
                } else if (entry != null) {
                    switch (entry.kind) {
                        case ROW_CLICK -> handled = row.performClick();
                        case CHILD_CLICK -> handled = clickChild(row, match.spec.packageName,
                                entry.childViewId);
                        case MENU -> {
                            // 动态朗读项：根据最新行状态选出要调起的菜单项。
                            String[] labels = entry.menuLabels;
                            if (entry.labelResolver != null) {
                                String[] resolved = entry.labelResolver.resolve(row);
                                if (resolved != null && resolved.length > 0) {
                                    labels = resolved;
                                }
                            }
                            handled = PhoneMenuBridge.runMenuAction(row, null, labels);
                            // 菜单执行后置顶/未读状态已变，立刻丢缓存，
                            // 否则下次焦点进来文案还停在旧状态。
                            WeChatProbe.invalidate(row);
                        }
                        default -> { }
                    }
                }
            } catch (Throwable throwable) {
                log("action failed " + throwable.getClass().getSimpleName());
            }
            log((dynamicLabel != null ? dynamicLabel : entry == null ? "?" : entry.label)
                    + " handled=" + handled);
            deliver(callback, interactionId, handled);
        });
        return null;
    }

    private static final int WX_EXECUTE_MAX_ATTEMPTS = 4;

    /** 微信菜单项执行：撞上别的菜单构建就 150ms 后重试（最多 4 次），最终回结果。 */
    private static void retryWxExecute(View anchor, View origin, int index,
                                       Object callback, int interactionId, int attempt) {
        boolean handled = WeChatMenuHook.execute(anchor, origin, index);
        log("wx exec slot=" + index + " handled=" + handled + " try=" + attempt
                + " row=" + origin.getClass().getSimpleName());
        if (!handled && WeChatMenuHook.lastRunBusy()
                && attempt + 1 < WX_EXECUTE_MAX_ATTEMPTS) {
            MAIN.postDelayed(() -> retryWxExecute(anchor, origin, index,
                    callback, interactionId, attempt + 1), 150L);
            return;
        }
        deliver(callback, interactionId, handled);
    }

    private static Match resolve(int action) {
        for (Spec spec : SPECS) {
            for (Entry entry : spec.entries) {
                int entryAction = entry.actionId(spec);
                if (entryAction == action) {
                    // ROW_CLICK/CHILD_CLICK 走 entry；动态 MENU 走 entry（调起时再解）；
                    // 固定 MENU 走 entry.menuLabels。
                    return new Match(spec, entry, null);
                }
            }
            int index = action - (spec.base + MENU_DYNAMIC_OFFSET);
            if (index >= 0 && index < MENU_DYNAMIC_LIMIT) {
                if (spec == WECHAT_CHAT) {
                    // 聊天页：直连钩子（行内的菜单逐条对应，比回退方案准）
                    return WeChatMenuHook.isAvailable()
                            ? new Match(spec, null, null, index) : null;
                }
                String[] menu = DISCOVERED_MENUS.get(spec.key());
                String label = null;
                if (menu != null && index < menu.length) {
                    label = menu[index];
                } else {
                    String[] preset = spec.presetMenuLabels();
                    if (index < preset.length) {
                        label = preset[index];
                    }
                }
                if (label != null) {
                    return new Match(spec, null, label);
                }
            }
        }
        return null;
    }

    private static boolean clickChild(View row, String packageName, String childViewId) {
        if (childViewId == null) {
            return false;
        }
        int id = row.getResources().getIdentifier(childViewId, "id", packageName);
        if (id == 0) {
            return false;
        }
        View target = row.findViewById(id);
        return target != null && target.performClick();
    }

    private static View rowView(Object controller, long nodeId) {
        try {
            return (View) findViewByAccessibilityId.invoke(controller, (int) nodeId);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static void deliver(Object callback, int interactionId, boolean handled) {
        try {
            Method setResult = callback.getClass().getMethod(
                    "setPerformAccessibilityActionResult", boolean.class, int.class);
            setResult.invoke(callback, handled, interactionId);
        } catch (Throwable throwable) {
            log("deliver failed " + throwable.getClass().getSimpleName());
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static void log(String message) {
        if (!VERBOSE || host == null) {
            return;
        }
        try {
            host.log(Log.INFO, TAG, message);
        } catch (RuntimeException ignored) {
            // 日志失败不影响功能
        }
    }

    private enum Kind {
        ROW_CLICK, CHILD_CLICK, MENU
    }

    /** 根据当前行状态返回要调起的菜单项。返回 null 表示使用默认 menuLabels。 */
    interface LabelResolver {
        String[] resolve(View row);
    }

    private static final class Entry {
        final int offset;
        final String label;
        final Kind kind;
        final String childViewId;
        final String[] menuLabels;
        final LabelResolver labelResolver;

        Entry(int offset, String label, Kind kind, String childViewId, String... menuAliases) {
            this(offset, label, kind, childViewId, null, menuAliases);
        }

        Entry(int offset, Kind kind, String childViewId, LabelResolver labelResolver,
              String... menuAliases) {
            this(offset, null, kind, childViewId, labelResolver, menuAliases);
        }

        private Entry(int offset, String label, Kind kind, String childViewId,
                      LabelResolver labelResolver, String[] menuAliases) {
            this.offset = offset;
            this.label = label;
            this.kind = kind;
            this.childViewId = childViewId;
            this.labelResolver = labelResolver;
            this.menuLabels = menuAliases == null || menuAliases.length == 0
                    ? new String[]{label} : menuAliases;
        }

        int actionId(Spec spec) {
            return spec.base + offset;
        }

        String displayLabel(View row) {
            if (labelResolver != null && row != null) {
                try {
                    String[] resolved = labelResolver.resolve(row);
                    if (resolved != null && resolved.length > 0) {
                        return resolved[0];
                    }
                } catch (Throwable ignored) {
                }
            }
            return label;
        }
    }

    private static final class Spec {
        final String packageName;
        final String rowViewId;
        final String classSuffix;
        final String viewClassSuffix;
        final int base;
        final List<Entry> entries;
        final boolean autoDiscover;

        String key() {
            return packageName + "|" + rowViewId + "|" + classSuffix + "|" + viewClassSuffix;
        }

        String[] presetMenuLabels() {
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (Entry entry : entries) {
                if (entry.kind == Kind.MENU) {
                    // 带 labelResolver 的条目由 A 段按实时状态注入，这里必须跳过，
                    // 否则同一个动作会被写死的预设再塞一遍（微信曾因此多出两条重复项）。
                    if (entry.labelResolver != null) {
                        continue;
                    }
                    // 动态条目没有固定 label，回退到 menuLabels 的第 0 个。
                    labels.add(entry.label != null ? entry.label : entry.menuLabels[0]);
                }
            }
            return labels.toArray(new String[0]);
        }

        Spec(String packageName, String rowViewId, String classSuffix, int base,
             Entry... entries) {
            this(packageName, rowViewId, classSuffix, null, base, true, entries);
        }

        Spec(String packageName, String rowViewId, String classSuffix, int base,
             boolean autoDiscover, Entry... entries) {
            this(packageName, rowViewId, classSuffix, null, base, autoDiscover, entries);
        }

        Spec(String packageName, String rowViewId, String classSuffix,
             String viewClassSuffix, int base, Entry... entries) {
            this(packageName, rowViewId, classSuffix, viewClassSuffix, base, true, entries);
        }

        Spec(String packageName, String rowViewId, String classSuffix,
             String viewClassSuffix, int base, boolean autoDiscover, Entry... entries) {
            this.packageName = packageName;
            this.rowViewId = rowViewId;
            this.classSuffix = classSuffix;
            this.viewClassSuffix = viewClassSuffix;
            this.base = base;
            this.autoDiscover = autoDiscover;
            this.entries = List.of(entries);
        }
    }

    private static final class Match {
        final Spec spec;
        final Entry entry;
        final String dynamicLabel;
        final int menuIndex;

        Match(Spec spec, Entry entry, String dynamicLabel) {
            this(spec, entry, dynamicLabel, -1);
        }

        Match(Spec spec, Entry entry, String dynamicLabel, int menuIndex) {
            this.spec = spec;
            this.entry = entry;
            this.dynamicLabel = dynamicLabel;
            this.menuIndex = menuIndex;
        }
    }
}
