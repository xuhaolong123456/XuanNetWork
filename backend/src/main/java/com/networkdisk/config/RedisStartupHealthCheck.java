package com.networkdisk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.stereotype.Component;

/** 应用启动时必须确认 Redis 可用，避免验证码模块悄然运行在不可靠状态。 */
@Component
public class RedisStartupHealthCheck implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(RedisStartupHealthCheck.class);
    private final StringRedisTemplate redis;

    public RedisStartupHealthCheck(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RuntimeException last = null;
        int[] backoffSeconds = {2, 4, 6};
        for (int attempt = 0; attempt < backoffSeconds.length; attempt++) {
            try {
                try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
                    connection.ping();
                    log.info("Redis 启动检查通过，第 {} 次尝试成功", attempt + 1);
                    return;
                }
            } catch (RuntimeException exception) {
                last = exception;
                log.warn("Redis 启动检查失败，第 {} 次尝试", attempt + 1);
                if (attempt < backoffSeconds.length - 1) {
                    try {
                        Thread.sleep(backoffSeconds[attempt] * 1000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Redis 启动检查被中断", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("Redis 为必需服务但当前不可用，应用停止启动", last);
    }
}
