package com.networkdisk.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 登录防爆破参数，集中配置并允许按环境覆盖。 */
@Component
@ConfigurationProperties(prefix = "app.login")
public class LoginSecurityProperties {
    private int failCaptchaThreshold = 3;
    private int failLockThreshold = 5;
    private int failWindowTtlSeconds = 900;
    private int ipMaxAttempts = 10;
    private int ipWindowTtlSeconds = 60;
    private int captchaTtlSeconds = 300;

    public int getFailCaptchaThreshold() { return failCaptchaThreshold; }
    public void setFailCaptchaThreshold(int value) { this.failCaptchaThreshold = value; }
    public int getFailLockThreshold() { return failLockThreshold; }
    public void setFailLockThreshold(int value) { this.failLockThreshold = value; }
    public int getFailWindowTtlSeconds() { return failWindowTtlSeconds; }
    public void setFailWindowTtlSeconds(int value) { this.failWindowTtlSeconds = value; }
    public int getIpMaxAttempts() { return ipMaxAttempts; }
    public void setIpMaxAttempts(int value) { this.ipMaxAttempts = value; }
    public int getIpWindowTtlSeconds() { return ipWindowTtlSeconds; }
    public void setIpWindowTtlSeconds(int value) { this.ipWindowTtlSeconds = value; }
    public int getCaptchaTtlSeconds() { return captchaTtlSeconds; }
    public void setCaptchaTtlSeconds(int value) { this.captchaTtlSeconds = value; }
}
