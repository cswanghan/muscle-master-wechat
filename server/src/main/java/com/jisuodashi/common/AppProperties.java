package com.jisuodashi.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jobs jobs = new Jobs();
    private final Snowflake snowflake = new Snowflake();
    private final Jwt jwt = new Jwt();
    private final Wechat wechat = new Wechat();
    private final Crypto crypto = new Crypto();
    private final Catalog catalog = new Catalog();
    private final Booking booking = new Booking();

    public Jobs getJobs() {
        return jobs;
    }

    public Snowflake getSnowflake() {
        return snowflake;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Wechat getWechat() {
        return wechat;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public Catalog getCatalog() {
        return catalog;
    }

    public Booking getBooking() {
        return booking;
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

    public static class Jwt {
        private String secret = "change-me-dev-only-hs256-key-32bytes!";
        private Duration customerTtl = Duration.ofHours(2);
        private Duration staffTtl = Duration.ofHours(8);

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getCustomerTtl() {
            return customerTtl;
        }

        public void setCustomerTtl(Duration customerTtl) {
            this.customerTtl = customerTtl;
        }

        public Duration getStaffTtl() {
            return staffTtl;
        }

        public void setStaffTtl(Duration staffTtl) {
            this.staffTtl = staffTtl;
        }
    }

    public static class Wechat {
        /** When true, code=dev / code=dev-staff never call WeChat. */
        private boolean mock = false;
        private String customerAppId = "";
        private String customerAppSecret = "";
        private String staffAppId = "";
        private String staffAppSecret = "";
        private String mockStaffUsername = "demo.admin";

        public boolean isMock() {
            return mock;
        }

        public void setMock(boolean mock) {
            this.mock = mock;
        }

        public String getCustomerAppId() {
            return customerAppId;
        }

        public void setCustomerAppId(String customerAppId) {
            this.customerAppId = customerAppId;
        }

        public String getCustomerAppSecret() {
            return customerAppSecret;
        }

        public void setCustomerAppSecret(String customerAppSecret) {
            this.customerAppSecret = customerAppSecret;
        }

        public String getStaffAppId() {
            return staffAppId;
        }

        public void setStaffAppId(String staffAppId) {
            this.staffAppId = staffAppId;
        }

        public String getStaffAppSecret() {
            return staffAppSecret;
        }

        public void setStaffAppSecret(String staffAppSecret) {
            this.staffAppSecret = staffAppSecret;
        }

        public String getMockStaffUsername() {
            return mockStaffUsername;
        }

        public void setMockStaffUsername(String mockStaffUsername) {
            this.mockStaffUsername = mockStaffUsername;
        }
    }

    public static class Crypto {
        private String phonePepper = "change-me-dev-phone-pepper";
        /** AES-256-GCM DEK, 32-byte Base64. Blank → SHA-256(pepper) (dev only). */
        private String dekBase64 = "";

        public String getPhonePepper() {
            return phonePepper;
        }

        public void setPhonePepper(String phonePepper) {
            this.phonePepper = phonePepper;
        }

        public String getDekBase64() {
            return dekBase64;
        }

        public void setDekBase64(String dekBase64) {
            this.dekBase64 = dekBase64;
        }
    }

    public static class Catalog {
        private int nearMeters = 1500;
        private Duration storeCacheTtl = Duration.ofMinutes(5);

        public int getNearMeters() {
            return nearMeters;
        }

        public void setNearMeters(int nearMeters) {
            this.nearMeters = nearMeters;
        }

        public Duration getStoreCacheTtl() {
            return storeCacheTtl;
        }

        public void setStoreCacheTtl(Duration storeCacheTtl) {
            this.storeCacheTtl = storeCacheTtl;
        }
    }

    public static class Booking {
        private final Captcha captcha = new Captcha();

        public Captcha getCaptcha() {
            return captcha;
        }

        public static class Captcha {
            private boolean enabled = false;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
