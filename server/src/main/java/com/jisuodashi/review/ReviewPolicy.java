package com.jisuodashi.review;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 评价口径的唯一出处：好评门槛、可评期、标签白名单、冷启动阈值。 */
public final class ReviewPolicy {

    /** {@code score >= 4} 计好评（2026-08-25 确认）。 */
    public static final int POSITIVE_SCORE = 4;

    /** 服务结束后 15 天内可评，过期不再受理。 */
    public static final int REVIEW_WINDOW_DAYS = 15;

    /** 展示窗口：30 天滚动。 */
    public static final int STAT_WINDOW_DAYS = 30;

    /**
     * 冷启动阈值：不足这个条数不显示好评率，只显示条数。
     * 1 条 5 星刷出的「好评率 100%」会压过 52 条 96% 的老技师 —— 小样本只有比率会失真，
     * 绝对条数不会，所以藏的是率不是数。
     */
    public static final int MIN_REVIEWS_FOR_RATE = 5;

    public static final int MAX_CONTENT_LENGTH = 500;
    public static final int MAX_TAGS = 5;

    /** 高分标签（{@code score >= 4}）。 */
    public static final List<String> GOOD_TAGS = List.of(
            "TECHNIQUE_GOOD", "FORCE_RIGHT", "ATTENTIVE", "ON_TIME",
            "CLEAN", "GOOD_COMMUNICATION", "EFFECTIVE");

    /** 低分标签（{@code score < 4}）。整组替换而不是共用一套，低分才有归因价值。 */
    public static final List<String> BAD_TAGS = List.of(
            "FORCE_TOO_LIGHT", "FORCE_TOO_HEAVY", "LATE", "SHORT_TIME",
            "NOT_ATTENTIVE", "ENV_POOR", "NO_EFFECT");

    private static final Set<String> ALL_TAGS = allTags();

    private ReviewPolicy() {
    }

    public static boolean positive(int score) {
        return score >= POSITIVE_SCORE;
    }

    public static boolean knownTag(String code) {
        return code != null && ALL_TAGS.contains(code);
    }

    /** 标签必须和分档匹配：给 5 星却选「力度太重」是前端串档，不是有效输入。 */
    public static boolean tagMatchesScore(String code, int score) {
        return positive(score) ? GOOD_TAGS.contains(code) : BAD_TAGS.contains(code);
    }

    private static Set<String> allTags() {
        Set<String> set = new LinkedHashSet<>(GOOD_TAGS);
        set.addAll(BAD_TAGS);
        return Set.copyOf(set);
    }
}
