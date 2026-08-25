/**
 * 评价体系与技师 30 天统计（P0）。
 *
 * <p>写侧挂在状态机的 {@code COMPLETED -> REVIEWED} 转移上（{@code OrderSide.REVIEW_RECORD}），
 * 读侧是 {@code therapist_stat_30d} 的主键批量查询 —— 展示接口一次返回整店技师，
 * 逐人聚合会把已在热路径上的 {@code GET /c/availability} 拖垮。
 *
 * <p>设计见 {@code docs/p1-review-level-design.md}。
 */
package com.jisuodashi.review;
