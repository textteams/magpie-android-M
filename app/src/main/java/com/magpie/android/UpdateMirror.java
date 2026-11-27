package com.magpie.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 更新源（下载镜像）选择 + 连通性测试的共用 UI 工具。
 *
 * <p>UpdateActivity（更新页的「更换更新源」）和 UpdateSettingsActivity（更新设置里的镜像）
 * 共用同一套列表对话框与测量逻辑，避免两处分叉。选中即写 {@link Prefs#setShellMirror}，
 * 两边用同一个 key，保证一致。列表末尾有「自定义…」可输入任意 http(s) 前缀。
 */
final class UpdateMirror {

    private UpdateMirror() {
    }

    /** 弹出镜像列表：点一行=选用并写 Prefs；每行右侧「测」=后台探测并回填 HTTP码/耗时；末尾「自定义…」。 */
    static void showPicker(final Activity act, final Runnable onPicked) {
        final int[] c = M3.current(act);
        final List<String> prefixes = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < ShellUpdater.MIRROR_PREFIXES.length; i++) {
            prefixes.add(ShellUpdater.MIRROR_PREFIXES[i]);
            names.add(ShellUpdater.MIRROR_NAMES[i]);
        }
        for (String p : Prefs.shellCustomMirrors(act)) {
            prefixes.add(p);
            names.add(p);
        }

        final Dialog dlg = new Dialog(act, R.style.Theme_Magpie_Sheet);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
        int stroke = (c[M3.PRIMARY] & 0x00FFFFFF) | 0x52000000;
        bg.setStroke(dp(act, 2), stroke);
        float r = dp(act, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        int pad = dp(act, 16);
        root.setPadding(pad, dp(act, 14), pad,
                dp(act, 16) + MainActivity.barHeightOf(act, "navigation_bar_height"));

        TextView title = new TextView(act);
        title.setText(R.string.shell_mirror_dialog_title);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTextColor(c[M3.ON_SURFACE]);
        root.addView(title);

        ScrollView sc = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);

        final String[] curRef = {Prefs.shellMirror(act)};
        final List<TextView> nameViews = new ArrayList<>();
        final List<String> baseNames = new ArrayList<>();

        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            final String prefix = prefixes.get(i);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
            rbg.setCornerRadius(dp(act, 14));
            rbg.setStroke(dp(act, 1), c[M3.OUTLINE_VARIANT]);
            row.setBackground(rbg);
            row.setPadding(dp(act, 12), dp(act, 11), dp(act, 10), dp(act, 11));

            LinearLayout textWrap = new LinearLayout(act);
            textWrap.setOrientation(LinearLayout.VERTICAL);
            boolean isCur = (curRef[0] == null ? "" : curRef[0]).equals(prefix);
            TextView nameView = new TextView(act);
            nameView.setText(isCur ? (name + "  ✓") : name);
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            nameView.setTextColor(c[M3.ON_SURFACE]);
            textWrap.addView(nameView);
            nameViews.add(nameView);
            baseNames.add(name);
            final TextView resultView = new TextView(act);
            resultView.setText("");
            resultView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            resultView.setTextColor(c[M3.ON_SURFACE_VARIANT]);
            textWrap.addView(resultView);
            row.addView(textWrap, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView test = new TextView(act);
            test.setText(R.string.update_mirror_test);
            test.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            test.setTextColor(c[M3.PRIMARY]);
            test.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
            GradientDrawable tbg = new GradientDrawable();
            tbg.setColor(0x00000000);
            tbg.setCornerRadius(dp(act, 999));
            tbg.setStroke(dp(act, 1), (c[M3.PRIMARY] & 0x00FFFFFF) | 0x52000000);
            test.setBackground(tbg);
            test.setClickable(true);
            test.setOnClickListener(v -> {
                resultView.setText(R.string.update_testing);
                test.setEnabled(false);
                probeAsync(act, prefix, res -> {
                    resultView.setText(res);
                    test.setEnabled(true);
                });
            });
            row.addView(test, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                dlg.dismiss();
                Prefs.setShellMirror(act, prefix);
                Toast.makeText(act, act.getString(R.string.update_mirror_switched, name),
                        Toast.LENGTH_SHORT).show();
                if (onPicked != null) {
                    onPicked.run();
                }
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(act, 6));
            list.addView(row, rlp);
        }

        // ---- 自定义更新源项 ----
        LinearLayout customRow = new LinearLayout(act);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
        cbg.setCornerRadius(dp(act, 14));
        cbg.setStroke(dp(act, 1), c[M3.OUTLINE_VARIANT]);
        customRow.setBackground(cbg);
        customRow.setPadding(dp(act, 12), dp(act, 11), dp(act, 10), dp(act, 11));
        final TextView customLabel = new TextView(act);
        customLabel.setText("自定义…");
        customLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        customLabel.setTextColor(c[M3.ON_SURFACE]);
        customRow.addView(customLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView customArrow = new TextView(act);
        customArrow.setText("\u203A");
        customArrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        customArrow.setTextColor(c[M3.PRIMARY]);
        customArrow.setPadding(dp(act, 10), 0, dp(act, 6), 0);
        customRow.addView(customArrow);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(act, 6));
        list.addView(customRow, clp);

        final Runnable refreshSel = () -> {
            String cur2 = curRef[0] == null ? "" : curRef[0];
            boolean isStd = false;
            for (int i = 0; i < nameViews.size(); i++) {
                String pf = prefixes.get(i);
                boolean on = pf != null && pf.equals(cur2);
                if (on) {
                    isStd = true;
                }
                nameViews.get(i).setText(on ? (baseNames.get(i) + "  ✓") : baseNames.get(i));
            }
            if (!cur2.isEmpty() && !isStd) {
                customLabel.setText("自定义：" + cur2 + "  ✓");
            } else {
                customLabel.setText("自定义…");
            }
        };
        refreshSel.run();

        customRow.setOnClickListener(v -> showCustomInput(act, curRef[0], newPrefix -> {
            curRef[0] = newPrefix;
            refreshSel.run();
        }));

        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 380)));

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
        }
        dlg.show();
    }

    /** 自定义更新源输入框：空=GitHub 官方，否则必须是 http(s):// 前缀（自动补尾 /）。 */
    private static void showCustomInput(final Activity act, final String current,
                                        final Consumer<String> onSaved) {
        final EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(current == null ? "" : current);
        input.setHint(customHint());
        new android.app.AlertDialog.Builder(act)
                .setTitle(R.string.update_change_source)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String p = normalizePrefix(input.getText().toString());
                    if (p == null) {
                        Toast.makeText(act, "无效的更新源：需为空或 http(s):// 前缀",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.setShellMirror(act, p);
                    Toast.makeText(act,
                            act.getString(R.string.update_mirror_switched, "自定义"),
                            Toast.LENGTH_SHORT).show();
                    if (onSaved != null) {
                        onSaved.accept(p);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 规范化：空=官方；必须 http(s):// 且不以 / 结尾补 /；不合法返回 null。 */
    private static String normalizePrefix(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        String low = s.toLowerCase(java.util.Locale.US);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            return null;
        }
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        return s;
    }

    /** core 若提供 ShellUpdater.CUSTOM_MIRROR_HINT 就用它，否则用本地提示。 */
    private static String customHint() {
        try {
            java.lang.reflect.Field f = ShellUpdater.class.getField("CUSTOM_MIRROR_HINT");
            Object v = f.get(null);
            if (v instanceof String) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        return "例如 https://gh-proxy.com/ 或自建反代前缀";
    }

    /** 测试「当前 Prefs 里的镜像」，结果回到主线程。 */
    static void testCurrentAsync(final Activity act, final Consumer<String> cb) {
        probeAsync(act, Prefs.shellMirror(act), cb);
    }

    /** 后台探测某个镜像前缀；结果形如「206 · 0.8s」/「HTTP 502 · 1.2s」/「失败：…」。 */
    static void probeAsync(final Activity act, final String prefix, final Consumer<String> cb) {
        new Thread(() -> {
            final String res = probe(prefix);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (act != null && !act.isFinishing() && !act.isDestroyed() && cb != null) {
                    cb.accept(res);
                }
            });
        }, "magpie-mirror-test").start();
    }

    private static String probe(String prefix) {
        String p = prefix == null ? "" : prefix.trim();
        String core = coreTest(p);
        if (core != null) {
            return core;
        }
        String target = "https://github.com/" + ShellUpdater.owner() + "/"
                + ShellUpdater.repo() + "/releases.atom";
        String url = p + target;
        long t0 = System.currentTimeMillis();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setInstanceFollowRedirects(true);
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "magpie-android");
            con.setRequestProperty("Range", "bytes=0-0");
            int code = con.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            String secs = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
            if (code >= 200 && code < 400) {
                return code + " · " + secs;
            }
            return "HTTP " + code + " · " + secs;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > 40) {
                msg = msg.substring(0, 40) + "…";
            }
            return "失败：" + msg;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /** core 若提供 {@code public static String testMirror(String)}，优先用它；否则 null 走本地探测。 */
    private static String coreTest(String prefix) {
        try {
            java.lang.reflect.Method m = ShellUpdater.class.getMethod("testMirror", String.class);
            if (m.getReturnType() == String.class) {
                Object r = m.invoke(null, prefix);
                return r == null ? null : String.valueOf(r);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
