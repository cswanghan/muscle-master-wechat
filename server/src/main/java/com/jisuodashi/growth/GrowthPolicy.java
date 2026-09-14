package com.jisuodashi.growth;

/** 成长域的口径。凡是"几分""多少算达标"的判断都收在这里。 */
public final class GrowthPolicy {

    private GrowthPolicy() {
    }

    /** 好评线：>= 4 分算好评，与站内评价 {@code ReviewPolicy} 保持同一条线。 */
    public static final int POSITIVE_SCORE = 4;

    /** 课后回访应在上课后几天内完成。 */
    public static final int AFTER_CLASS_DUE_DAYS = 1;

    /** 余次低于这个数就该催续费了。 */
    public static final int RETENTION_THRESHOLD_SESSIONS = 3;

    /** 多久没来算"长期未到访"。 */
    public static final int DORMANT_DAYS = 45;

    /** 课包到期前多少天开始提醒续费。 */
    public static final int EXPIRING_SOON_DAYS = 30;

    /**
     * 配合度打分：按应打卡天数里实际打了几天换算成 1–5。
     *
     * <p>用打卡率而不是让老师主观打分 —— 主观分会全是 5，失去区分度，
     * 而续费话术要靠这个数说"您这个阶段只完成了六成作业"。
     */
    public static int complianceScore(int doneDays, int expectedDays) {
        if (expectedDays <= 0) {
            return 0;
        }
        int pct = Math.min(100, doneDays * 100 / expectedDays);
        if (pct >= 90) {
            return 5;
        }
        if (pct >= 70) {
            return 4;
        }
        if (pct >= 50) {
            return 3;
        }
        return pct >= 25 ? 2 : 1;
    }

    /** 百分比，x100 定点，避免浮点在报表里飘。 */
    public static int rateX100(long hit, long total) {
        return total <= 0 ? 0 : (int) (hit * 10_000 / total);
    }

    /**
     * 按完成情况生成鼓励话语。刻意写得具体 ——
     * 泛泛的"继续加油"看两次就被无视了，带数字的才有人读。
     */
    public static String encourage(int lessons, int positiveRateX100, int followUpRateX100) {
        if (lessons <= 0) {
            return "这个月还没开张，先把手上的回访打完，约课就来了。";
        }
        if (positiveRateX100 >= 9500 && lessons >= 60) {
            return "本月 " + lessons + " 节课，好评率 " + fmt(positiveRateX100) + "，量和口碑都稳，保持住。";
        }
        if (followUpRateX100 < 6000) {
            return "上了 " + lessons + " 节课，但回访只完成 " + fmt(followUpRateX100)
                    + "。回访是下一次成交的入口，别漏。";
        }
        if (positiveRateX100 < 8000) {
            return "本月 " + lessons + " 节课，好评率 " + fmt(positiveRateX100)
                    + "，先看看最近几条差评说了什么。";
        }
        return "本月 " + lessons + " 节课，节奏不错，再把好评认领补一补。";
    }

    private static String fmt(int x100) {
        return (x100 / 100) + "%";
    }
}
