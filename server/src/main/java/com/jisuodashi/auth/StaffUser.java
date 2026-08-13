package com.jisuodashi.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class StaffUser {

    private long id;
    private String username;
    private String name;
    private byte[] phoneCipher;
    private String phoneHash;
    private String wxOpenid;
    private int status = 1;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private List<String> roleCodes = new ArrayList<>();
    private String scopeType = "SELF";
    private List<Long> storeIds = new ArrayList<>();

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getPhoneCipher() {
        return phoneCipher;
    }

    public void setPhoneCipher(byte[] phoneCipher) {
        this.phoneCipher = phoneCipher;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public String getWxOpenid() {
        return wxOpenid;
    }

    public void setWxOpenid(String wxOpenid) {
        this.wxOpenid = wxOpenid;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<String> getRoleCodes() {
        return roleCodes;
    }

    public void setRoleCodes(List<String> roleCodes) {
        this.roleCodes = roleCodes;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public List<Long> getStoreIds() {
        return storeIds;
    }

    public void setStoreIds(List<Long> storeIds) {
        this.storeIds = storeIds;
    }

    public TokenType tokenType() {
        TokenType best = TokenType.T;
        for (String role : roleCodes) {
            TokenType mapped = switch (role) {
                case "SUPER_ADMIN", "FINANCE", "OPS", "REGION_MANAGER" -> TokenType.A;
                case "STORE_MANAGER", "FRONTDESK" -> TokenType.F;
                default -> TokenType.T;
            };
            if (rank(mapped) > rank(best)) {
                best = mapped;
            }
        }
        return best;
    }

    private static int rank(TokenType typ) {
        return switch (typ) {
            case A -> 3;
            case F -> 2;
            case T -> 1;
            case C -> 0;
        };
    }
}
