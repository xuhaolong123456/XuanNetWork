package com.networkdisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
/**
 * Spring Boot 应用启动类。
 *
 * 启动时会扫描 com.networkdisk 包下的 Controller、Service、Repository
 * 和配置类，并启动内置 Web 服务器。
 */
public class NetworkDiskApplication {

    /** Java 应用入口，运行 mvn spring-boot:run 时从这里开始。 */
    public static void main(String[] args) {
        SpringApplication.run(NetworkDiskApplication.class, args);
    }
}
