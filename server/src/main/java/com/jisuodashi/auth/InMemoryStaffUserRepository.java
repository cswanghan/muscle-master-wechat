package com.jisuodashi.auth;

import com.jisuodashi.catalog.DemoFixtures;

import com.jisuodashi.rbac.PermissionCatalog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@Profile("dev")
public class InMemoryStaffUserRepository implements StaffUserRepository {

    private final ConcurrentMap<Long, StaffUser> byId = new ConcurrentHashMap<>();

    public InMemoryStaffUserRepository() {
        seed();
    }

    private void seed() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        insertSeed(DemoStaffIds.ADMIN, "demo.admin", "演示超管", List.of("SUPER_ADMIN"), "ALL", List.of());
        insertSeed(DemoStaffIds.MANAGER, "demo.manager", "演示店长",
                List.of("STORE_MANAGER"), "STORE", List.of(DemoStaffIds.STORE));
        insertSeed(DemoStaffIds.FRONT, "demo.front", "演示前台",
                List.of("FRONTDESK"), "STORE", List.of(DemoStaffIds.STORE));
        for (DemoFixtures.TherapistSeed s : DemoFixtures.therapists()) {
            insertSeed(s.staffUserId(), s.username(), s.name(),
                    List.of("THERAPIST"), "SELF", List.of(s.storeId()));
        }
        byId.values().forEach(s -> {
            s.setCreatedAt(t);
            s.setUpdatedAt(t);
        });
    }

    private void insertSeed(
            long id, String username, String name, List<String> roles, String scope, List<Long> stores) {
        StaffUser s = new StaffUser();
        s.setId(id);
        s.setUsername(username);
        s.setName(name);
        s.setStatus(1);
        s.setRoleCodes(List.copyOf(roles));
        s.setPermissionCodes(new ArrayList<>(PermissionCatalog.forRoles(roles)));
        s.setScopeType(scope);
        s.setStoreIds(List.copyOf(stores));
        byId.put(id, s);
    }

    @Override
    public Optional<StaffUser> findByWxOpenid(String openid) {
        if (openid == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(s -> s.getDeletedAt() == null && openid.equals(s.getWxOpenid()))
                .findFirst()
                .map(this::copyOf);
    }

    @Override
    public Optional<StaffUser> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(s -> s.getDeletedAt() == null && username.equals(s.getUsername()))
                .findFirst()
                .map(this::copyOf);
    }

    @Override
    public Optional<StaffUser> findById(long id) {
        StaffUser s = byId.get(id);
        if (s == null || s.getDeletedAt() != null) {
            return Optional.empty();
        }
        return Optional.of(copyOf(s));
    }

    @Override
    public StaffUser insert(StaffUser staff) {
        if (staff.getPermissionCodes() == null || staff.getPermissionCodes().isEmpty()) {
            staff.setPermissionCodes(new ArrayList<>(PermissionCatalog.forRoles(staff.getRoleCodes())));
        }
        byId.put(staff.getId(), copyOf(staff));
        return copyOf(staff);
    }

    @Override
    public void update(StaffUser staff) {
        byId.put(staff.getId(), copyOf(staff));
    }

    private StaffUser copyOf(StaffUser s) {
        StaffUser c = new StaffUser();
        c.setId(s.getId());
        c.setUsername(s.getUsername());
        c.setName(s.getName());
        c.setPhoneCipher(s.getPhoneCipher() == null ? null : s.getPhoneCipher().clone());
        c.setPhoneHash(s.getPhoneHash());
        c.setWxOpenid(s.getWxOpenid());
        c.setStatus(s.getStatus());
        c.setCreatedAt(s.getCreatedAt());
        c.setUpdatedAt(s.getUpdatedAt());
        c.setDeletedAt(s.getDeletedAt());
        c.setRoleCodes(List.copyOf(s.getRoleCodes()));
        c.setPermissionCodes(List.copyOf(s.getPermissionCodes()));
        c.setScopeType(s.getScopeType());
        c.setStoreIds(List.copyOf(s.getStoreIds()));
        return c;
    }
}
