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
    private final Internal internal = new Internal();
    private final Availability availability = new Availability();
    private final Gray gray = new Gray();
    private final Flags flags = new Flags();

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

    public Internal getInternal() {
        return internal;
    }

    public Availability getAvailability() {
        return availability;
    }

    public Gray getGray() {
        return gray;
    }

    public Flags getFlags() {
        return flags;
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
        /** D17: platform default mchid when store.wx_mchid is blank. */
        private String mchid = "";
        /** WeChat prepay_id TTL; repay re-signs until this elapses. */
        private Duration prepayTtl = Duration.ofHours(2);

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

        public String getMchid() {
            return mchid;
        }

        public void setMchid(String mchid) {
            this.mchid = mchid;
        }

        public Duration getPrepayTtl() {
            return prepayTtl;
        }

        public void setPrepayTtl(Duration prepayTtl) {
            this.prepayTtl = prepayTtl;
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

    /**
     * Rollback-drill endpoints. Off by default; when on, require loopback
     * plus {@code X-Internal-Token}. Never expose :8080 unauthenticated.
     */
    public static class Internal {
        private final ForceRelease forceRelease = new ForceRelease();

        public ForceRelease getForceRelease() {
            return forceRelease;
        }

        public static class ForceRelease {
            private boolean enabled = false;
            private String token = "";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getToken() {
                return token;
            }

            public void setToken(String token) {
                this.token = token;
            }
        }
    }

    public static class Availability {
        private Duration cacheTtl = Duration.ofSeconds(30);

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }

    public static class Booking {
        private final Captcha captcha = new Captcha();
        /** Design §坏锁回滚: emergency stop-lock. Default on. */
        private final Lock lock = new Lock();
        /** C-end free cancel window before start (D18). */
        private int cancelFreeMinutes = 120;

        public Captcha getCaptcha() {
            return captcha;
        }

        public Lock getLock() {
            return lock;
        }

        public int getCancelFreeMinutes() {
            return cancelFreeMinutes;
        }

        public void setCancelFreeMinutes(int cancelFreeMinutes) {
            this.cancelFreeMinutes = cancelFreeMinutes;
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

        public static class Lock {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }

    public static class Gray {
        /** Comma-separated store ids. C-end only returns these. */
        private String storeIds = "3100000000000000001";

        public String getStoreIds() {
            return storeIds;
        }

        public void setStoreIds(String storeIds) {
            this.storeIds = storeIds;
        }
    }

    public static class Flags {
        private final BookingFlags booking = new BookingFlags();
        private final PayFlags pay = new PayFlags();
        private final WorkflowFlags workflow = new WorkflowFlags();

        public BookingFlags getBooking() {
            return booking;
        }

        public PayFlags getPay() {
            return pay;
        }

        public WorkflowFlags getWorkflow() {
            return workflow;
        }

        public static class BookingFlags {
            private boolean enabled = true;
            private final Lock lock = new Lock();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Lock getLock() {
                return lock;
            }

            public static class Lock {
                private boolean enabled = true;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }
            }
        }

        public static class PayFlags {
            private final Wechat wechat = new Wechat();

            public Wechat getWechat() {
                return wechat;
            }

            public static class Wechat {
                private boolean enabled = true;

                public boolean isEnabled() {
                    return enabled;
                }

                public void setEnabled(boolean enabled) {
                    this.enabled = enabled;
                }
            }
        }

        public static class WorkflowFlags {
            private final Toggle reschedule = new Toggle();
            private final Toggle addOn = new Toggle();
            private final Toggle swap = new Toggle();
            private final Toggle refund = new Toggle();

            public Toggle getReschedule() {
                return reschedule;
            }

            public Toggle getAddOn() {
                return addOn;
            }

            public Toggle getSwap() {
                return swap;
            }

            public Toggle getRefund() {
                return refund;
            }
        }

        public static class Toggle {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
