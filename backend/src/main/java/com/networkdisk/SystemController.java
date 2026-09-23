package com.networkdisk;

import com.networkdisk.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
/** 提供服务状态检查接口。 */
public class SystemController {

    /**
     * 根路径接口，用于确认后端已经启动。
     * 返回值会被 Spring 自动序列化为 JSON。
     */
    @GetMapping("/")
    public Result<Map<String, String>> index() {
        return Result.success(Map.of(
                "service", "network-disk-backend",
                "status", "running"
        ));
    }
}
