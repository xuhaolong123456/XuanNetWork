package com.networkdisk.file;

public enum MergeFlag {
    INCOMPLETE(0), READY(1);

    private final int value;

    MergeFlag(int value) { this.value = value; }

    @com.fasterxml.jackson.annotation.JsonValue
    public int getValue() { return value; }
}
