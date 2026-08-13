package com.jisuodashi.rbac;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends a driving-table predicate: {@code store.id IN} or {@code store_id IN},
 * plus {@code therapist_id = :me} for SELF on therapist tables.
 * EXISTS wrappers are rewritten on the inner SELECT, not spliced onto the outer.
 */
public final class SqlScopeRewriter {

    private static final Pattern TAIL = Pattern.compile(
            "(?i)\\s+(ORDER\\s+BY|GROUP\\s+BY|HAVING|LIMIT|FOR\\s+UPDATE)\\b");
    private static final Pattern WORD_WHERE = Pattern.compile("(?i)\\bWHERE\\b");
    private static final Pattern EXISTS_WRAP = Pattern.compile("(?is)^SELECT\\s+EXISTS\\s*\\((.*)\\)\\s*$");
    private static final Pattern DRIVING = Pattern.compile(
            "(?i)\\b(FROM|UPDATE)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*))?");
    private static final Set<String> RESERVED = Set.of(
            "WHERE", "SET", "ON", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
            "ORDER", "GROUP", "LIMIT", "HAVING", "AND", "OR", "UNION", "EXISTS");
    private static final Set<String> STORE_ID_TABLES = Set.of(
            "booking_order", "therapist_slot", "bed_slot", "room", "bed",
            "schedule_template", "schedule_exception", "human_task", "store_project",
            "service_record");
    private static final Set<String> THERAPIST_ID_TABLES = Set.of(
            "booking_order", "therapist_slot", "schedule_template", "schedule_exception",
            "service_record");

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
        Matcher exists = EXISTS_WRAP.matcher(trimmed);
        if (exists.matches()) {
            String inner = rewrite(exists.group(1).strip(), scope);
            return "SELECT EXISTS(" + inner + ")";
        }
        List<String> unionParts = splitUnion(trimmed);
        if (unionParts.size() > 1) {
            List<String> rewritten = new ArrayList<>();
            for (String part : unionParts) {
                rewritten.add(rewrite(part, scope));
            }
            return String.join(" UNION ", rewritten);
        }
        Driving driving = driving(trimmed);
        if (driving == null) {
            return sql;
        }
        String predicate = predicate(driving, scope);
        if (predicate == null) {
            return sql;
        }
        return insertPredicate(trimmed, predicate);
    }

    static String predicate(Driving driving, StoreScope scope) {
        String table = driving.table.toLowerCase(Locale.ROOT);
        String column;
        if ("store".equals(table)) {
            column = qualify(driving.alias, "id");
        } else if (STORE_ID_TABLES.contains(table)) {
            column = qualify(driving.alias, "store_id");
        } else {
            return null;
        }
        if (scope.type() == DataScopeType.SELF && scope.therapistId() == null) {
            return "1=0";
        }
        String storePred = scope.storeIds().isEmpty()
                ? "1=0"
                : column + " IN (" + scope.sqlInList() + ")";
        if (scope.type() == DataScopeType.SELF && THERAPIST_ID_TABLES.contains(table)) {
            return storePred + " AND " + qualify(driving.alias, "therapist_id") + " = " + scope.therapistId();
        }
        return storePred;
    }

    static String insertPredicate(String sql, String predicate) {
        int tail = indexOfDepth0(sql, TAIL);
        int cut = tail < 0 ? sql.length() : tail;
        String head = sql.substring(0, cut);
        String rest = sql.substring(cut);
        if (indexOfDepth0(head, WORD_WHERE) >= 0) {
            return head + " AND " + predicate + rest;
        }
        return head + " WHERE " + predicate + rest;
    }

    static Driving driving(String sql) {
        Matcher m = DRIVING.matcher(sql);
        while (m.find()) {
            if (parenDepth(sql, m.start()) != 0) {
                continue;
            }
            String alias = m.group(3);
            if (alias != null && RESERVED.contains(alias.toUpperCase(Locale.ROOT))) {
                alias = null;
            }
            return new Driving(m.group(2), alias);
        }
        return null;
    }

    private static String qualify(String alias, String column) {
        return alias == null ? column : alias + "." + column;
    }

    private static int indexOfDepth0(String sql, Pattern pattern) {
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            if (parenDepth(sql, m.start()) == 0) {
                return m.start();
            }
        }
        return -1;
    }

    static int parenDepth(String sql, int pos) {
        int depth = 0;
        for (int i = 0; i < pos; i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    static List<String> splitUnion(String sql) {
        List<String> parts = new ArrayList<>();
        String upper = sql.toUpperCase(Locale.ROOT);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && upper.startsWith("UNION", i)
                    && (i == 0 || !isIdent(upper.charAt(i - 1)))
                    && (i + 5 >= upper.length() || !isIdent(upper.charAt(i + 5)))) {
                parts.add(sql.substring(start, i).strip());
                i += 5;
                while (i < upper.length() && Character.isWhitespace(upper.charAt(i))) {
                    i++;
                }
                if (upper.startsWith("ALL", i) && (i + 3 >= upper.length() || !isIdent(upper.charAt(i + 3)))) {
                    i += 3;
                    while (i < upper.length() && Character.isWhitespace(upper.charAt(i))) {
                        i++;
                    }
                }
                start = i;
                i--;
            }
        }
        if (parts.isEmpty()) {
            return List.of(sql);
        }
        parts.add(sql.substring(start).strip());
        return parts;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    record Driving(String table, String alias) {
    }
}
