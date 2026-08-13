package com.jisuodashi.auth;

import java.time.Instant;

public class Customer {

    private long id;
    private String wxOpenid;
    private String wxUnionid;
    private byte[] phoneCipher;
    private String phoneHash;
    private String nickname;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getWxOpenid() {
        return wxOpenid;
    }

    public void setWxOpenid(String wxOpenid) {
        this.wxOpenid = wxOpenid;
    }

    public String getWxUnionid() {
        return wxUnionid;
    }

    public void setWxUnionid(String wxUnionid) {
        this.wxUnionid = wxUnionid;
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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
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

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean hasPhone() {
        return phoneHash != null && !phoneHash.isBlank();
    }

    public Customer copy() {
        Customer c = new Customer();
        c.id = id;
        c.wxOpenid = wxOpenid;
        c.wxUnionid = wxUnionid;
        c.phoneCipher = phoneCipher == null ? null : phoneCipher.clone();
        c.phoneHash = phoneHash;
        c.nickname = nickname;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.deletedAt = deletedAt;
        return c;
    }
}
