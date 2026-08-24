package com.magneo.compass;

/** 一次摇卦结果。lines 从初爻到上爻，6/9 为动爻，7/8 为静爻。 */
public class OracleReading {
    public final long id;
    public final long createdAt;
    public final int[] lines;
    public final OracleBook.Entry primary;
    public final OracleBook.Entry changed;
    public final int movingCount;
    public String aiStatus = "";
    public String aiText = "";

    OracleReading(long id, long createdAt, int[] lines, OracleBook.Entry primary,
                  OracleBook.Entry changed, int movingCount) {
        this.id = id;
        this.createdAt = createdAt;
        this.lines = lines;
        this.primary = primary;
        this.changed = changed;
        this.movingCount = movingCount;
    }

    public boolean hasMovingLines() {
        return movingCount > 0 && changed != null;
    }

    public String movingLabel() {
        if (movingCount <= 0) return "无";
        String[] names = {"初", "二", "三", "四", "五", "上"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < names.length; i++) {
            if (lines[i] == 6 || lines[i] == 9) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(names[i]);
            }
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    public String title() {
        if (!hasMovingLines()) return primary.name;
        return primary.name + " 之 " + changed.name;
    }

    public String prompt() {
        StringBuilder yao = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) yao.append(' ');
            yao.append(i + 1).append(':').append(lineName(lines[i]));
        }
        return "本卦：" + primary.name + "（上" + primary.upper + "下" + primary.lower + "）。"
                + "动爻：" + movingLabel() + "。"
                + (hasMovingLines() ? "变卦：" + changed.name + "（上" + changed.upper + "下" + changed.lower + "）。" : "无变卦。")
                + "六爻：" + yao + "。"
                + "本地简解：" + primary.summary + " 建议：" + primary.advice;
    }

    private static String lineName(int v) {
        switch (v) {
            case 6: return "老阴动";
            case 7: return "少阳";
            case 8: return "少阴";
            case 9: return "老阳动";
            default: return "?";
        }
    }
}
