package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDBC proof: REQUIRES_NEW keeps CUSTOMER_COLLISION after the merge TX rolls back 40908. */
@SpringJUnitConfig(CollisionTaskCommitTest.Config.class)
class CollisionTaskCommitTest {

    @Autowired
    private CollideThenFail collideThenFail;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void humanTaskRemainsAfter40908Rollback() {
        assertThatThrownBy(() -> collideThenFail.run("a".repeat(64)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.CUSTOMER_COLLISION);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM human_task WHERE task_type='CUSTOMER_COLLISION'", Integer.class);
        assertThat(count).isEqualTo(1);
        String bizKey = jdbc.queryForObject("SELECT biz_key FROM human_task", String.class);
        assertThat(bizKey).isNotNull().hasSizeLessThanOrEqualTo(64);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("collision_tx;MODE=MySQL")
                    .addScript("classpath:collision-human-task.sql")
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SnowflakeIdGenerator ids() {
            return new SnowflakeIdGenerator(new AppProperties());
        }

        @Bean
        RelatedRecordsRepository related(JdbcTemplate jdbc, SnowflakeIdGenerator ids, Clock clock) {
            return new JdbcRelatedRecordsRepository(jdbc, ids, clock);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        CollisionTaskWriter collisionTaskWriter(RelatedRecordsRepository related) {
            return new CollisionTaskWriter(related);
        }

        @Bean
        CollideThenFail collideThenFail(CollisionTaskWriter writer) {
            return new CollideThenFail(writer);
        }
    }

    static class CollideThenFail {

        private final CollisionTaskWriter writer;

        CollideThenFail(CollisionTaskWriter writer) {
            this.writer = writer;
        }

        @Transactional
        public void run(String phoneHash) {
            writer.record(phoneHash);
            throw new ApiException(ErrorCodes.CUSTOMER_COLLISION, "客户身份冲突");
        }
    }
}
