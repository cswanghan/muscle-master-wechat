package com.jisuodashi.rbac;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Mirrors V2 role_permission so in-memory staff (Flyway off) has the same codes. */
public final class PermissionCatalog {

    public static final List<String> ALL_CODES = List.of(
            "catalog:store",
            "catalog:therapist",
            "catalog:project",
            "catalog:write",
            "schedule:write",
            "schedule:approve",
            "order:list",
            "order:view",
            "order:refund",
            "frontdesk:order:*",
            "refund:create",
            "refund:after_start",
            "refund:approve",
            "inventory:force_release",
            "staff:self");

    private PermissionCatalog() {
    }

    public static Set<String> forRoles(Collection<String> roles) {
        Set<String> perms = new LinkedHashSet<>();
        if (roles == null) {
            return perms;
        }
        for (String role : roles) {
            perms.addAll(forRole(role));
        }
        return perms;
    }

    public static List<String> forRole(String role) {
        if (role == null) {
            return List.of();
        }
        return switch (role) {
            case "SUPER_ADMIN" -> ALL_CODES;
            case "FINANCE" -> List.of(
                    "order:list", "order:view", "order:refund",
                    "refund:create", "refund:after_start", "refund:approve");
            case "OPS" -> List.of(
                    "catalog:store", "catalog:therapist", "catalog:project", "catalog:write",
                    "schedule:write", "schedule:approve",
                    "order:list", "order:view", "inventory:force_release");
            case "REGION_MANAGER" -> List.of(
                    "catalog:store", "catalog:therapist", "catalog:project", "catalog:write",
                    "schedule:write", "schedule:approve",
                    "order:list", "order:view", "refund:approve");
            case "STORE_MANAGER" -> List.of(
                    "catalog:therapist", "catalog:project",
                    "schedule:write", "schedule:approve",
                    "order:list", "order:view", "order:refund",
                    "frontdesk:order:*", "refund:create", "refund:approve");
            case "FRONTDESK" -> List.of(
                    "order:list", "order:view", "frontdesk:order:*", "refund:create");
            case "THERAPIST" -> List.of("staff:self");
            default -> List.of();
        };
    }

    public static boolean allows(Collection<String> held, String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        if (held == null) {
            return false;
        }
        if (held.contains(required)) {
            return true;
        }
        for (String code : held) {
            if (code.endsWith(":*")) {
                String prefix = code.substring(0, code.length() - 1);
                if (required.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
