package com.jisuodashi.rbac;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScopeRewriterTest {

    private static final long STORE = RbacDemoIds.STORE;

    @Test
    void allSkipsRewrite() {
        StoreScope scope = new StoreScope(DataScopeType.ALL, List.of(), 1L, null);
        String sql = "SELECT * FROM booking_order WHERE status='BOOKED'";
        assertThat(SqlScopeRewriter.rewrite(sql, scope)).isEqualTo(sql);
    }

    @Test
    void storeScopeAppendsInList() {
        StoreScope scope = new StoreScope(DataScopeType.STORE, List.of(STORE), 2L, null);
        String sql = "SELECT * FROM booking_order WHERE status='BOOKED' ORDER BY id";
        assertThat(SqlScopeRewriter.rewrite(sql, scope))
                .isEqualTo("SELECT * FROM booking_order WHERE status='BOOKED' AND store_id IN ("
                        + STORE + ") ORDER BY id");
    }

    @Test
    void storeTableUsesIdColumn() {
        StoreScope scope = new StoreScope(DataScopeType.STORE, List.of(STORE), 2L, null);
        String sql = "SELECT id, code, name FROM store WHERE deleted_at IS NULL";
        assertThat(SqlScopeRewriter.rewrite(sql, scope))
                .contains("id IN (" + STORE + ")")
                .doesNotContain("store_id IN");
    }

    @Test
    void selfAddsTherapistPredicate() {
        StoreScope scope = new StoreScope(DataScopeType.SELF, List.of(STORE), 3L, 401L);
        String sql = "SELECT * FROM booking_order WHERE therapist_id IS NOT NULL";
        assertThat(SqlScopeRewriter.rewrite(sql, scope))
                .contains("store_id IN (" + STORE + ")")
                .contains("therapist_id = 401");
    }

    @Test
    void emptyStoreScopeIsUnsatisfiable() {
        StoreScope scope = new StoreScope(DataScopeType.STORE, List.of(), 2L, null);
        String sql = "SELECT * FROM booking_order";
        assertThat(SqlScopeRewriter.rewrite(sql, scope)).isEqualTo("SELECT * FROM booking_order WHERE 1=0");
    }

    @Test
    void insertIsNotRewritten() {
        StoreScope scope = new StoreScope(DataScopeType.STORE, List.of(STORE), 2L, null);
        String sql = "INSERT INTO booking_order (store_id) VALUES (1)";
        assertThat(SqlScopeRewriter.rewrite(sql, scope)).isEqualTo(sql);
    }
}
