package com.jisuodashi.rbac;

import java.util.List;
import java.util.Optional;

public interface ScopedStoreDirectory {

    List<ScopedStore> list();

    Optional<ScopedStore> find(long id);

    void updateStatus(long id, int status);

    void insert(ScopedStore store);

    void update(ScopedStore store);

    void softDelete(long id);

    default void resetDemo() {
    }
}
