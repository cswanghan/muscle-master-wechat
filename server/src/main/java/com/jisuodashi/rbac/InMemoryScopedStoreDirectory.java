package com.jisuodashi.rbac;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Second store is RBAC-only so C catalog tests still see the single V3 flagship. */
@Repository
@Profile("dev")
public class InMemoryScopedStoreDirectory implements ScopedStoreDirectory {

    private final CopyOnWriteArrayList<ScopedStore> stores = new CopyOnWriteArrayList<>(List.of(
            new ScopedStore(RbacDemoIds.STORE, "DEMO01", "肌松大师·演示旗舰店", 1),
            new ScopedStore(RbacDemoIds.STORE_EAST, "DEMO02", "肌松大师·演示二分店", 1)));

    @Override
    public List<ScopedStore> list() {
        return List.copyOf(stores);
    }

    @Override
    public Optional<ScopedStore> find(long id) {
        return stores.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public void updateStatus(long id, int status) {
        stores.replaceAll(s -> s.id() == id ? s.withStatus(status) : s);
    }
}
