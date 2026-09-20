package com.xander.lab.automation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.automation.api.BlogContentPlanAiGenerateRequest;
import com.xander.lab.automation.infrastructure.BlogAgentTopicSeriesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates model generation outside the persistence transaction. */
@Service
@RequiredArgsConstructor
public class BlogPlanAiGenerateService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BlogAgentTopicSeriesClient topicSeriesClient;
    private final BlogPlanAiBatchService batchService;

    public List<Long> generate(Long userId, BlogContentPlanAiGenerateRequest request) {
        requireUser(userId);
        String seed = request.getTopic().strip();
        int days = request.getDays() == null ? 7 : request.getDays();
        ZoneId zone = BlogPlanScheduleCalculator.resolveZone(request.getTimezone());
        LocalTime time = request.getTime() == null || request.getTime().isBlank()
                ? LocalTime.of(9, 0) : parseTime(request.getTime());

        List<String> excludeTitles = new ArrayList<>();
        excludeTitles.add(seed);
        jdbcTemplate.query("SELECT topic, topics_json FROM blog_content_plan "
                        + "WHERE user_id = ? AND deleted_at IS NULL",
                (rs, rowNum) -> new ExistingTopics(rs.getString("topic"), rs.getString("topics_json")),
                userId).forEach(existing -> {
            if (existing.topic() != null) {
                excludeTitles.add(existing.topic());
            }
            if (existing.topicsJson() != null && !existing.topicsJson().isBlank()) {
                try {
                    excludeTitles.addAll(objectMapper.readValue(existing.topicsJson(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
                } catch (JsonProcessingException exception) {
                    throw new IllegalArgumentException("计划配置数据损坏", exception);
                }
            }
        });

        List<String> topics = topicSeriesClient.generate(userId, seed, request.getAudience(),
                request.getTone(), days, excludeTitles);
        if (topics.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI 主题生成失败，请稍后重试");
        }
        return batchService.create(userId, request, zone, time, topics);
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.strip(), TIME_FORMAT);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("触发时间格式应为 HH:mm");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
    }

    private record ExistingTopics(String topic, String topicsJson) {
    }
}
