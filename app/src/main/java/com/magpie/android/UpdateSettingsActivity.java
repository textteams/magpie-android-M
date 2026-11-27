package com.magpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 更新设置：把散在各处的「更新渠道 / 更新源」收拢到这一页。
 *
 * <ul>
 *   <li>magpie 内核：稳定 / 测试渠道 + 自定义更新源（原 UpdateActivity 里的）</li>
 *   <li>壳软件：稳定 / 测试渠道 + 下载镜像（原 SettingsActivity 里的镜像）</li>
 * </ul>
 *
 * <p>保存后直接杀掉 App 自身进程，确保渠道、镜像在下次启动时全部按新值生效。
 */
public class UpdateSettingsActivity extends Activity {

    private int[] c;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update_settings);
        c = M3.current(this);

        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) {
            View page = findViewById(R.id.update_settings_root);
            page.setPadding(page.getPaddingLeft(),
                    getResources().getDimensionPixelSize(sbId) + page.getPaddingTop(),
                    page.getPaddingRight(), page.getPaddingBottom());
        }

        // ---- magpie 内核渠道 ----
        RadioButton coreStable = findViewById(R.id.core_stable);
        RadioButton coreTest = findViewById(R.id.core_test);
        (Prefs.stableChannel(this) ? coreStable : coreTest).setChecked(true);

        // ---- 壳软件渠道 ----
        RadioButton shellStable = findViewById(R.id.shell_stable);
        RadioButton shellTest = findViewById(R.id.shell_test);
        (Prefs.shellStableChannel(this) ? shellStable : shellTest).setChecked(true);

        // ---- magpie 内核更新源 ----
        final EditText feed = findViewById(R.id.core_feed);
        feed.setText(Prefs.updateFeed(this));

        // ---- 壳软件镜像 ----
        final TextView mirrorValue = findViewById(R.id.shell_mirror_value);
        refreshMirrorLabel(mirrorValue);
        mirrorValue.setOnClickListener(v -> showMirrorDialog(mirrorValue));
        findViewById(R.id.shell_mirror_add).setOnClickListener(v ->
                showAddMirrorDialog(mirrorValue));

        // ---- 保存并重启 ----
        findViewById(R.id.update_settings_save).setOnClickListener(v -> {
            Prefs.setStableChannel(this, coreStable.isChecked());
            Prefs.setShellStableChannel(this, shellStable.isChecked());
            Prefs.setUpdateFeed(this, feed.getText().toString());
            Toast.makeText(this, R.string.update_settings_saved, Toast.LENGTH_SHORT).show();
            // 杀掉 App 自身，确保所有设置下次启动全部按新值生效
            v.postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }, 400);
        });

        applyTheme();
    }

    private void refreshMirrorLabel(TextView mirrorValue) {
        String cur = Prefs.shellMirror(this);
        if (cur == null || cur.isEmpty()) {
            mirrorValue.setText(R.string.shell_mirror_official);
        } else {
            mirrorValue.setText(cur);
        }
    }

    private void showMirrorDialog(TextView mirrorValue) {
        // 复用统一的选择器（含每行「测」测试）；选中即写 Prefs 并回填标签
        UpdateMirror.showPicker(this, () -> refreshMirrorLabel(mirrorValue));
    }

    private void showAddMirrorDialog(TextView mirrorValue) {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/");
        input.setSingleLine(true);
        input.setTextColor(c[M3.ON_SURFACE]);
        input.setHintTextColor(c[M3.ON_SURFACE_VARIANT]);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.shell_mirror_add_title)
                .setMessage(R.string.shell_mirror_add_hint)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String p = input.getText().toString().trim();
                    if (!p.isEmpty()) {
                        if (!p.endsWith("/")) {
                            p = p + "/";
                        }
                        Prefs.addShellCustomMirror(this, p);
                        Prefs.setShellMirror(this, p);
                        refreshMirrorLabel(mirrorValue);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    private void styleDialog(AlertDialog dlg) {
        try {
            if (dlg.getWindow() != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
                bg.setCornerRadius(dp(24));
                int stroke = (c[M3.PRIMARY] & 0x00FFFFFF) | 0x52000000;
                bg.setStroke(Math.max(1, dp(1.5f)), stroke);
                dlg.getWindow().setBackgroundDrawable(bg);
            }
            dlg.setOnShowListener(x -> {
                GradientDrawable btnBg = new GradientDrawable();
                btnBg.setColor(0x00000000);
                btnBg.setCornerRadius(dp(999));
                btnBg.setStroke(Math.max(1, dp(1.2f)),
                        (c[M3.PRIMARY] & 0x00FFFFFF) | 0x52000000);
                for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                        AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                    Button b = dlg.getButton(id);
                    if (b != null) {
                        b.setTextColor(c[M3.PRIMARY]);
                        b.setBackground(btnBg);
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** M3：surface 底 + 卡片 + 文字 + 控件按令牌上色。 */
    private void applyTheme() {
        findViewById(R.id.update_settings_root).setBackgroundColor(c[M3.SURFACE]);
        ((TextView) findViewById(R.id.update_settings_title)).setTextColor(c[M3.ON_SURFACE]);

        int[] cards = {R.id.card_core_channel, R.id.card_shell_channel,
                R.id.card_core_feed, R.id.card_shell_mirror};
        for (int id : cards) {
            View card = findViewById(id);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c[M3.SURFACE_CONTAINER_LOW]);
            bg.setCornerRadius(dp(20));
            card.setBackground(bg);
        }

        int[] labels = {R.id.label_core_channel, R.id.label_shell_channel,
                R.id.label_core_feed, R.id.label_shell_mirror};
        for (int id : labels) {
            ((TextView) findViewById(id)).setTextColor(c[M3.ON_SURFACE]);
        }
        int[] hints = {R.id.hint_core_channel, R.id.hint_shell_channel,
                R.id.hint_core_feed, R.id.hint_shell_mirror};
        for (int id : hints) {
            ((TextView) findViewById(id)).setTextColor(c[M3.ON_SURFACE_VARIANT]);
        }

        // 渠道单选框
        RadioButton[] rbs = {findViewById(R.id.core_stable), findViewById(R.id.core_test),
                findViewById(R.id.shell_stable), findViewById(R.id.shell_test)};
        for (RadioButton rb : rbs) {
            rb.setTextColor(c[M3.ON_SURFACE]);
        }

        // 更新源输入框
        EditText feed = findViewById(R.id.core_feed);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(c[M3.SURFACE_CONTAINER_HIGHEST]);
        fb.setCornerRadius(dp(12));
        fb.setStroke(dp(1), c[M3.OUTLINE_VARIANT]);
        feed.setBackground(fb);
        feed.setTextColor(c[M3.ON_SURFACE]);
        feed.setHintTextColor(c[M3.ON_SURFACE_VARIANT]);

        // 镜像值框
        TextView mirror = findViewById(R.id.shell_mirror_value);
        GradientDrawable mb = new GradientDrawable();
        mb.setColor(c[M3.SURFACE_CONTAINER_HIGHEST]);
        mb.setCornerRadius(dp(12));
        mb.setStroke(dp(1), c[M3.OUTLINE_VARIANT]);
        mirror.setBackground(mb);
        mirror.setTextColor(c[M3.ON_SURFACE]);

        TextView add = findViewById(R.id.shell_mirror_add);
        add.setTextColor(c[M3.PRIMARY]);

        // 保存按钮
        Button save = findViewById(R.id.update_settings_save);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(c[M3.PRIMARY]);
        sb.setCornerRadius(dp(20));
        save.setBackground(sb);
        save.setTextColor(c[M3.ON_PRIMARY]);
        save.setAllCaps(false);
        save.setStateListAnimator(null);
    }
}
