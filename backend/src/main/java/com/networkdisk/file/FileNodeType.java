package com.networkdisk.file;

public enum FileNodeType {
    DIRECTORY,
    FILE;

    static FileNodeType fromStoredValue(String value) {
        // Canal 读取 MySQL ENUM 的行事件时可能得到序号，索引统一保存枚举名称。
        return switch (value) {
            case "1", "DIRECTORY" -> DIRECTORY;
            case "2", "FILE" -> FILE;
            default -> throw new IllegalArgumentException("未知文件节点类型: " + value);
        };
    }
}
