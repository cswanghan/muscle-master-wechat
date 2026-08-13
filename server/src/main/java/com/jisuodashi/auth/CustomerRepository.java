package com.jisuodashi.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {

    Optional<Customer> findByOpenid(String openid);

    Optional<Customer> findByPhoneHash(String phoneHash);

    Optional<Customer> findById(long id);

    /** SELECT … FOR UPDATE on hit rows (no-op lock in memory). */
    List<Customer> lockByIds(Collection<Long> ids);

    Customer insert(Customer customer);

    void update(Customer customer);

    default void clear() {
    }
}
