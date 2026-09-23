package com.networkdisk.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

@Entity
@Table(name = "sys_user", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_user_nick_name", columnNames = "nick_name")
})
/** 映射 MySQL sys_user 表的用户实体。 */
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库自增主键。 */
    private Long id;

    @Column(nullable = false, length = 128)
    /** 规范化后的邮箱，数据库中有唯一约束。 */
    private String email;

    @Column(name = "nick_name", nullable = false, length = 32)
    /** 用户昵称，数据库中有唯一约束。 */
    private String nickName;

    @Column(name = "password_hash", nullable = false, length = 100)
    /** BCrypt 密文，绝对不能保存或返回明文密码。 */
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    /** 用户创建时间。 */
    private LocalDateTime createdAt;

    /** JPA 反射创建实体时使用，业务代码不直接调用。 */
    protected User() {
    }

    /** Service 完成参数处理和密码哈希后调用此构造方法创建用户。 */
    public User(String email, String nickName, String passwordHash) {
        this.email = email;
        this.nickName = nickName;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
    }

    /** 返回数据库生成的用户 ID。 */
    public Long getId() {
        return id;
    }

    /** 仅供密码校验和单元测试使用，不应放进接口响应。 */
    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickName() {
        return nickName;
    }
}
