package com.jisuodashi.rbac;

public record ScopedStore(long id, String code, String name, int status) {

    public ScopedStore withStatus(int next) {
        return new ScopedStore(id, code, name, next);
    }
}
