package com.jisuodashi.auth;

import java.util.List;
import java.util.Optional;

public interface StaffUserRepository {

    Optional<StaffUser> findByWxOpenid(String openid);

    Optional<StaffUser> findByUsername(String username);

    Optional<StaffUser> findById(long id);

    StaffUser insert(StaffUser staff);

    void update(StaffUser staff);

    /** 员工花名册，含已离职（status=0）——离职后历史仍要查得到人。 */
    List<StaffUser> listAll();
}
