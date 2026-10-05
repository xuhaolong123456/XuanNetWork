package com.networkdisk.file;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.demo.seed-admin", havingValue = "true")
public class DevAdminSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevAdminSeed.class);
    static final String DEFAULT_FILE_NAME = "管理员示例文件.txt";
    private static final String ADMIN_EMAIL = "admin@local.invalid";

    private final UserRepository users;
    private final UserFileRepository files;
    private final BCryptPasswordEncoder passwords;
    private final String adminPassword;

    public DevAdminSeed(UserRepository users, UserFileRepository files, BCryptPasswordEncoder passwords,
                        @Value("${app.demo.admin-password:}") String adminPassword) {
        this.users = users;
        this.files = files;
        this.passwords = passwords;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminPassword.isBlank()) {
            throw new IllegalStateException("初始化管理员账号前必须设置 APP_DEMO_ADMIN_PASSWORD");
        }

        User admin = users.findByNickName("admin").orElseGet(() ->
                users.save(new User(ADMIN_EMAIL, "admin", passwords.encode(adminPassword))));
        if (!passwords.matches(adminPassword, admin.getPasswordHash())) {
            throw new IllegalStateException("现有管理员账号密码与配置不一致，未修改任何数据");
        }

        if (!files.existsByOwner_IdAndParentIsNullAndName(admin.getId(), DEFAULT_FILE_NAME)) {
            files.save(new UserFile(admin, null, DEFAULT_FILE_NAME, FileNodeType.FILE, 0, "text/plain"));
        }
        log.info("开发环境管理员账号和私有示例文件已就绪，用户编号：{}", admin.getId());
    }
}
