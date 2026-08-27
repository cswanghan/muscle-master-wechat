package com.jisuodashi.review;

import com.jisuodashi.common.JdbcTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产侧服务段来源。拉全历史明细（{@code ended_at IS NOT NULL}）而不是在 SQL 里开窗聚合：
 * 首访序号必须全历史算，窗口过滤在排序之后 —— 聚合逻辑收在 {@link TherapistStatCalculator}
 * 一处，dev/prod 同一条路径，不会出现两边口径悄悄分叉。
 *
 * <p>日更 job 一天跑一次，行数是「门店数 × 技师数 × 历史服务次数」量级；
 * 涨到吃不消时把这里换成 {@code ROW_NUMBER() OVER (PARTITION BY therapist_id, customer_id)}
 * 的聚合查询即可，接口不变。
 */
@Component
@Profile("!dev")
public class JdbcServedVisitSource implements ServedVisitSource {

    private final JdbcTemplate jdbc;

    public JdbcServedVisitSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ServedVisit> listCompletedVisits() {
        return jdbc.query(
                """
                SELECT therapist_id, customer_id, started_at
                  FROM service_record
                 WHERE ended_at IS NOT NULL AND started_at IS NOT NULL
                 ORDER BY started_at
                """,
                (rs, i) -> new ServedVisit(
                        rs.getLong("therapist_id"),
                        rs.getLong("customer_id"),
                        JdbcTimes.instant(rs.getTimestamp("started_at"))));
    }
}
