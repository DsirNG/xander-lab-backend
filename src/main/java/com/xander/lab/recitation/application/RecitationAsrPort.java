package com.xander.lab.recitation.application;

import java.util.List;

/** 背诵模块调用外部录音转写服务的本地端口。 */
public interface RecitationAsrPort {

    /** 提交一个公网可访问的录音地址。 */
    SubmittedTask submit(String audioUrl);

    /** 查询已提交到指定供应商的任务。 */
    PollResult poll(long providerId, String taskId);

    /** 已提交远端任务的稳定标识。 */
    record SubmittedTask(long providerId, String model, String taskId) {
    }

    /** 字或词级时间戳。 */
    record WordTimestamp(String text, int beginTime, int endTime) {
    }

    /** 远端任务查询结果。 */
    record PollResult(State state, String transcript, List<WordTimestamp> words, String errorMessage) {
        public enum State { PROCESSING, SUCCEEDED, FAILED }
    }
}
