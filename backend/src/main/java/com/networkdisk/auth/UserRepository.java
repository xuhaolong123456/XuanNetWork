package com.networkdisk.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 用户数据访问层，Spring Data JPA 会自动生成实现类并操作 MySQL。 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 判断规范化邮箱是否已经存在。 */
    boolean existsByEmail(String email);

    boolean existsByNickName(String nickName);

    /** nick_name 字段目前用于保存全局唯一用户名。 */
    Optional<User> findByNickName(String nickName);

    // 根目录创建时锁住用户行，避免并发请求为同名目录选出相同编号。
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
    /** 判断昵称是否已经存在。 */
