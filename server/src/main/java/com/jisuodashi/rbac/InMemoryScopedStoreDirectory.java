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
        StoreScope scope = StoreScopeContext.get();
        List<ScopedStore> all = List.copyOf(stores);
        return scope == null ? all : scope.filter(all, ScopedStore::id);
    }

    @Override
    public Optional<ScopedStore> find(long id) {
        StoreScope scope = StoreScopeContext.get();
        if (scope != null && !scope.contains(id)) {
            return Optional.empty();
        }
        return stores.stream().filter(s -> s.id() == id).findFirst();
    }

    @Override
    public void updateStatus(long id, int status) {
        StoreScope scope = StoreScopeContext.get();
        if (scope != null) {
            scope.assertContains(id);
        }
        stores.replaceAll(s -> s.id() == id ? s.withStatus(status) : s);
    }

    @Override
    public void insert(ScopedStore store) {
        stores.add(store);
    }

    @Override
    public void update(ScopedStore store) {
        stores.replaceAll(s -> s.id() == store.id() ? store : s);
    }

    @Override
    public void softDelete(long id) {
        stores.removeIf(s -> s.id() == id);
    }

    @Override
    public void resetDemo() {
        stores.clear();
        stores.add(new ScopedStore(RbacDemoIds.STORE, "DEMO01", "肌松大师·演示旗舰店", 1));
        stores.add(new ScopedStore(RbacDemoIds.STORE_EAST, "DEMO02", "肌松大师·演示二分店", 1));
    }
}
