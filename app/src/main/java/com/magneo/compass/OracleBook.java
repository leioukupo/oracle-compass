package com.magneo.compass;

import java.util.Random;

/** 轻量周易卦库与摇卦算法，保证离线可读。 */
public final class OracleBook {
    private OracleBook() {}

    public static final class Entry {
        public final int number;
        public final String name;
        public final String upper;
        public final String lower;
        public final String summary;
        public final String advice;

        private Entry(int number, String name, String upper, String lower,
                      String summary, String advice) {
            this.number = number;
            this.name = name;
            this.upper = upper;
            this.lower = lower;
            this.summary = summary;
            this.advice = advice;
        }
    }

    private static final String[] TRIGRAM = {"坤", "震", "坎", "兑", "艮", "离", "巽", "乾"};
    private static final String[] ELE = {"地", "雷", "水", "泽", "山", "火", "风", "天"};
    private static final String[] NAMES = {
            "坤", "豫", "比", "萃", "剥", "晋", "观", "否",
            "复", "震", "屯", "随", "颐", "噬嗑", "益", "无妄",
            "师", "解", "坎", "困", "蒙", "未济", "涣", "讼",
            "临", "归妹", "节", "兑", "损", "睽", "中孚", "履",
            "谦", "小过", "蹇", "咸", "艮", "旅", "渐", "遁",
            "明夷", "丰", "既济", "革", "贲", "离", "家人", "同人",
            "升", "恒", "井", "大过", "蛊", "鼎", "巽", "姤",
            "泰", "大壮", "需", "夬", "大畜", "大有", "小畜", "乾"
    };
    private static final int[] NUMBERS = {
            2, 16, 8, 45, 23, 35, 20, 12,
            24, 51, 3, 17, 27, 21, 42, 25,
            7, 40, 29, 47, 4, 64, 59, 6,
            19, 54, 60, 58, 41, 38, 61, 10,
            15, 62, 39, 31, 52, 56, 53, 33,
            36, 55, 63, 49, 22, 30, 37, 13,
            46, 32, 48, 28, 18, 50, 57, 44,
            11, 34, 5, 43, 26, 14, 9, 1
    };

    public static OracleReading cast(long seed, long createdAt) {
        Random random = new Random(seed);
        int[] lines = new int[6];
        for (int i = 0; i < lines.length; i++) {
            int sum = 0;
            for (int c = 0; c < 3; c++) sum += random.nextBoolean() ? 3 : 2;
            lines[i] = sum;
        }
        return read(lines, seed, createdAt);
    }

    public static OracleReading read(int[] lines, long seed, long createdAt) {
        int[] base = new int[6];
        int[] changed = new int[6];
        int moving = 0;
        for (int i = 0; i < 6; i++) {
            int v = lines[i];
            base[i] = (v == 7 || v == 9) ? 1 : 0;
            if (v == 6) {
                changed[i] = 1;
                moving++;
            } else if (v == 9) {
                changed[i] = 0;
                moving++;
            } else {
                changed[i] = base[i];
            }
        }
        Entry primary = entry(bits(base, 3), bits(base, 0), moving);
        Entry next = moving > 0 ? entry(bits(changed, 3), bits(changed, 0), moving) : null;
        return new OracleReading(seed, createdAt, lines, primary, next, moving);
    }

    private static int bits(int[] lines, int start) {
        int out = 0;
        for (int i = 0; i < 3; i++) if (lines[start + i] == 1) out |= (1 << i);
        return out;
    }

    private static Entry entry(int upperBits, int lowerBits, int moving) {
        int key = upperBits * 8 + lowerBits;
        String name = NAMES[key];
        String upper = TRIGRAM[upperBits];
        String lower = TRIGRAM[lowerBits];
        String summary = ELE[upperBits] + "在上，" + ELE[lowerBits] + "在下。"
                + meaning(name) + movingTone(moving);
        String advice = advice(name, moving);
        return new Entry(NUMBERS[key], name, upper, lower, summary, advice);
    }

    private static String movingTone(int moving) {
        if (moving <= 0) return "卦气较稳。";
        if (moving <= 2) return "变机已现。";
        if (moving <= 4) return "变化较急。";
        return "局面多变，先求定。";
    }

    private static String meaning(String name) {
        if ("乾".equals(name)) return "重在主动、开创与自强，宜正而不躁。";
        if ("坤".equals(name)) return "重在承载、配合与蓄势，宜稳中成事。";
        if ("屯".equals(name)) return "初始多阻，先立秩序再推进。";
        if ("蒙".equals(name)) return "信息未明，先学习求证。";
        if ("需".equals(name)) return "时机未至，等待中要有准备。";
        if ("讼".equals(name)) return "分歧已起，先降火再求证据。";
        if ("师".equals(name)) return "众力可用，但需要纪律与主心骨。";
        if ("比".equals(name)) return "适合结盟靠近，慎选同路人。";
        if ("小畜".equals(name)) return "力量尚小，以积累和修正为先。";
        if ("履".equals(name)) return "行事临险，守礼守边界可过。";
        if ("泰".equals(name)) return "上下通达，宜推进正事。";
        if ("否".equals(name)) return "闭塞不通，先保核心。";
        if ("同人".equals(name)) return "求同聚人，公开透明更有利。";
        if ("大有".equals(name)) return "资源在手，宜大处着眼。";
        if ("谦".equals(name)) return "谦下能受益，少显锋芒。";
        if ("豫".equals(name)) return "气势已动，乐中仍需有备。";
        if ("随".equals(name)) return "顺势跟随，但要保留判断。";
        if ("蛊".equals(name)) return "旧弊需治，先清根因。";
        if ("临".equals(name)) return "机会临近，宜亲临细看。";
        if ("观".equals(name)) return "先观察格局，再决定动作。";
        if ("噬嗑".equals(name)) return "有阻需咬合处理，宜果断。";
        if ("贲".equals(name)) return "重在修饰与秩序，外美不能遮内实。";
        if ("剥".equals(name)) return "根基受损，宜收缩防守。";
        if ("复".equals(name)) return "回归起点，适合重新开始。";
        if ("无妄".equals(name)) return "勿妄动，按事实行动。";
        if ("大畜".equals(name)) return "积蓄已成，仍需节制。";
        if ("颐".equals(name)) return "养正为先，留意输入与消耗。";
        if ("大过".equals(name)) return "承压过重，需改结构。";
        if ("坎".equals(name)) return "险中有险，稳住节奏。";
        if ("离".equals(name)) return "明亮可见，但需依附正道。";
        if ("咸".equals(name)) return "感应互通，宜真诚沟通。";
        if ("恒".equals(name)) return "贵在持续，不宜反复无常。";
        if ("遁".equals(name)) return "退避不是失败，是保存力量。";
        if ("大壮".equals(name)) return "势强不可鲁莽，守正才壮。";
        if ("晋".equals(name)) return "进展可期，宜借光而上。";
        if ("明夷".equals(name)) return "光被遮蔽，先藏锋护身。";
        if ("家人".equals(name)) return "内外有序，先理近处关系。";
        if ("睽".equals(name)) return "意见相背，求小同胜过求大同。";
        if ("蹇".equals(name)) return "前路艰难，换路或求援。";
        if ("解".equals(name)) return "困局可解，及时松绑。";
        if ("损".equals(name)) return "有所减损，反得清明。";
        if ("益".equals(name)) return "可增益扩展，但利他更稳。";
        if ("夬".equals(name)) return "当断则断，公开而不激烈。";
        if ("姤".equals(name)) return "突遇新机，先辨其性。";
        if ("萃".equals(name)) return "人事聚集，需有中心。";
        if ("升".equals(name)) return "循序上升，不贪快。";
        if ("困".equals(name)) return "受限之时，守信守心。";
        if ("井".equals(name)) return "资源恒在，重在修井用井。";
        if ("革".equals(name)) return "变革已近，需名正言顺。";
        if ("鼎".equals(name)) return "旧物可新，适合重组升级。";
        if ("震".equals(name)) return "惊动之后，定神再行。";
        if ("艮".equals(name)) return "止而后定，边界要清楚。";
        if ("渐".equals(name)) return "渐进有序，慢就是稳。";
        if ("归妹".equals(name)) return "关系未正，承诺需谨慎。";
        if ("丰".equals(name)) return "盛大之时，更要防遮蔽。";
        if ("旅".equals(name)) return "人在途中，低耗前进。";
        if ("巽".equals(name)) return "柔入细处，持续渗透。";
        if ("兑".equals(name)) return "悦而能通，言语有力。";
        if ("涣".equals(name)) return "散而可聚，先化隔阂。";
        if ("节".equals(name)) return "节制成度，过严则伤。";
        if ("中孚".equals(name)) return "诚信在中，可化疑。";
        if ("小过".equals(name)) return "小事可过，大事宜慎。";
        if ("既济".equals(name)) return "事已成形，防末端失守。";
        if ("未济".equals(name)) return "尚未完成，最后一步最要稳。";
        return "阴阳相推，宜观时取势。";
    }

    private static String advice(String name, int moving) {
        if (moving >= 4) return "先停大动作，拆成小步验证。";
        if ("乾".equals(name) || "大壮".equals(name) || "夬".equals(name)) return "可以推进，但要留余地。";
        if ("坤".equals(name) || "谦".equals(name) || "复".equals(name)) return "先稳住底盘，等趋势自然显现。";
        if ("坎".equals(name) || "蹇".equals(name) || "困".equals(name) || "否".equals(name)) return "避开硬冲，先找替代路径。";
        if ("革".equals(name) || "鼎".equals(name) || "益".equals(name) || "升".equals(name)) return "适合调整结构，边做边校准。";
        if ("咸".equals(name) || "兑".equals(name) || "同人".equals(name) || "中孚".equals(name)) return "先沟通意图，再落实行动。";
        return "取其一要点，今天只推进最确定的一步。";
    }
}
