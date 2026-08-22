package com.xander.lab.recitation.domain;

/** 背诵录音任务的持久化状态常量。 */
public final class RecitationAttemptStatus {
    public static final String UPLOADING = "UPLOADING";
    public static final String PENDING = "PENDING";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String PROCESSING = "PROCESSING";
    public static final String POLLING = "POLLING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private RecitationAttemptStatus() {
    }
}
