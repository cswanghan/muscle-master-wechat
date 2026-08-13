package com.jisuodashi.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jobs jobs = new Jobs();
    private final Snowflake snowflake = new Snowflake();

    public Jobs getJobs() {
        return jobs;
    }

    public Snowflake getSnowflake() {
        return snowflake;
    }

    public static class Jobs {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Snowflake {
        private long workerId = 1;

        public long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(long workerId) {
            this.workerId = workerId;
        }
    }
}
