package com.magneo.compass;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 圆屏自绘对话框：透明窗口 + 椭圆深底金边容器，内容都在圆内。 */
public class RoundDialog {
    private final Dialog dialog;
    private final LinearLayout body;
    private final Activity ctx;

    public RoundDialog(Activity a) {
        ctx = a;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setBackgroundResource(R.drawable.bg_dialog_oval);
        body.setClipToOutline(true);
        body.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        int p = dp(26);
        body.setPadding(p, p, p, p);
        dialog.setContentView(body);
    }

    public RoundDialog title(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextColor(Color.rgb(212, 175, 55));
        tv.setTextSize(19);
        tv.setGravity(Gravity.CENTER);
        body.addView(tv, lpWrap());
        return this;
    }

    public RoundDialog text(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextColor(Color.rgb(232, 220, 192));
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        body.addView(tv, lpWrap());
        return this;
    }

    /** 圆形“药丸”按钮，点击执行动作并关闭。 */
    public RoundDialog item(String label, final Runnable action) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextColor(Color.rgb(232, 220, 192));
        b.setBackgroundResource(R.drawable.bg_pill_dark);
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, dp(4), 0, dp(4));
        body.addView(b, lp);
        return this;
    }

    /** 添加输入框（如文件名/路径/重命名）。 */
    public RoundDialog field(EditText e) {
        e.setBackgroundResource(R.drawable.bg_pill_dark);
        e.setTextColor(Color.rgb(232, 220, 192));
        e.setHintTextColor(Color.rgb(120, 114, 98));
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = lpWrap();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.setMargins(0, dp(4), 0, dp(4));
        body.addView(e, lp);
        return this;
    }

    public RoundDialog cancel() {
        return item("取消", null);
    }

    public void show() {
        dialog.show();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
