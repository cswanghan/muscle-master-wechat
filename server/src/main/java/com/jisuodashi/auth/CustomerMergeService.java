package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Sole write path for customer identity (D19). Callers must not UPDATE phone_hash / wx_openid
 * themselves. Merge survivor is always the phone row (B).
 */
@Service
public class CustomerMergeService {

    private final CustomerRepository customers;
    private final RelatedRecordsRepository related;
    private final SnowflakeIdGenerator ids;
    private final Clock clock;
    private final Object lock = new Object();

    public CustomerMergeService(
            CustomerRepository customers,
            RelatedRecordsRepository related,
            SnowflakeIdGenerator ids,
            Clock clock) {
        this.customers = customers;
        this.related = related;
        this.ids = ids;
        this.clock = clock;
    }

    public Customer merge(String openid, String phoneHash, byte[] phoneCipher) {
        return merge(openid, null, phoneHash, phoneCipher);
    }

    public Customer merge(String openid, String unionid, String phoneHash, byte[] phoneCipher) {
        synchronized (lock) {
            Customer a = openid == null ? null : customers.findByOpenid(openid).orElse(null);
            Customer b = phoneHash == null ? null : customers.findByPhoneHash(phoneHash).orElse(null);
            Set<Long> lockIds = new LinkedHashSet<>();
            if (a != null) {
                lockIds.add(a.getId());
            }
            if (b != null) {
                lockIds.add(b.getId());
            }
            customers.lockByIds(lockIds);
            Instant now = Instant.now(clock);

            if (a == null && b == null) {
                Customer created = new Customer();
                created.setId(ids.nextId());
                created.setWxOpenid(openid);
                created.setWxUnionid(unionid);
                created.setPhoneHash(phoneHash);
                created.setPhoneCipher(phoneCipher);
                created.setCreatedAt(now);
                created.setUpdatedAt(now);
                return customers.insert(created);
            }

            if (a != null && b == null) {
                if (phoneHash != null) {
                    a.setPhoneHash(phoneHash);
                    a.setPhoneCipher(phoneCipher);
                    a.setUpdatedAt(now);
                    if (unionid != null) {
                        a.setWxUnionid(unionid);
                    }
                    customers.update(a);
                }
                return a;
            }

            if (a == null) {
                if (b.getWxOpenid() == null) {
                    if (openid != null) {
                        b.setWxOpenid(openid);
                        if (unionid != null) {
                            b.setWxUnionid(unionid);
                        }
                        b.setUpdatedAt(now);
                        customers.update(b);
                    }
                    return b;
                }
                if (openid == null || openid.equals(b.getWxOpenid())) {
                    return b;
                }
                related.insertCollisionTask(phoneHash);
                throw new ApiException(ErrorCodes.CUSTOMER_COLLISION, "客户身份冲突");
            }

            if (a.getId() == b.getId()) {
                return a;
            }

            // A = openid-only C login; B = phone-only walk-in. Survive as B.
            if (b.getWxOpenid() == null) {
                String originalOpenid = a.getWxOpenid();
                a.setWxOpenid(null);
                a.setUpdatedAt(now);
                customers.update(a);

                b.setWxOpenid(originalOpenid);
                if (unionid != null) {
                    b.setWxUnionid(unionid);
                } else if (a.getWxUnionid() != null) {
                    b.setWxUnionid(a.getWxUnionid());
                }
                b.setUpdatedAt(now);
                customers.update(b);

                related.reassignBookings(a.getId(), b.getId());
                related.reassignSessions(a.getId(), b.getId());
                related.reassignServiceRecords(a.getId(), b.getId());

                a.setDeletedAt(now);
                a.setUpdatedAt(now);
                customers.update(a);
                related.insertMergeAudit(a.getId(), b.getId());
                return b;
            }

            related.insertCollisionTask(phoneHash);
            throw new ApiException(ErrorCodes.CUSTOMER_COLLISION, "客户身份冲突");
        }
    }
}
