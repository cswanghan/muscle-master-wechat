package com.jisuodashi.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MySQL 8 container per JVM, shared by every {@link MysqlBackedTest} class.
 *
 * <p>Deliberately not a {@code @Container} field: that restarts the container per test class while
 * Spring caches one context across them, so the second class would run against a stopped database.
 * Testcontainers' Ryuk sidecar reaps this one when the JVM exits.
 *
 * <p>Server flags mirror {@code deploy/docker-compose.yml}, {@code innodb_lock_wait_timeout=5}
 * included: fail-fast lock waits are what the deadlock-retry path is written against, so a test
 * database on the 50s default would hide the behaviour it exists to prove.
 */
public final class MysqlTestDatabase {

    private static final Logger log = LoggerFactory.getLogger(MysqlTestDatabase.class);

    private MysqlTestDatabase() {
    }

    /**
     * Answered without touching {@link Holder}, so a machine with no daemon skips instead of dying
     * in a static initialiser.
     *
     * <p>Catches rather than propagates: {@code isDockerAvailable()} throws, it does not merely
     * return false, when the daemon is present but unusable — an incompatible API version, a
     * socket the user cannot read. "Cannot run here" is a skip in every one of those cases.
     */
    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError ex) {
            log.warn("Docker unusable, skipping MySQL integration tests: {}", ex.toString());
            return false;
        }
    }

    static MySQLContainer<?> instance() {
        return Holder.INSTANCE;
    }

    /** Lazy: the container starts on first use, i.e. after the Docker check has passed. */
    private static final class Holder {

        static final MySQLContainer<?> INSTANCE = start();

        private static MySQLContainer<?> start() {
            MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("muscle_master")
                    .withUsername("muscle")
                    .withPassword("muscle")
                    .withEnv("TZ", "Asia/Shanghai")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_0900_ai_ci",
                            "--innodb_lock_wait_timeout=5",
                            "--default-time-zone=+08:00")
                    .withUrlParam("connectionTimeZone", "Asia/Shanghai")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
            mysql.start();
            return mysql;
        }
    }

    /**
     * Points the datasource at the container. An initialiser rather than
     * {@code @DynamicPropertySource} because only this form can hang off the
     * {@link MysqlBackedTest} annotation instead of a shared superclass.
     */
    public static final class Datasource implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            MySQLContainer<?> mysql = instance();
            TestPropertyValues.of(
                    "spring.datasource.url=" + mysql.getJdbcUrl(),
                    "spring.datasource.username=" + mysql.getUsername(),
                    "spring.datasource.password=" + mysql.getPassword(),
                    "spring.datasource.driver-class-name=" + mysql.getDriverClassName()
            ).applyTo(context);
        }
    }
}
