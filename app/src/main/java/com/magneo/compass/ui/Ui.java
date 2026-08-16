package com.magneo.compass.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.magneo.compass.R;

/**
 * 统一的 dp/sp 换算与字号/触控目标常量。
 * 取代原先散落在 5 个文件里的 dp() / dp2() 私有助手，以及若干处 raw px padding。
 *
 * 设备 density = 2.0（320dpi 报告值）。真实物理 dpi≈220，1dp≈0.229mm。
 */
public final class Ui {

    private Ui() {}

    /** 真理罗盘共享色板：玄学黑金为主，青蓝只做弱科幻强调。 */
    public static final int COLOR_BG = Color.rgb(18, 13, 9);
    public static final int COLOR_BG_DEEP = Color.rgb(11, 8, 6);
    public static final int COLOR_PANEL = Color.rgb(29, 24, 16);
    public static final int COLOR_PANEL_ALT = Color.rgb(38, 31, 20);
    public static final int COLOR_GOLD = Color.rgb(212, 175, 55);
    public static final int COLOR_GOLD_DARK = Color.rgb(145, 116, 48);
    public static final int COLOR_GOLD_DIM = Color.rgb(120, 98, 50);
    public static final int COLOR_RED = Color.rgb(139, 30, 30);
    public static final int COLOR_TEXT = Color.rgb(232, 220, 192);
    public static final int COLOR_TEXT_DIM = Color.rgb(150, 137, 110);
    public static final int COLOR_TEXT_MUTED = Color.rgb(112, 94, 65);
    public static final int COLOR_AETHER = Color.rgb(70, 210, 214);
    public static final int COLOR_ERROR = Color.rgb(231, 76, 60);

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static float dpF(Context c, int v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static float dpF(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** 触控目标最小边长（dp）。系统实测：Music 顶带 76×76px（≈38dp 报告值），Settings 行 100×100px。 */
    public static final int TOUCH_MIN_DP = 44;

    /** 主字号梯度（sp）。 */
    public static final int TEXT_XL = 26;   // 卦名大字
    public static final int TEXT_LG = 19;   // 对话框标题/页面主标题
    public static final int TEXT_MD = 17;    // 设置页各分类标题
    public static final int TEXT_BASE = 16; // 列表行/文本查看
    public static final int TEXT_SM = 14;    // 提示/副文本
    public static final int TEXT_XS = 12;    // 分组标签/checkbox

    public static void styleTitle(TextView v) {
        v.setTextColor(COLOR_GOLD);
        v.setTextSize(TEXT_LG);
        v.setGravity(Gravity.CENTER);
    }

    public static void styleBody(TextView v) {
        v.setTextColor(COLOR_TEXT);
        v.setTextSize(TEXT_BASE);
    }

    public static void styleSubtle(TextView v) {
        v.setTextColor(COLOR_TEXT_DIM);
        v.setTextSize(TEXT_SM);
    }

    public static void stylePillButton(Button b) {
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(TEXT_SM);
        b.setBackgroundResource(R.drawable.bg_pill_dark);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
    }

    public static void styleIconButton(Button b) {
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(TEXT_BASE);
        b.setBackgroundResource(R.drawable.bg_oval_dark);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
    }

    public static void styleField(EditText e) {
        e.setTextColor(COLOR_TEXT);
        e.setHintTextColor(COLOR_TEXT_DIM);
        e.setTextSize(TEXT_SM);
        e.setBackgroundResource(R.drawable.bg_rect_gold);
        e.setGravity(Gravity.CENTER);
    }

    public static void styleCheck(CheckBox c) {
        c.setTextColor(COLOR_TEXT);
        c.setTextSize(TEXT_SM);
    }
}
