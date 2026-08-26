package com.stockmanager.quanttask.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.Map;

/** Compact, pollable task state exposed to the frontend. */
public record QuantTaskView(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long accountId,
        String taskType,
        String status,
        Integer progress,
        String stage,
        String resultType,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Long resultId,
        Map<String, Object> resultSummary,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
