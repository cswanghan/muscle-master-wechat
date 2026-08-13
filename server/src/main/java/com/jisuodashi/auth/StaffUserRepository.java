package com.jisuodashi.auth;

import java.util.Optional;

public interface StaffUserRepository {

    Optional<StaffUser> findByWxOpenid(String openid);

    Optional<StaffUser> findByUsername(String username);

    Optional<StaffUser> findById(long id);

    StaffUser insert(StaffUser staff);

    void update(StaffUser staff);
}
