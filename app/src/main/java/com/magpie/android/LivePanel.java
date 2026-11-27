package com.magpie.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「实时 · 网关正在做的决定」：magpie 网页路由页最上面那块。
 *
 * <p>数据来自 {@code GET /api/gateway/trace?after=<seq>&wait=1} 长轮询，
 * 见 {@link TraceSource}。
 */
final class LivePanel {

    private final NativeHome host;
    private final Ui ui;

    private final LinearLayout root;
    private final LinearLayout body;

    /** 三个运行态计数：请求 / 改道 / 错误，各自一格。 */
    private LinearLayout statRequests;
    private LinearLayout statRerouted;
    private LinearLayout statErrors;

    /** 最近一次 trace 的 totals（请求/改道/错误），底部计数用。 */
    private JSONObject lastTotals;

    private final TraceSource source = new TraceSource();

    /** 每次 trace 回来顺手转一份给别人（请求记录区要用同一批 routes）。 */
    private java.util.function.Consumer<JSONArray> forward;

    /**
     * 并发请求：同时显示最近若干条 route（网页那样），而不是只显示最后一条。
     * 以前 apply() 只挑 id 最大的一条重画，多个 agent/会话并发时每来一条就整块
     * 换成另一条 → 界面反复跳。现在按 route id 累积成列表、并列展示、可展开。
     */
    private static final int MAX_LIVE = 30;
    private static final long DONE_TTL_MS = 20_000L;
    private final java.util.LinkedHashMap<Long, Entry> entries = new java.util.LinkedHashMap<>();

    /** 组头「收起/展开」状态，跨多次 trace 刷新保持。 */
    private boolean treeCollapsed = false;
    /** 决策树里各供应商的展开/收起状态（key=provider id；在集合里=已收起）。 */
    private final java.util.Set<String> provCollapsed = new java.util.HashSet<>();

    private static final class Entry {
        long id;
        JSONObject route;
        long lastSeen;
        boolean done;
        long doneAt;
        boolean expanded;
    }

    LivePanel(NativeHome host, Ui ui) {
        this.host = host;
        this.ui = ui;

        root = ui.column();
        // 自己在该方向上必须 wrap：父级是竖直 LinearLayout 时，
        // MATCH_PARENT 会让几个子项平分整屏高度，中间凭空多出空白
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = ui.row();
        head.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(2));
        head.addView(ui.text("实时 · 网关正在做的决定", 14, M3.ON_SURFACE, true),
                ui.weight(1));
        root.addView(head, wrapLp());

        // 顶部只保留标题；「请求/改道/错误」计数与提示语都移到面板底部（见 syncBody/renderEmpty）。
        body = ui.column();
        body.setPadding(0, ui.dp(10), 0, 0);
        root.addView(body, wrapLp());

        renderEmpty();
    }

    /** 一格计数：上面大数字（tnum 等宽，数字跳不抖），下面小标签。 */
    private LinearLayout counterCell(String label, int valueRole) {
        LinearLayout box = ui.column();
        TextView v = ui.text("0", 16, valueRole, true);
        v.setFontFeatureSettings("tnum");
        box.addView(v);
        box.addView(ui.text(label, 10.5f, M3.ON_SURFACE_VARIANT, false));
        return box;
    }

    /** 把某一格的大数字换掉；值没变就不动，避免每 26 秒重排一次。 */
    private void setCounter(LinearLayout cell, int value) {
        if (cell == null || cell.getChildCount() == 0) {
            return;
        }
        android.view.View v0 = cell.getChildAt(0);
        if (!(v0 instanceof TextView)) {
            return;
        }
        TextView v = (TextView) v0;
        String next = String.valueOf(value);
        if (!next.contentEquals(v.getText())) {
            v.setText(next);
        }
    }

    View view() {
        return root;
    }

    void setForward(java.util.function.Consumer<JSONArray> f) {
        this.forward = f;
    }

    void start() {
        source.start(host, new TraceSource.Sink() {
            @Override
            public void onTrace(JSONArray routes, JSONObject totals) {
                host.post(() -> {
                    apply(routes, totals);
                    if (forward != null && routes != null && routes.length() > 0) {
                        forward.accept(routes);
                    }
                });
            }

            @Override
            public void onError(String why) {
                // 网关没起来就保留上一帧，不刷屏
            }
        });
    }

    void stop() {
        source.stop();
    }

    private void apply(JSONArray routes, JSONObject totals) {
        if (totals != null) {
            lastTotals = totals;   // 底部计数用（顶部已不再显示）
        }
        long now = System.currentTimeMillis();
        if (routes != null) {
            for (int i = 0; i < routes.length(); i++) {
                JSONObject r = routes.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                long id = r.optLong("id", 0);
                if (id <= 0) {
                    continue;
                }
                Entry e = entries.get(id);
                if (e == null) {
                    e = new Entry();
                    e.id = id;
                    entries.put(id, e);
                }
                e.route = r;
                e.lastSeen = now;
                if (NativeData.flag(r, "done")) {
                    e.done = true;
                    e.doneAt = now;
                } else {
                    e.done = false;
                }
            }
        }
        // 清掉完成已久（超过 TTL）的条目
        java.util.Iterator<java.util.Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.done && now - e.doneAt > DONE_TTL_MS) {
                it.remove();
            }
        }
        // 超量：先淘汰已完成的，其次淘汰最旧的
        while (entries.size() > MAX_LIVE) {
            Long victim = null;
            for (java.util.Map.Entry<Long, Entry> en : entries.entrySet()) {
                if (en.getValue().done) {
                    victim = en.getKey();
                    break;
                }
            }
            if (victim == null) {
                for (Long k : entries.keySet()) {
                    victim = k;
                    break;
                }
            }
            entries.remove(victim);
        }
        syncBody();
    }

    /**
     * 照 magpie 手机网页版：一条 featured route 渲染成一棵「分层决策树」——
     * 组头 + 策略说明 + Agent→magpie + 按 provider 分组的候选账号（可展开）+ 底部解释。
     * 取「最新进行中」的那条 route 作为 featured（都在跑就取最新一条）。
     */
    private void syncBody() {
        if (entries.isEmpty()) {
            renderEmpty();
            return;
        }
        java.util.List<Entry> list = new java.util.ArrayList<>(entries.values());
        list.sort((a, b) -> Long.compare(b.id, a.id)); // 新的在上
        Entry feat = null;
        for (Entry e : list) {
            if (!e.done) {
                feat = e;
                break;
            }
        }
        if (feat == null) {
            feat = list.get(0);
        }
        final JSONObject route = feat.route;
        final JSONObject group = NativeData.obj(route, "group");

        body.removeAllViews();

        // 主体容器：组头右侧的「收起/展开」控制它的显隐
        final LinearLayout main = ui.column();
        main.setLayoutParams(wrapLp());

        // 组头：<组名> · 启用 N 个        [收起/展开]
        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(4));
        String grpName = groupName(group);
        int enabled = enabledCount(group, route);
        LinearLayout titleWrap = ui.row();
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);
        TextView grpTitle = ui.text(grpName, 15, M3.ON_SURFACE, true);
        grpTitle.setSingleLine(true);
        grpTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleWrap.addView(grpTitle);
        TextView grpCount = ui.text(" · 启用 " + enabled + " 个", 13,
                M3.ON_SURFACE_VARIANT, false);
        grpCount.setSingleLine(true);
        titleWrap.addView(grpCount);
        header.addView(titleWrap, ui.weight(1));
        final TextView toggle = ui.text(treeCollapsed ? "展开" : "收起", 12, M3.PRIMARY, true);
        toggle.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4));
        header.addView(toggle);
        header.setClickable(true);
        main.setVisibility(treeCollapsed ? View.GONE : View.VISIBLE);
        header.setOnClickListener(v -> {
            treeCollapsed = !treeCollapsed;
            main.setVisibility(treeCollapsed ? View.GONE : View.VISIBLE);
            toggle.setText(treeCollapsed ? "展开" : "收起");
        });
        body.addView(header, wrapLp());

        // 策略说明
        main.addView(policyNote(group));
        // Agent → magpie
        main.addView(agentToMagpie(route, group));
        // 决策树（按 provider 分组）
        main.addView(decisionTree(route));
        // 底部决策解释
        main.addView(bottomExplain(route));
        // 底部计数：请求 / 改道 / 错误
        main.addView(bottomCounters());

        body.addView(main, wrapLp());
    }

    // ------------------------------------------------------------------
    // 分层决策树（Phase 1）
    // ------------------------------------------------------------------

    private String groupName(JSONObject group) {
        if (group == null) {
            return "路由组";
        }
        String n = NativeData.str(group, "ref", NativeData.str(group, "name", ""));
        return n.isEmpty() ? "路由组" : n;
    }

    /** 启用数：优先 group.members 长度，否则用 order 里去重后的账号数。 */
    private int enabledCount(JSONObject group, JSONObject route) {
        if (group != null) {
            JSONArray m = NativeData.arr(group, "members");
            if (m.length() > 0) {
                return m.length();
            }
        }
        JSONArray order = NativeData.arr(route, "order");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject w = order.optJSONObject(i);
            if (w != null) {
                String id = NativeData.str(w, "id", "");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids.size();
    }

    private View policyNote(JSONObject group) {
        String routing = group == null ? "" : NativeData.str(group, "routing", "");
        TextView t = ui.text(routingExplain(routing), 11.5f, M3.ON_SURFACE_VARIANT, false);
        t.setLineSpacing(ui.dp(3), 1f);
        t.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(8));
        return t;
    }

    private String routingExplain(String routing) {
        String r = routing == null ? "" : routing.toLowerCase(java.util.Locale.US);
        if (r.contains("pace")) {
            return "额度优先：余额最先重置的账号排前面，避免额度在重置时作废；"
                    + "用到 90% 以上的留到其他都无法响应时才用；失败后休息中的排最后。";
        }
        if (r.contains("smart")) {
            return "智能：按可用额度、模型匹配与历史成功率综合排序，优先选能直接回答的账号。";
        }
        if (r.isEmpty()) {
            return "按可用额度与匹配度排序：能直接回答的账号排前面，失败/暂停的排后面。";
        }
        return "路由策略：" + routing + "（按可用额度与匹配度排序，失败/暂停的排后面）。";
    }

    private String routingLabel(String routing) {
        String r = routing == null ? "" : routing.toLowerCase(java.util.Locale.US);
        if (r.contains("pace")) {
            return "额度优先";
        }
        if (r.contains("smart")) {
            return "智能";
        }
        return (routing == null || routing.isEmpty()) ? "路由" : routing;
    }

    /** Agent 卡 → 箭头 → magpie 卡（带 routing 标签胶囊）。 */
    private View agentToMagpie(JSONObject route, JSONObject group) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(6));
        row.setLayoutParams(lp);

        String agent = NativeData.str(route, "agent", "");
        String model = NativeData.str(route, "model", "");
        String who = (agent == null || agent.isEmpty()) ? "等待请求" : NativeData.pretty(agent);
        row.addView(agentCard(who, model),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = ui.text("\u2192", 14, M3.OUTLINE, false);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ui.dp(28), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(arrow, alp);

        LinearLayout mag = box(Prefs.browserHost(host) + ":" + GatewayService.gatewayPort(host),
                "magpie", null, true, false);
        String routing = group == null ? "" : NativeData.str(group, "routing", "");
        TextView chip = badge(routingLabel(routing),
                ui.col(M3.SECONDARY_CONTAINER), ui.col(M3.ON_SECONDARY_CONTAINER));
        LinearLayout.LayoutParams chlp = ui.wrap();
        chlp.topMargin = ui.dp(4);
        mag.addView(chip, chlp);
        row.addView(mag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 网页样式的 Agent 卡：● 名称 + 模型（等宽小字），圆角居中，无抬头。 */
    private LinearLayout agentCard(String name, String model) {
        LinearLayout box = ui.column();
        GradientDrawable bg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 14,
                ui.col(M3.OUTLINE_VARIANT), 1f);
        box.setBackground(bg);
        box.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout nameRow = ui.row();
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(dot(M3.PRIMARY, 7));
        TextView n = ui.text(name, 13, M3.ON_SURFACE, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = ui.wrap();
        nlp.leftMargin = ui.dp(6);
        nameRow.addView(n, nlp);
        box.addView(nameRow);

        if (model != null && !model.isEmpty()) {
            TextView m = ui.mono(model, 9.5f, M3.ON_SURFACE_VARIANT);
            m.setSingleLine(true);
            m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            m.setGravity(Gravity.CENTER);
            m.setLayoutParams(ui.matchWrap(6));
            box.addView(m);
        }
        return box;
    }

    /** 决策树主体：order[] 按 provider 分组，每组一行供应商 + 其下可展开的账号行。 */
    private View decisionTree(JSONObject route) {
        LinearLayout wrap = ui.column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2));
        wrap.setLayoutParams(lp);

        JSONArray order = NativeData.arr(route, "order");
        if (order.length() == 0) {
            return wrap;
        }
        JSONArray tries = NativeData.arr(route, "tries");
        String answeredId = lastAnsweredId(tries);

        java.util.LinkedHashMap<String, java.util.List<JSONObject>> byProv =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject w = order.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String prov = NativeData.str(w, "provider", "");
            if (prov.isEmpty()) {
                prov = NativeData.str(w, "name", "");
            }
            if (prov.isEmpty()) {
                prov = "?";
            }
            java.util.List<JSONObject> g = byProv.get(prov);
            if (g == null) {
                g = new java.util.ArrayList<>();
                byProv.put(prov, g);
            }
            g.add(w);
        }

        int shown = 0;
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : byProv.entrySet()) {
            if (shown >= 10) {
                break;
            }
            wrap.addView(providerRow(route, e.getKey(), e.getValue(), tries, answeredId));
            shown++;
        }
        return wrap;
    }

    /** 最后一个「成功」try 的 id（没有成功的就退回最后一条 try 的 id）。 */
    private String lastAnsweredId(JSONArray tries) {
        String lastAny = null;
        for (int i = 0; i < tries.length(); i++) {
            JSONObject t = tries.optJSONObject(i);
            if (t != null) {
                String id = NativeData.str(t, "id", "");
                if (!id.isEmpty()) {
                    lastAny = id;
                }
            }
        }
        for (int i = tries.length() - 1; i >= 0; i--) {
            JSONObject t = tries.optJSONObject(i);
            if (t != null && NativeData.flag(t, "done")
                    && NativeData.num(t, "status") < 400) {
                String id = NativeData.str(t, "id", "");
                if (!id.isEmpty()) {
                    return id;
                }
            }
        }
        return lastAny;
    }

    private String providerDisplay(String provKey, JSONObject w) {
        // 供应商行显示供应商名（不用账号昵称）：内置映射 → Zh.of → 首字母大写 → 原 id。
        if (provKey == null || provKey.isEmpty()) {
            return "供应商";
        }
        String key = provKey.trim();
        String lower = key.toLowerCase(java.util.Locale.US);
        String builtin = builtinProviderName(lower);
        if (builtin != null) {
            return builtin;
        }
        String z = Zh.of(key);
        if (z != null && !z.isEmpty() && !z.equalsIgnoreCase(key)) {
            return z;
        }
        // 全小写 ascii（xxx / xxx-yyy）→ 首字母大写
        if (lower.equals(key) && key.matches("[a-z0-9._-]+")) {
            return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
        return key;
    }

    /** 常见供应商 id → 展示名（覆盖 ProviderIcons 里那批 + workbuddy/free）。 */
    private static String builtinProviderName(String lower) {
        switch (lower) {
            case "workbuddy": return "WorkBuddy";
            case "free": return "免费";
            case "antigravity": return "Antigravity";
            case "trae":
            case "trae-cn": return "Trae";
            case "qwen":
            case "qwenwork": return "Qwen";
            case "openai": return "OpenAI";
            case "anthropic": return "Anthropic";
            case "claude":
            case "claude-code": return "Claude";
            case "gemini": return "Gemini";
            case "moonshot":
            case "kimi": return "Kimi";
            case "zhipu":
            case "zai": return "Zhipu";
            case "openrouter": return "OpenRouter";
            case "siliconcloud": return "SiliconCloud";
            case "volcengine": return "Volcengine";
            case "baidu": return "Baidu";
            case "tencent": return "Tencent";
            case "xai": return "xAI";
            case "mistral": return "Mistral";
            case "groq": return "Groq";
            case "together": return "Together";
            case "fireworks": return "Fireworks";
            case "cerebras": return "Cerebras";
            case "perplexity": return "Perplexity";
            case "copilot": return "Copilot";
            case "cursor": return "Cursor";
            case "windsurf": return "Windsurf";
            case "cline": return "Cline";
            case "opencode": return "OpenCode";
            case "zed": return "Zed";
            case "ollama": return "Ollama";
            case "lmstudio": return "LM Studio";
            default: return null;
        }
    }

    private View providerRow(JSONObject route, String provKey, java.util.List<JSONObject> items,
                             JSONArray tries, String answeredId) {
        JSONObject w0 = items.get(0);
        String label = providerDisplay(provKey, w0);
        String repModel = "";
        for (JSONObject x : items) {
            String mm = NativeData.str(x, "model", "");
            if (!mm.isEmpty()) {
                repModel = shortModel(mm);
                break;
            }
        }
        final boolean collapsed = provCollapsed.contains(provKey);

        LinearLayout wrap = ui.column();
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = ui.dp(8);
        wrap.setLayoutParams(wlp);

        LinearLayout head = ui.row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable hbg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 12,
                ui.col(M3.OUTLINE_VARIANT), 1f);
        ui.clickable(head, hbg);
        head.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));

        int icon = ProviderIcons.of(host, NativeData.str(w0, "icon", ""),
                NativeData.str(w0, "provider", ""), NativeData.str(w0, "preset", ""));
        if (icon == 0) {
            // 兜底：用 provider id 再查一次（icon/preset 缺失时）
            icon = ProviderIcons.of(host, "", provKey, "");
        }
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(host);
            iv.setImageResource(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            head.addView(iv, new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        } else {
            head.addView(dot(M3.ON_SURFACE_VARIANT, 8));
        }

        TextView name = ui.text(label, 12.5f, M3.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(ui.dp(120));
        LinearLayout.LayoutParams nlp = ui.wrap();
        nlp.leftMargin = ui.dp(6);
        head.addView(name, nlp);

        if (!repModel.isEmpty()) {
            TextView rm = modelChip(repModel);
            LinearLayout.LayoutParams mlp = ui.wrap();
            mlp.leftMargin = ui.dp(6);
            head.addView(rm, mlp);
        }
        // 弹性空隙：把路由胶囊 / 状态徽章 / caret 推到右侧
        head.addView(new View(host), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 路由方式胶囊（额度优先 / 智能）：放在模型之后、状态徽章之前
        JSONObject grpR = NativeData.obj(route, "group");
        String routingR = grpR == null ? "" : NativeData.str(grpR, "routing", "");
        String rlabel = routingLabel(routingR);
        if ("额度优先".equals(rlabel) || "智能".equals(rlabel)) {
            TextView rchip = badge(rlabel,
                    ui.col(M3.SECONDARY_CONTAINER), ui.col(M3.ON_SECONDARY_CONTAINER));
            LinearLayout.LayoutParams rlp = ui.wrap();
            rlp.leftMargin = ui.dp(6);
            head.addView(rchip, rlp);
        }

        TextView pb = providerBadge(items, tries);
        if (pb != null) {
            LinearLayout.LayoutParams bp = ui.wrap();
            bp.leftMargin = ui.dp(6);
            head.addView(pb, bp);
        }

        final TextView arr = ui.text(collapsed ? "\u25B8" : "\u25BE", 10,
                M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams al = ui.wrap();
        al.leftMargin = ui.dp(6);
        head.addView(arr, al);
        wrap.addView(head, wrapLp());

        // 账号容器：左侧竖向引导线 + 左缩进，做出「挂在供应商下面」的树形观感
        final LinearLayout treeRow = ui.row();
        LinearLayout.LayoutParams treeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        treeLp.topMargin = ui.dp(6);
        treeRow.setLayoutParams(treeLp);

        View guide = new View(host);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(ui.col(M3.OUTLINE_VARIANT));
        guide.setBackground(gd);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ui.dp(2), ViewGroup.LayoutParams.MATCH_PARENT);
        treeRow.addView(guide, glp);

        final LinearLayout acc = ui.column();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.leftMargin = ui.dp(14);
        acc.setLayoutParams(alp);

        java.util.LinkedHashMap<String, java.util.List<JSONObject>> byId =
                new java.util.LinkedHashMap<>();
        for (JSONObject x : items) {
            String id = NativeData.str(x, "id", "");
            if (id.isEmpty()) {
                id = "?";
            }
            java.util.List<JSONObject> l = byId.get(id);
            if (l == null) {
                l = new java.util.ArrayList<>();
                byId.put(id, l);
            }
            l.add(x);
        }
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : byId.entrySet()) {
            acc.addView(accountRow(route, e.getValue(), tries, answeredId));
        }
        treeRow.addView(acc, alp);
        treeRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        wrap.addView(treeRow, treeLp);

        head.setOnClickListener(v -> {
            boolean nowCollapsed = treeRow.getVisibility() == View.VISIBLE;
            treeRow.setVisibility(nowCollapsed ? View.GONE : View.VISIBLE);
            arr.setText(nowCollapsed ? "\u25B8" : "\u25BE");
            if (nowCollapsed) {
                provCollapsed.add(provKey);
            } else {
                provCollapsed.remove(provKey);
            }
        });
        return wrap;
    }

    /** 供应商行徽章：只在该组「最后一条 try」失败或回答中时显示；成功不挂（避免每行都“已答”太吵）。 */
    private TextView providerBadge(java.util.List<JSONObject> items, JSONArray tries) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (JSONObject x : items) {
            String id = NativeData.str(x, "id", "");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        JSONObject t = lastTryOf(ids, tries);
        if (t == null) {
            return null;
        }
        if (!NativeData.flag(t, "done")) {
            return badge("回答中…", ui.col(M3.PRIMARY), 0xFFFFFFFF);
        }
        int st = NativeData.num(t, "status");
        if (st >= 400) {
            return badge(st + " · 失败", ui.col(M3.ERROR), 0xFFFFFFFF);
        }
        return null;
    }

    private JSONObject lastTryOf(java.util.Set<String> ids, JSONArray tries) {
        for (int i = tries.length() - 1; i >= 0; i--) {
            JSONObject t = tries.optJSONObject(i);
            if (t != null && ids.contains(NativeData.str(t, "id", ""))) {
                return t;
            }
        }
        return null;
    }

    /** 由一个 try 生成徽章：回答中(蓝) / 失败(红) / 已答(次要容器)。 */
    private TextView tryBadge(JSONObject t) {
        if (t == null) {
            return null;
        }
        if (!NativeData.flag(t, "done")) {
            return badge("回答中…", ui.col(M3.PRIMARY), 0xFFFFFFFF);
        }
        int st = NativeData.num(t, "status");
        if (st >= 400) {
            return badge(st + " · 失败", ui.col(M3.ERROR), 0xFFFFFFFF);
        }
        long ms = (long) NativeData.dec(t, "ms");
        return badge(ms > 0 ? "已答 · " + NativeData.ms(ms) : "已答",
                ui.col(M3.SECONDARY_CONTAINER), ui.col(M3.ON_SECONDARY_CONTAINER));
    }

    /** 模型名做成浅底圆角小胶囊（等宽小字），替代纯文字。 */
    private TextView modelChip(String text) {
        TextView t = ui.mono(text, 9.5f, M3.ON_SURFACE_VARIANT);
        t.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGH), 999,
                ui.col(M3.OUTLINE_VARIANT), 1f));
        int px = ui.dp(7), py = ui.dp(2);
        t.setPadding(px, py, px, py);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(ui.dp(160));
        return t;
    }

    private TextView badge(String text, int bgColor, int fgColor) {
        TextView t = ui.textColor(text, 9.5f, fgColor, true);
        t.setBackground(ui.round(bgColor, 999));
        int px = ui.dp(7), py = ui.dp(2);
        t.setPadding(px, py, px, py);
        t.setSingleLine(true);
        return t;
    }

    /** 一个账号一行（同 id 的多条 order 合并）。 */
    private View accountRow(JSONObject route, java.util.List<JSONObject> same, JSONArray tries,
                            String answeredId) {
        JSONObject w = same.get(0);
        String id = NativeData.str(w, "id", "");
        boolean picked = id != null && !id.isEmpty() && id.equals(answeredId);

        final LinearLayout card = ui.column();
        GradientDrawable bg = ui.roundStroke(
                ui.col(picked ? M3.SURFACE : M3.SURFACE_CONTAINER_LOW), 12,
                ui.col(picked ? M3.ON_SURFACE : M3.OUTLINE_VARIANT), picked ? 2f : 1f);
        card.setBackground(bg);
        card.setPadding(ui.dp(11), ui.dp(8), ui.dp(11), ui.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(6);
        card.setLayoutParams(lp);

        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);

        View circle = new View(host);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(ui.col(picked ? M3.ON_SURFACE : M3.SURFACE_CONTAINER_HIGHEST));
        circle.setBackground(cd);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        clp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(circle, clp);

        int icon = ProviderIcons.of(host, NativeData.str(w, "icon", ""),
                NativeData.str(w, "provider", ""), NativeData.str(w, "preset", ""));
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(host);
            iv.setImageResource(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(15), ui.dp(15));
            ilp.leftMargin = ui.dp(5);
            row.addView(iv, ilp);
        }

        String who = NativeData.str(w, "who", NativeData.str(w, "name", "?"));
        TextView name = ui.text(who, 12, M3.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(ui.dp(120));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = ui.dp(6);
        row.addView(name, nlp);

        String plan = Zh.of(NativeData.str(w, "plan", ""));
        if (plan.isEmpty() && "account".equals(NativeData.str(w, "kind", ""))) {
            plan = "Free";
        }
        if (!plan.isEmpty()) {
            TextView p = ui.text(plan, 9, M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams plp = ui.wrap();
            plp.leftMargin = ui.dp(4);
            row.addView(p, plp);
        }

        StringBuilder models = new StringBuilder();
        for (JSONObject x : same) {
            String mm = NativeData.str(x, "model", "");
            if (mm.isEmpty()) {
                continue;
            }
            if (models.length() > 0) {
                models.append(" · ");
            }
            models.append(shortModel(mm));
        }
        if (models.length() > 0) {
            TextView m = modelChip(models.toString());
            LinearLayout.LayoutParams mlp = ui.wrap();
            mlp.leftMargin = ui.dp(6);
            row.addView(m, mlp);
        }
        // 弹性空隙：状态徽章推到右侧
        row.addView(new View(host), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        java.util.Set<String> one = new java.util.HashSet<>();
        one.add(id);
        TextView stateBadge = tryBadge(lastTryOf(one, tries));
        if (stateBadge != null) {
            LinearLayout.LayoutParams sbp = ui.wrap();
            sbp.leftMargin = ui.dp(6);
            row.addView(stateBadge, sbp);
        }
        card.addView(row);

        double used = NativeData.dec(w, "used");
        TextView info = ui.text(quotaText(w), 10.5f,
                used >= 90 ? M3.ERROR : M3.ON_SURFACE_VARIANT, false);
        info.setLineSpacing(ui.dp(1), 1f);
        info.setLayoutParams(ui.matchWrap(2));
        card.addView(info);

        final JSONObject mine = route;
        ui.clickable(card, bg);
        card.setOnClickListener(v -> {
            if (mine != null) {
                CtxSheet.show((android.app.Activity) host, ui, mine);
            }
        });
        return card;
    }

    private String quotaText(JSONObject w) {
        double used = NativeData.dec(w, "used");
        double amount = NativeData.dec(w, "amount");
        double limit = NativeData.dec(w, "limit");
        String unit = Zh.of(NativeData.str(w, "unit", ""));
        StringBuilder sb = new StringBuilder();
        if (limit > 0) {
            sb.append(brief(amount)).append(" / ").append(brief(limit));
            if (!unit.isEmpty()) {
                sb.append(' ').append(unit);
            }
            // 像图2：额度之后、重置之前补一句「已用 X%」（used 取不到就省略）
            if (used > 0) {
                sb.append(" · 已用 ").append(NativeData.percent(used));
            }
        } else {
            sb.append("已用 ").append(NativeData.percent(used));
        }
        JSONArray renews = NativeData.arr(w, "renews");
        if (renews.length() > 0) {
            String u = NativeData.until(renews.optString(0, ""));
            if (!u.isEmpty()) {
                sb.append(" · ").append(u).append("重置");
            }
        }
        JSONObject rest = NativeData.obj(w, "rest");
        if (rest != null) {
            String u = NativeData.until(NativeData.str(rest, "until", ""));
            sb.append(" · 暂停使用");
            if (!u.isEmpty()) {
                sb.append(' ').append(u).append("恢复");
            }
        }
        if (NativeData.flag(w, "fallback")) {
            sb.append(" · 备用");
        }
        return sb.toString();
    }

    private JSONObject findOrderItem(JSONArray order, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (int i = 0; i < order.length(); i++) {
            JSONObject w = order.optJSONObject(i);
            if (w != null && id.equals(NativeData.str(w, "id", ""))) {
                return w;
            }
        }
        return null;
    }

    /** 底部一句决策解释（近似图2）：按「回答的那条 try」→ order 项取供应商友好名+模型。 */
    private View bottomExplain(JSONObject route) {
        JSONArray tries = NativeData.arr(route, "tries");
        JSONObject t = lastSuccessOrLast(tries);
        String text;
        if (tries.length() == 0 || t == null) {
            text = "暂无决策信息。";
        } else {
            String id = NativeData.str(t, "id", "");
            JSONObject ow = findOrderItem(NativeData.arr(route, "order"), id);
            String model = shortModel(ow != null
                    ? NativeData.str(ow, "model", "") : NativeData.str(route, "model", ""));
            String prov = ow == null ? "" : providerDisplay(
                    NativeData.str(ow, "provider", ""), ow);
            String tag;
            if (!prov.isEmpty() && !model.isEmpty()) {
                tag = prov + "(" + model + ")";
            } else if (!prov.isEmpty()) {
                tag = prov;
            } else if (!model.isEmpty()) {
                tag = model;
            } else {
                tag = "magpie";
            }

            if (!NativeData.flag(t, "done")) {
                text = "正在由 " + tag + " 回答…";
            } else {
                int st = NativeData.num(t, "status");
                if (st >= 400) {
                    text = tag + " 返回 " + st + " · 失败，且无其他可问；"
                            + "这可能是暂时性错误，稍后会自动重试。";
                } else {
                    long ms = (long) NativeData.dec(t, "ms");
                    text = "由 " + tag + " 回答" + (ms > 0 ? " · " + NativeData.ms(ms) : "") + "。";
                }
            }
        }
        TextView v = ui.text(text, 11, M3.ON_SURFACE_VARIANT, false);
        v.setLineSpacing(ui.dp(2), 1f);
        v.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(6));
        return v;
    }

    /** tries 里最后一条「成功」的；没有成功就退回最后一条（可能失败/进行中）。 */
    private JSONObject lastSuccessOrLast(JSONArray tries) {
        JSONObject last = null;
        for (int i = 0; i < tries.length(); i++) {
            JSONObject x = tries.optJSONObject(i);
            if (x != null) {
                last = x;
            }
        }
        for (int i = tries.length() - 1; i >= 0; i--) {
            JSONObject x = tries.optJSONObject(i);
            if (x != null && NativeData.flag(x, "done")
                    && NativeData.num(x, "status") < 400) {
                return x;
            }
        }
        return last;
    }

    /** 左侧：所有并发 agent 的小卡竖排；中间箭头；右侧：一个 magpie 卡。 */
    private View sourcesRow(java.util.LinkedHashMap<String, Entry> agents, Entry newest) {
        LinearLayout wrap = ui.column();
        wrap.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2));

        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = ui.column();
        int n = 0;
        for (Entry e : agents.values()) {
            String agent = NativeData.str(e.route, "agent", "");
            String model = NativeData.str(e.route, "model", "");
            String who = (agent == null || agent.isEmpty()) ? "等待请求" : NativeData.pretty(agent);
            LinearLayout b = box(who, "你的 Agent",
                    model.isEmpty() ? null : shortModel(model), false, false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (n > 0) {
                blp.topMargin = ui.dp(6);
            }
            left.addView(b, blp);
            n++;
        }
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = ui.text("\u2192", 14, M3.OUTLINE, false);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ui.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(arrow, alp);

        JSONObject g = newest == null ? null : NativeData.obj(newest.route, "group");
        String grpName = g == null ? "" : NativeData.str(g, "ref", NativeData.str(g, "name", ""));
        LinearLayout magpie = box(Prefs.browserHost(host) + ":" + GatewayService.gatewayPort(host),
                "magpie", grpName.isEmpty() ? null : grpName, true, false);
        row.addView(magpie, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(row, wrapLp());
        return wrap;
    }

    /** 一条 route 的完整明细：agent→magpie 两框 + 连线 + 候选账号卡 + 被排除。 */
    private void addDetail(LinearLayout parent, JSONObject route) {
        String agent = NativeData.str(route, "agent", "");
        String model = NativeData.str(route, "model", "");
        JSONObject group = NativeData.obj(route, "group");

        String answered = null;
        JSONArray tries = NativeData.arr(route, "tries");
        if (tries.length() > 0) {
            JSONObject last = tries.optJSONObject(tries.length() - 1);
            if (last != null) {
                answered = NativeData.str(last, "id", null);
            }
        }
        boolean done = NativeData.flag(route, "done");
        int status = NativeData.num(route, "status");

        parent.addView(nodes(agent, model, group));
        parent.addView(connector(answered != null, done, status));
        parent.addView(orderList(route, answered, tries, done, status));
        parent.addView(excluded(route));
    }

    /** 摘要行右侧的状态文字。 */
    private String summaryText(JSONObject route) {
        boolean done = NativeData.flag(route, "done");
        int status = NativeData.num(route, "status");
        if (!done) {
            return "回答中…";
        }
        if (status >= 400) {
            return "失败 " + status;
        }
        JSONArray tries = NativeData.arr(route, "tries");
        double ms = 0;
        if (tries.length() > 0) {
            JSONObject t = tries.optJSONObject(tries.length() - 1);
            if (t != null) {
                ms = NativeData.dec(t, "ms");
            }
        }
        return ms > 0 ? "已答 · " + NativeData.ms((long) ms) : "已答";
    }

    private void renderEmpty() {
        body.removeAllViews();
        body.addView(nodes(null, null, null));
        // 提示语只在空态显示；有数据后不再出现
        TextView hint = ui.text("等待请求；经 agent 发出请求后，这里实时播放："
                        + "路由把谁排第一及原因、每次尝试和各自的回应。",
                11, M3.ON_SURFACE_VARIANT, false);
        hint.setLineSpacing(ui.dp(2), 1f);
        hint.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        body.addView(hint);
        TextView t = ui.text("还没有请求", 12, M3.ON_SURFACE_VARIANT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(16), 0, ui.dp(10));
        body.addView(t);
        body.addView(bottomCounters());
    }

    /** 底部计数一行：请求 / 改道 / 错误（大号 tnum 数字 + 小标签，错误用红色）。 */
    private View bottomCounters() {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(8));
        int req = lastTotals == null ? 0 : NativeData.num(lastTotals, "requests");
        int rer = lastTotals == null ? 0 : NativeData.num(lastTotals, "rerouted");
        int err = lastTotals == null ? 0 : NativeData.num(lastTotals, "errors");
        row.addView(counterCell2("请求", req, M3.ON_SURFACE), ui.weight(1));
        row.addView(counterCell2("改道", rer, M3.ON_SURFACE), ui.weight(1));
        row.addView(counterCell2("错误", err, M3.ERROR), ui.weight(1));
        return row;
    }

    private LinearLayout counterCell2(String label, int value, int valueRole) {
        LinearLayout box = ui.column();
        TextView v = ui.text(String.valueOf(value), 22, valueRole, true);
        v.setFontFeatureSettings("tnum");
        box.addView(v);
        box.addView(ui.text(label, 11, M3.ON_SURFACE_VARIANT, false));
        return box;
    }

    private View nodes(String agent, String model, JSONObject group) {
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(16), 0, ui.dp(16), 0);
        row.setLayoutParams(lp);

        String who = (agent == null || agent.isEmpty()) ? "等待请求" : NativeData.pretty(agent);
        String sub = model == null || model.isEmpty() ? null : model;
        row.addView(box(who, "你的 Agent", sub, false, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = ui.text("\u2192", 14, M3.OUTLINE, false);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ui.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(arrow, alp);

        String grpName = group == null ? "" : NativeData.str(group, "ref",
                NativeData.str(group, "name", ""));
        row.addView(box(Prefs.browserHost(host) + ":" + GatewayService.gatewayPort(host),
                        "magpie", grpName.isEmpty() ? null : grpName, true, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout box(String main, String header, String sub,
                             boolean live, boolean idle) {
        LinearLayout box = ui.column();
        GradientDrawable bg = ui.roundStroke(
                ui.col(live ? M3.SURFACE_CONTAINER : M3.SURFACE_CONTAINER_LOW), 14,
                ui.col(live ? M3.ON_SURFACE : M3.OUTLINE_VARIANT), live ? 1.8f : 1f);
        box.setBackground(bg);
        box.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout head = ui.row();
        if (live) {
            head.addView(dot(M3.TERTIARY, 7));
        } else if (idle) {
            head.addView(dot(M3.ERROR, 7));
        }
        TextView h = ui.text(header, 10, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams hlp = ui.wrap();
        if (live || idle) {
            hlp.leftMargin = ui.dp(5);
        }
        head.addView(h, hlp);
        box.addView(head);

        TextView m = ui.text(main, 12, M3.ON_SURFACE, true);
        m.setGravity(Gravity.CENTER);
        m.setSingleLine(true);
        m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        m.setLayoutParams(ui.matchWrap(2));
        box.addView(m);

        if (sub != null && !sub.isEmpty()) {
            TextView s = ui.mono(sub, 9, M3.ON_SURFACE_VARIANT);
            s.setGravity(Gravity.CENTER);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            box.addView(s);
        }
        return box;
    }

    private View dot(int role, int sizeDp) {
        View v = new View(host);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ui.col(role));
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private View connector(boolean live, boolean done, int status) {
        RouteLines v = new RouteLines(host, ui);
        v.setLive(live, done && status >= 400);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(24));
        lp.setMargins(ui.dp(16), 0, ui.dp(16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View orderList(JSONObject route, String answeredId,
                           JSONArray tries, boolean done, int status) {
        LinearLayout list = ui.column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(14), 0, ui.dp(14), 0);
        list.setLayoutParams(lp);

        JSONArray order = NativeData.arr(route, "order");
        if (order.length() == 0) {
            return list;
        }

        // 同一个账号会在 order 里出现多次（它服务的每个模型各一条）。网页上
        // 是「一个账号一张卡、右边的胶囊列它这次能服务的模型」，所以这里按
        // id 合并：同一个 id 只画一张卡，模型名拼在右边。
        java.util.LinkedHashMap<String, java.util.List<JSONObject>> byId =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject w = order.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String id = NativeData.str(w, "id", "");
            java.util.List<JSONObject> group = byId.get(id);
            if (group == null) {
                group = new java.util.ArrayList<>();
                byId.put(id, group);
            }
            group.add(w);
        }

        boolean marked = false;
        int shown = 0;
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : byId.entrySet()) {
            if (shown >= 12) {
                break;
            }
            boolean picked = !marked && e.getKey().equals(answeredId);
            if (picked) {
                marked = true;
            }
            list.addView(card(e.getValue(), picked, tries, done, status, route));
            shown++;
        }
        int rest = byId.size() - shown;
        if (rest > 0) {
            TextView more = ui.text("还有 " + rest + " 个候选账号",
                    10.5f, M3.ON_SURFACE_VARIANT, false);
            more.setPadding(0, ui.dp(2), 0, ui.dp(6));
            list.addView(more);
        }
        return list;
    }

    /**
     * 一个账号一张卡。
     *
     * @param same 同一个账号在 order 里的若干条（它服务的每个模型一条），
     *             第一条当主，其余模型名拼在右边
     */
    private View card(java.util.List<JSONObject> same, boolean picked, JSONArray tries,
                      boolean done, int status, JSONObject routeOfCard) {
        JSONObject w = same.get(0);
        LinearLayout card = ui.column();
        GradientDrawable bg = ui.roundStroke(
                ui.col(picked ? M3.SURFACE : M3.SURFACE_CONTAINER_LOW), 12,
                ui.col(picked ? M3.ON_SURFACE : M3.OUTLINE_VARIANT), picked ? 2f : 1f);
        card.setBackground(bg);
        card.setPadding(ui.dp(11), ui.dp(8), ui.dp(11), ui.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(6);
        card.setLayoutParams(lp);

        LinearLayout row = ui.row();

        View circle = new View(host);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(ui.col(picked ? M3.ON_SURFACE : M3.SURFACE_CONTAINER_HIGHEST));
        circle.setBackground(cd);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        clp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(circle, clp);

        int icon = ProviderIcons.of(host, NativeData.str(w, "icon", ""),
                NativeData.str(w, "provider", ""), NativeData.str(w, "preset", ""));
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(host);
            iv.setImageResource(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
            ilp.leftMargin = ui.dp(6);
            row.addView(iv, ilp);
        }

        String who = NativeData.str(w, "who", NativeData.str(w, "name", "?"));
        if (who.length() > 20) {
            who = who.substring(0, 20) + "…";
        }
        // 名字按内容宽（最多 130dp），模型串吃剩下的并省略 —— 反过来的话
        // 一长串模型名会把账号名挤成 0 宽，玩家直接看不见是谁
        TextView name = ui.text(who, 12, M3.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = ui.dp(6);
        name.setMaxWidth(ui.dp(130));
        row.addView(name, nlp);

        String plan = Zh.of(NativeData.str(w, "plan", ""));
        if (plan.isEmpty() && "account".equals(NativeData.str(w, "kind", ""))) {
            plan = "Free";
        }
        if (!plan.isEmpty()) {
            TextView p = ui.text(plan, 9, M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams plp = ui.wrap();
            plp.leftMargin = ui.dp(4);
            row.addView(p, plp);
        }

        // 模型：同一账号的多个模型拼成一行（网页上也是这么紧凑）
        StringBuilder models = new StringBuilder();
        for (JSONObject x : same) {
            String mm = NativeData.str(x, "model", "");
            if (mm.isEmpty()) {
                continue;
            }
            if (models.length() > 0) {
                models.append(" · ");
            }
            models.append(shortModel(mm));
        }
        if (models.length() > 0) {
            TextView m = ui.mono(models.toString(), 9, M3.ON_SURFACE_VARIANT);
            m.setSingleLine(true);
            m.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = ui.dp(6);
            row.addView(m, mlp);
        }
        card.addView(row);

        double used = NativeData.dec(w, "used");
        double amount = NativeData.dec(w, "amount");
        double limit = NativeData.dec(w, "limit");
        String unit = Zh.of(NativeData.str(w, "unit", ""));
        StringBuilder sb = new StringBuilder();
        if (limit > 0) {
            sb.append(brief(amount)).append(" / ").append(brief(limit));
            if (!unit.isEmpty()) {
                sb.append(' ').append(unit);
            }
            sb.append(" · ");
        }
        sb.append("已用 ").append(NativeData.percent(used));

        if (used >= 90) {
            sb.append(" · 留到最后");
        } else if (isPaced(w)) {
            sb.append(" · 先用积分更早到期的账号");
        }
        JSONArray renews = NativeData.arr(w, "renews");
        if (renews.length() > 0) {
            String u = NativeData.until(renews.optString(0, ""));
            if (!u.isEmpty()) {
                sb.append(" · ").append(u).append("重置");
            }
        }
        JSONObject rest = NativeData.obj(w, "rest");
        if (rest != null) {
            String u = NativeData.until(NativeData.str(rest, "until", ""));
            sb.append(" · 暂停使用");
            if (!u.isEmpty()) {
                sb.append(' ').append(u).append("恢复");
            }
        }
        if (NativeData.flag(w, "fallback")) {
            sb.append(" · 备用");
        }

        TextView info = ui.text(sb.toString(), 10.5f,
                used >= 90 ? M3.ERROR : M3.ON_SURFACE_VARIANT, false);
        info.setLineSpacing(ui.dp(1), 1f);
        info.setLayoutParams(ui.matchWrap(4));
        card.addView(info);

        card.addView(ui.quotaBar(Math.max(0, Math.min(100, used)),
                100 - Math.max(0, Math.min(100, used))));

        if (picked) {
            card.addView(stateLine(w, tries, done, status));
        }

        // 点开看这条请求的上下文窗口（跟网页上点一行一样）。
        // 用**这张卡自己**那条 route，而不是全局的「最新一条」——
        // 两个请求连着来时，点旧卡会打开新请求的上下文。
        final JSONObject mine = routeOfCard;
        ui.clickable(card, bg);
        card.setOnClickListener(v -> {
            if (mine != null) {
                CtxSheet.show((android.app.Activity) host, ui, mine);
            }
        });
        return card;
    }

    private View stateLine(JSONObject w, JSONArray tries, boolean done, int status) {
        String text;
        int role = M3.PRIMARY;

        JSONObject t = null;
        for (int i = tries.length() - 1; i >= 0; i--) {
            JSONObject x = tries.optJSONObject(i);
            if (x != null && NativeData.str(x, "id", "").equals(NativeData.str(w, "id", ""))) {
                t = x;
                break;
            }
        }

        if (t == null) {
            text = "这个请求没有经过它";
            role = M3.ON_SURFACE_VARIANT;
        } else if (!NativeData.flag(t, "done")) {
            long ms = System.currentTimeMillis()
                    - NativeData.parseTime(NativeData.str(t, "start", ""));
            text = "回答中… " + Math.max(1, ms / 1000) + "s";
        } else {
            int st = NativeData.num(t, "status");
            long ms = (long) NativeData.dec(t, "ms");
            if (st >= 400) {
                text = "失败（" + st + "）";
                role = M3.ERROR;
            } else {
                text = "回答了这个请求" + (ms > 0 ? " · " + NativeData.ms(ms) : "");
            }
        }

        TextView v = ui.textColor(text, 11, ui.col(role), true);
        v.setLayoutParams(ui.matchWrap(5));
        return v;
    }

    private View excluded(JSONObject route) {
        JSONArray left = NativeData.arr(route, "left");
        LinearLayout box = ui.column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(16), ui.dp(2), ui.dp(16), 0);
        box.setLayoutParams(lp);
        if (left.length() == 0) {
            return box;
        }

        final LinearLayout detail = ui.column();
        final TextView arrow = ui.text("\u25B8", 10, M3.ON_SURFACE_VARIANT, false);
        LinearLayout head = ui.row();
        TextView label = ui.text(left.length() + " 个被排除", 10.5f,
                M3.ON_SURFACE_VARIANT, false);
        head.addView(label, ui.weight(1));
        head.addView(arrow);
        head.setClickable(true);
        head.setOnClickListener(v -> {
            boolean open = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "\u25BE" : "\u25B8");
        });
        box.addView(head);

        for (int i = 0; i < left.length(); i++) {
            JSONObject w = left.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String who = NativeData.str(w, "who", NativeData.str(w, "name", "?"));
            StringBuilder why = new StringBuilder();
            if (NativeData.flag(w, "barred")) {
                why.append("不服务这个模型");
            }
            if (NativeData.flag(w, "unlisted")) {
                if (why.length() > 0) {
                    why.append(" · ");
                }
                why.append("仅供路由组");
            }
            JSONObject rest = NativeData.obj(w, "rest");
            if (rest != null) {
                if (why.length() > 0) {
                    why.append(" · ");
                }
                why.append("暂停使用");
            }
            if (why.length() == 0) {
                why.append("不在候选里");
            }
            TextView t = ui.text("· " + who + "：" + why, 10,
                    M3.ON_SURFACE_VARIANT, false);
            t.setPadding(ui.dp(6), ui.dp(2), 0, 0);
            detail.addView(t);
        }
        detail.setVisibility(View.GONE);
        box.addView(detail);
        return box;
    }

    private boolean isPaced(JSONObject w) {
        return "pace".equals(NativeData.str(w, "routing", ""));
    }

    private String shortModel(String m) {
        int slash = m.indexOf('/');
        return slash > 0 && slash < m.length() - 1 ? m.substring(slash + 1) : m;
    }

    private String brief(double v) {
        long n = Math.round(v);
        if (n < 100_000) {
            if (v == Math.rint(v)) {
                return String.valueOf(n);
            }
            String s = String.format(java.util.Locale.US, "%.2f", v);
            while (s.endsWith("0")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        }
        return NativeData.tokens(n);
    }

    static final class RouteLines extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Ui ui;

        RouteLines(android.content.Context ctx, Ui ui) {
            super(ctx);
            this.ui = ui;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.col(M3.OUTLINE_VARIANT));
        }

        void setLive(boolean live, boolean failed) {
            if (!live) {
                paint.setColor(ui.col(M3.OUTLINE_VARIANT));
                paint.setPathEffect(null);
            } else {
                paint.setColor(failed ? ui.col(M3.ERROR) : 0xFF34A853);
                float dash = ui.dp(5);
                paint.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{dash, dash * 0.9f}, 0));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float fromX = w * 0.72f;
            float toX = w * 0.10f;
            path.reset();
            path.moveTo(fromX, 0);
            path.lineTo(fromX, h * 0.5f);
            path.quadTo(fromX, h * 0.82f, toX, h * 0.82f);
            path.lineTo(toX, h);
            canvas.drawPath(path, paint);
        }
    }
    /** 垂直 LinearLayout 的子项必须显式给 WRAP_CONTENT，否则会和别的子项平分整屏高度。 */
    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

}
