package com.networkdisk.file;

public class FileBusinessException extends RuntimeException {
    private final String code;
    private final int status;

    public FileBusinessException(String code, String message) {
        this(code, message, 400);
    }

    public FileBusinessException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public int getStatus() { return status; }
}
