package com.jisuodashi.admin;

import java.util.List;

public interface AdminOrderStore {

    List<AdminOrderRow> list();

    default void resetDemo() {
    }
}
