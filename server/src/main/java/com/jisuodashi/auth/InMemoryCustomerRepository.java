package com.jisuodashi.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** H2 cannot run V1; customers live in memory on `dev`. */
@Repository
@Profile("dev")
public class InMemoryCustomerRepository implements CustomerRepository {

    private final ConcurrentMap<Long, Customer> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<Customer> findByOpenid(String openid) {
        if (openid == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(c -> !c.isDeleted() && openid.equals(c.getWxOpenid()))
                .findFirst()
                .map(Customer::copy);
    }

    @Override
    public Optional<Customer> findByPhoneHash(String phoneHash) {
        if (phoneHash == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(c -> !c.isDeleted() && phoneHash.equals(c.getPhoneHash()))
                .findFirst()
                .map(Customer::copy);
    }

    @Override
    public Optional<Customer> findById(long id) {
        Customer c = byId.get(id);
        if (c == null || c.isDeleted()) {
            return Optional.empty();
        }
        return Optional.of(c.copy());
    }

    @Override
    public List<Customer> lockByIds(Collection<Long> ids) {
        List<Customer> locked = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            Customer c = byId.get(id);
            if (c != null && !c.isDeleted()) {
                locked.add(c.copy());
            }
        }
        return locked;
    }

    @Override
    public Customer insert(Customer customer) {
        byId.put(customer.getId(), customer.copy());
        return customer.copy();
    }

    @Override
    public void update(Customer customer) {
        byId.put(customer.getId(), customer.copy());
    }

    @Override
    public void clear() {
        byId.clear();
    }
}
