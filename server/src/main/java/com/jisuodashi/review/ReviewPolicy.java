package com.jisuodashi.review;

/**
 * 统计口径的唯一出处。评价本体（可评期、标签、长度上限）的规则在 {@link ReviewService}，
 * 这里只放「怎么把评价折算成技师卡上那几个数」——两者分开，改口径不会碰到写入路径。
 */
public final class ReviewPolicy {

    /** {@code score >= 4} 计好评（2026-08-25 确认）。 */
    public static final int POSITIVE_SCORE = 4;

    /** 展示窗口：30 天滚动。 */
    public static final int STAT_WINDOW_DAYS = 30;

    /**
     * 冷启动阈值：不足这个条数不显示好评率，只显示条数。
     * 1 条 5 星刷出的「好评率 100%」会压过 52 条 96% 的老技师 —— 小样本只有比率会失真，
     * 绝对条数不会，所以藏的是率不是数。
     */
    public static final int MIN_REVIEWS_FOR_RATE = 5;

    private ReviewPolicy() {
    }

    /**
     * {@code review} 表不存好评标志，落库时也不定格 —— 好评与否每次读时按当前口径算。
     * 代价是改阈值会连历史一起改写；换来的是不必为一个可以随时重算的派生位加一列。
     */
    public static boolean positive(int score) {
        return score >= POSITIVE_SCORE;
    }
}
