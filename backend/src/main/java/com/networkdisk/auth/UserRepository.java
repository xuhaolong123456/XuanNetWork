package com.networkdisk.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** 用户数据访问层，Spring Data JPA 会自动生成实现类并操作 MySQL。 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 判断规范化邮箱是否已经存在。 */
    boolean existsByEmail(String email);

    boolean existsByNickName(String nickName);

    /** nick_name 当前承载全局唯一用户名。 */
    Optional<User> findByNickName(String nickName);
}
    /** 判断昵称是否已经存在。 */
