package com.jisuodashi.rbac;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** Live /a /f store SQL. {@link StoreScopeSqlInterceptor} appends {@code id IN (...)}. */
@Mapper
public interface ScopedStoreMapper {

    @Select(ScopedStoreQueries.LIST)
    List<ScopedStore> list();

    @Select("SELECT id, code, name, status FROM store WHERE id=#{id} AND deleted_at IS NULL")
    ScopedStore find(@Param("id") long id);

    @Update("UPDATE store SET status=#{status}, updated_at=#{updatedAt} WHERE id=#{id} AND deleted_at IS NULL")
    int updateStatus(@Param("id") long id, @Param("status") int status, @Param("updatedAt") Instant updatedAt);
}
