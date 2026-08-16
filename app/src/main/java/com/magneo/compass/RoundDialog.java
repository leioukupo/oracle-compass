package com.magneo.compass;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.magneo.compass.ui.Ui;

/** 圆屏自绘对话框：透明窗口 + 椭圆深底金边容器，内容都在圆内。 */
public class RoundDialog {
    private final Dialog dialog;
    private final LinearLayout body;
    private final Activity ctx;
    private final int size;

    public RoundDialog(Activity a) {
        ctx = a;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        // 圆屏适配：对话框取屏幕短边 92% 的正方形，内容在圆内可滚动
        int sw = ctx.getResources().getDisplayMetrics().widthPixels;
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        size = (int) (Math.min(sw, sh) * 0.92f);

        com.magneo.compass.ui.RoundFrame wrap = new com.magneo.compass.ui.RoundFrame(a, true, true, 30);

        ScrollView scv = new ScrollView(a);
        scv.setFillViewport(false);   // 内容大于视口时按需滚动，不强制铺满导致按钮被压扁
        scv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 14));
        scv.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(scv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setContentView(wrap);
    }

    public RoundDialog title(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        Ui.styleTitle(tv);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(false);
        body.addView(tv, lpWrap());
        return this;
    }

    public RoundDialog text(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        Ui.styleBody(tv);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(Ui.dp(ctx, 2), 1f);
        body.addView(tv, lpWrap());
        return this;
    }

    /** 圆形“药丸”按钮，点击执行动作并关闭。 */
    public RoundDialog item(String label, final Runnable action) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        Ui.stylePillButton(b);
        b.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 7), Ui.dp(ctx, 12), Ui.dp(ctx, 7));
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, com.magneo.compass.ui.Ui.dp(ctx, 4), 0, com.magneo.compass.ui.Ui.dp(ctx, 4));
        body.addView(b, lp);
        return this;
    }

    /** 添加输入框（如文件名/路径/重命名）。 */
    public RoundDialog field(EditText e) {
        Ui.styleField(e);
        e.setSingleLine(true);
        e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 7), Ui.dp(ctx, 12), Ui.dp(ctx, 7));
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, com.magneo.compass.ui.Ui.dp(ctx, 4), 0, com.magneo.compass.ui.Ui.dp(ctx, 4));
        body.addView(e, lp);
        return this;
    }

    /** 添加多行输入框（长 prompt / 配置文本）。 */
    public RoundDialog fieldArea(EditText e) {
        Ui.styleField(e);
        e.setSingleLine(false);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8));
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = Ui.dp(ctx, 132);
        lp.setMargins(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        body.addView(e, lp);
        return this;
    }

    /** 添加任意控件（如下拉选择框），样式同输入框。 */
    public RoundDialog view(View v) {
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, com.magneo.compass.ui.Ui.dp(ctx, 4), 0, com.magneo.compass.ui.Ui.dp(ctx, 4));
        body.addView(v, lp);
        return this;
    }

    public RoundDialog cancel() {
        return item("取消", null);
    }

    public void show() {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(size, size);
        }
    }

    public void dismiss() {
        dialog.dismiss();
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
