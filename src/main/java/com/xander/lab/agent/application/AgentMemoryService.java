package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xander.lab.agent.domain.AgentMemory;
import com.xander.lab.agent.infrastructure.AgentMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 智能体的跨会话长期记忆。
 *
 * <p>召回策略刻意保持简单可解释：先按与本轮输入的词面相关度排序，相关度相同再看命中次数和更新时间。
 * 现阶段条目量级很小（每用户上限 {@link #MAX_MEMORIES} 条），朴素打分足够；
 * 等真实数据量上来再换检索实现，接口不用动。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {
    /** 每个用户的记忆条数上限；超出后写入前淘汰最久未被召回的一条。 */
    static final int MAX_MEMORIES = 200;
    /** 每轮对话注入上下文的记忆条数上限，避免挤占对话历史。 */
    static final int RECALL_LIMIT = 8;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_KEY_LENGTH = 120;
    private static final Set<String> KINDS = Set.of("PROFILE", "PREFERENCE", "FACT", "PROJECT");

    private final AgentMemoryMapper memoryMapper;

    /** 写入或更新一条记忆；同一 memory_key 视为更新，避免同一件事被反复记成多条。 */
    @Transactional
    public AgentMemory remember(Long userId, String memoryKey, String kind, String content,
                               Long sourceConversationId) {
        requireUser(userId);
        String key = normalizeKey(memoryKey);
        String body = content == null ? "" : content.trim();
        if (!StringUtils.hasText(key)) throw new IllegalArgumentException("记忆需要 memoryKey");
        if (!StringUtils.hasText(body)) throw new IllegalArgumentException("记忆需要 content");
        if (body.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("单条记忆不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
        }
        String normalizedKind = normalizeKind(kind);

        AgentMemory existing = findByKey(userId, key);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            memoryMapper.update(null, new LambdaUpdateWrapper<AgentMemory>()
                    .eq(AgentMemory::getId, existing.getId())
                    .eq(AgentMemory::getUserId, userId)
                    .set(AgentMemory::getKind, normalizedKind)
                    .set(AgentMemory::getContent, body)
                    .set(AgentMemory::getUpdatedAt, now));
            existing.setKind(normalizedKind);
            existing.setContent(body);
            existing.setUpdatedAt(now);
            return existing;
        }

        evictWhenFull(userId);
        AgentMemory memory = new AgentMemory();
        memory.setUserId(userId);
        memory.setMemoryKey(key);
        memory.setKind(normalizedKind);
        memory.setContent(body);
        memory.setSourceConversationId(sourceConversationId);
        memory.setHitCount(0);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memoryMapper.insert(memory);
        return memory;
    }

    /** 按 key 删除一条记忆；返回是否真的删掉，让工具能如实回报而不是假装成功。 */
    @Transactional
    public boolean forget(Long userId, String memoryKey) {
        requireUser(userId);
        String key = normalizeKey(memoryKey);
        if (!StringUtils.hasText(key)) throw new IllegalArgumentException("删除记忆需要 memoryKey");
        return memoryMapper.delete(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getMemoryKey, key)) > 0;
    }

    /** 列出全部记忆，按最近更新排序。 */
    @Transactional(readOnly = true)
    public List<AgentMemory> list(Long userId) {
        requireUser(userId);
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .orderByDesc(AgentMemory::getUpdatedAt, AgentMemory::getId)
                .last("LIMIT " + MAX_MEMORIES));
    }

    /**
     * 按查询文本召回最相关的若干条记忆，并记录命中次数。
     *
     * <p>命中计数是排序信号而非业务数据，失败只记日志，不能让召回本身失败。</p>
     */
    public List<AgentMemory> recall(Long userId, String query, int limit) {
        List<AgentMemory> all = list(userId);
        if (all.isEmpty()) return List.of();
        int size = Math.min(Math.max(limit, 1), RECALL_LIMIT);
        List<String> terms = terms(query);
        List<AgentMemory> ranked = new ArrayList<>(all);
        ranked.sort((left, right) -> {
            int byScore = Integer.compare(score(right, terms), score(left, terms));
            if (byScore != 0) return byScore;
            int byHits = Integer.compare(hitCount(right), hitCount(left));
            if (byHits != 0) return byHits;
            return right.getUpdatedAt().compareTo(left.getUpdatedAt());
        });
        List<AgentMemory> recalled = ranked.subList(0, Math.min(size, ranked.size()));
        try {
            memoryMapper.markRecalled(userId, recalled.stream().map(AgentMemory::getId).toList());
        } catch (Exception error) {
            log.warn("agent memory hit counter update failed, userId={}", userId, error);
        }
        return List.copyOf(recalled);
    }

    /** 渲染成注入上下文的紧凑文本；空记忆返回空串由调用方决定是否写入。 */
    public String render(List<AgentMemory> memories) {
        if (memories.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < memories.size(); index++) {
            AgentMemory memory = memories.get(index);
            builder.append("- [").append(memory.getKind()).append(' ').append(memory.getMemoryKey())
                    .append("] ").append(memory.getContent());
            if (index < memories.size() - 1) builder.append('\n');
        }
        return builder.toString();
    }

    private int score(AgentMemory memory, List<String> terms) {
        if (terms.isEmpty()) return 0;
        String haystack = (memory.getMemoryKey() + ' ' + memory.getContent()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) score += term.length();
        }
        // 用户画像和偏好是长期基线，同等相关度下优先注入。
        if ("PROFILE".equals(memory.getKind()) || "PREFERENCE".equals(memory.getKind())) score += 1;
        return score;
    }

    /** 切出用于匹配的词元：英文按空白分词，中文按二元字组，兼顾无分词器时的召回率。 */
    private List<String> terms(String query) {
        if (!StringUtils.hasText(query)) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("[^\\p{IsHan}\\p{IsAlnum}]+")) {
            if (token.isBlank()) continue;
            if (token.codePoints().anyMatch(Character::isIdeographic)) {
                int[] codePoints = token.codePoints().toArray();
                if (codePoints.length == 1) {
                    terms.add(token);
                    continue;
                }
                for (int index = 0; index + 1 < codePoints.length; index++) {
                    terms.add(new String(codePoints, index, 2));
                }
            } else if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    private void evictWhenFull(Long userId) {
        Long total = memoryMapper.selectCount(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId));
        if (total == null || total < MAX_MEMORIES) return;
        List<AgentMemory> stale = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .orderByAsc(AgentMemory::getHitCount)
                .orderByAsc(AgentMemory::getUpdatedAt)
                .last("LIMIT " + (total - MAX_MEMORIES + 1)));
        stale.forEach(memory -> memoryMapper.deleteById(memory.getId()));
    }

    private AgentMemory findByKey(Long userId, String key) {
        return memoryMapper.selectOne(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getUserId, userId)
                .eq(AgentMemory::getMemoryKey, key));
    }

    private int hitCount(AgentMemory memory) {
        return memory.getHitCount() == null ? 0 : memory.getHitCount();
    }

    private String normalizeKey(String memoryKey) {
        if (memoryKey == null) return "";
        String normalized = memoryKey.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        return normalized.length() <= MAX_KEY_LENGTH ? normalized : normalized.substring(0, MAX_KEY_LENGTH);
    }

    private String normalizeKind(String kind) {
        if (!StringUtils.hasText(kind)) return "FACT";
        String normalized = kind.trim().toUpperCase(Locale.ROOT);
        return KINDS.contains(normalized) ? normalized : "FACT";
    }

    private void requireUser(Long userId) {
        if (userId == null) throw new IllegalArgumentException("记忆操作需要用户身份");
    }
}
