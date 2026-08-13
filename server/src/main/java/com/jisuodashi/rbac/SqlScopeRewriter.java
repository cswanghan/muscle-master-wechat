package com.jisuodashi.rbac;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends {@code store_id IN (...)} (or {@code id IN} for the store table).
 * ALL is a no-op. STORE/SELF with an empty store list becomes {@code 1=0}.
 */
public final class SqlScopeRewriter {

    private static final Pattern TAIL = Pattern.compile(
            "(?i)\\s+(ORDER\\s+BY|GROUP\\s+BY|HAVING|LIMIT|FOR\\s+UPDATE)\\b");
    private static final Pattern FROM_STORE = Pattern.compile(
            "(?i)\\b(FROM|UPDATE|JOIN)\\s+store\\b");
    private static final Pattern WORD_STORE_ID = Pattern.compile("(?i)\\bstore_id\\b");
    private static final Pattern WORD_THERAPIST_ID = Pattern.compile("(?i)\\btherapist_id\\b");
    private static final Pattern WORD_WHERE = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Set<String> STORE_TABLES = Set.of(
            "booking_order", "therapist_slot", "bed_slot", "room", "bed",
            "schedule_template", "schedule_exception", "human_task", "store_project",
            "service_record");
    private static final Pattern STORE_TABLE = Pattern.compile(
            "(?i)\\b(FROM|UPDATE|JOIN)\\s+(" + String.join("|", STORE_TABLES) + ")\\b");

    private SqlScopeRewriter() {
    }

    public static String rewrite(String sql, StoreScope scope) {
        if (sql == null || scope == null || scope.all()) {
            return sql;
        }
        String trimmed = sql.strip();
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("INSERT")) {
            return sql;
        }
        String predicate = predicate(trimmed, scope);
        if (predicate == null) {
            return sql;
        }
        return insertPredicate(trimmed, predicate);
    }

    static String predicate(String sql, StoreScope scope) {
        boolean fromStore = FROM_STORE.matcher(sql).find();
        boolean hasStoreId = WORD_STORE_ID.matcher(sql).find();
        boolean storeTable = STORE_TABLE.matcher(sql).find();
        if (!fromStore && !hasStoreId && !storeTable) {
            return null;
        }
        String column = fromStore && !hasStoreId ? "id" : "store_id";
        String storePred;
        if (scope.storeIds().isEmpty()) {
            storePred = "1=0";
        } else {
            storePred = column + " IN (" + scope.sqlInList() + ")";
        }
        if (scope.type() == DataScopeType.SELF
                && scope.therapistId() != null
                && WORD_THERAPIST_ID.matcher(sql).find()) {
            return storePred + " AND therapist_id = " + scope.therapistId();
        }
        return storePred;
    }

    static String insertPredicate(String sql, String predicate) {
        Matcher tail = TAIL.matcher(sql);
        int cut = tail.find() ? tail.start() : sql.length();
        String head = sql.substring(0, cut);
        String rest = sql.substring(cut);
        if (WORD_WHERE.matcher(head).find()) {
            return head + " AND " + predicate + rest;
        }
        return head + " WHERE " + predicate + rest;
    }
}
