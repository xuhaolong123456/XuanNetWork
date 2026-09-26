package com.networkdisk.file;

public class FileBusinessException extends RuntimeException {
    private final String code;

    public FileBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
