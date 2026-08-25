package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.xander.lab.agent.domain.AgentMemory;
import com.xander.lab.agent.infrastructure.AgentMemoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceTest {

    private AgentMemoryMapper memoryMapper;
    private AgentMemoryService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMemory.class);
    }

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        service = new AgentMemoryService(memoryMapper);
    }

    @Test
    void rememberNormalizesTheKeyAndDefaultsTheKind() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.selectCount(any())).thenReturn(3L);

        AgentMemory saved = service.remember(42L, "  Writing Style  ", "不认识的类型", "偏好口语化", 7L);

        assertThat(saved.getMemoryKey()).isEqualTo("writing-style");
        assertThat(saved.getKind()).isEqualTo("FACT");
        assertThat(saved.getContent()).isEqualTo("偏好口语化");
        assertThat(saved.getSourceConversationId()).isEqualTo(7L);
        assertThat(saved.getHitCount()).isZero();
        verify(memoryMapper).insert(saved);
    }

    @Test
    void rememberUpdatesInPlaceWhenTheKeyAlreadyExists() {
        AgentMemory existing = memory(11L, "writing-style", "PREFERENCE", "旧内容", 5);
        when(memoryMapper.selectOne(any())).thenReturn(existing);

        AgentMemory saved = service.remember(42L, "writing-style", "PROFILE", "新内容", 9L);

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getKind()).isEqualTo("PROFILE");
        assertThat(saved.getContent()).isEqualTo("新内容");
        verify(memoryMapper).update(eq(null), any());
        verify(memoryMapper, never()).insert(any());
    }

    @Test
    void rememberRejectsMissingKeyContentOrUser() {
        assertThatThrownBy(() -> service.remember(null, "k", "FACT", "c", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("用户身份");
        assertThatThrownBy(() -> service.remember(42L, "  ", "FACT", "c", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memoryKey");
        assertThatThrownBy(() -> service.remember(42L, "k", "FACT", "  ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content");
        assertThatThrownBy(() -> service.remember(42L, "k", "FACT", "字".repeat(2001), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
    }

    @Test
    void writingAtCapacityEvictsTheLeastUsedMemoryFirst() {
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.selectCount(any())).thenReturn((long) AgentMemoryService.MAX_MEMORIES);
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory(3L, "stale", "FACT", "旧的", 0)));

        service.remember(42L, "new-one", "FACT", "新的", null);

        verify(memoryMapper).deleteById(3L);
        verify(memoryMapper).insert(any(AgentMemory.class));
    }

    @Test
    void forgetReportsWhetherAnythingWasActuallyRemoved() {
        when(memoryMapper.delete(any())).thenReturn(1, 0);

        assertThat(service.forget(42L, "Writing Style")).isTrue();
        assertThat(service.forget(42L, "writing-style")).isFalse();
        assertThatThrownBy(() -> service.forget(42L, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memoryKey");
    }

    @Test
    void recallRanksTheMemoriesThatActuallyMatchTheTurnFirst() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "favourite-food", "FACT", "喜欢吃辣", 0),
                memory(2L, "writing-style", "PREFERENCE", "写作偏好口语化，少用术语", 0),
                memory(3L, "job", "PROFILE", "后端工程师", 0)));

        List<AgentMemory> recalled = service.recall(42L, "帮我按我的写作偏好改一下", 2);

        assertThat(recalled).hasSize(2);
        assertThat(recalled.get(0).getMemoryKey()).isEqualTo("writing-style");
        verify(memoryMapper).markRecalled(eq(42L), anyList());
    }

    @Test
    void recallFallsBackToProfileAndPreferenceWhenNothingMatches() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "favourite-food", "FACT", "喜欢吃辣", 0),
                memory(2L, "job", "PROFILE", "后端工程师", 0)));

        List<AgentMemory> recalled = service.recall(42L, "今天天气怎么样", 5);

        assertThat(recalled.get(0).getKind()).isEqualTo("PROFILE");
    }

    @Test
    void recallIsCappedAtTheInjectionLimit() {
        List<AgentMemory> many = new ArrayList<>();
        for (int index = 0; index < AgentMemoryService.RECALL_LIMIT + 5; index++) {
            many.add(memory((long) index, "key-" + index, "FACT", "内容" + index, 0));
        }
        when(memoryMapper.selectList(any())).thenReturn(many);

        assertThat(service.recall(42L, "内容", 999)).hasSize(AgentMemoryService.RECALL_LIMIT);
    }

    @Test
    void aFailingHitCounterDoesNotBreakRecall() {
        // 命中计数只是排序信号：它挂了不能把整轮对话拖下水。
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "job", "PROFILE", "后端工程师", 0)));
        doThrow(new RuntimeException("db down")).when(memoryMapper).markRecalled(any(), anyList());

        assertThat(service.recall(42L, "工作", 3)).hasSize(1);
    }

    @Test
    void recallOnAnEmptyStoreTouchesNothing() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.recall(42L, "任何问题", 3)).isEmpty();
        verify(memoryMapper, never()).markRecalled(any(), anyList());
    }

    @Test
    void recallSkipsUnrelatedFactsInsteadOfPaddingTheContextAndCountingThemAsHits() {
        // 零分记忆一旦被当成"命中"，hitCount 就不再是"常用"的信号，淘汰会先删错东西。
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "favourite-food", "FACT", "喜欢吃辣", 0),
                memory(2L, "car", "FACT", "开电车", 0)));

        assertThat(service.recall(42L, "帮我看看这段代码", 5)).isEmpty();
        verify(memoryMapper, never()).markRecalled(any(), anyList());
    }

    @Test
    void recallOnlyCountsTheMemoriesItActuallyInjected() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory(1L, "favourite-food", "FACT", "喜欢吃辣", 0),
                memory(2L, "writing-style", "PREFERENCE", "写作偏好口语化", 0)));

        service.recall(42L, "按我的写作偏好来", 5);

        verify(memoryMapper).markRecalled(42L, List.of(2L));
    }

    @Test
    void recallToleratesMemoriesWithoutAnUpdatedAt() {
        // 历史数据里出现过空的 updated_at；排序时不能因此抛 NPE 把整轮对话打断。
        AgentMemory undated = memory(1L, "writing-style", "PREFERENCE", "写作偏好口语化", 0);
        undated.setUpdatedAt(null);
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                undated, memory(2L, "job", "PROFILE", "后端工程师", 0)));

        assertThat(service.recall(42L, "随便问一句", 5)).hasSize(2);
    }

    @Test
    void renderLabelsEachMemoryWithItsKindAndKey() {
        String rendered = service.render(List.of(
                memory(1L, "job", "PROFILE", "后端工程师", 0),
                memory(2L, "writing-style", "PREFERENCE", "口语化", 0)));

        assertThat(rendered).isEqualTo("- [PROFILE job] 后端工程师\n- [PREFERENCE writing-style] 口语化");
        assertThat(service.render(List.of())).isEmpty();
    }

    private AgentMemory memory(Long id, String key, String kind, String content, int hitCount) {
        AgentMemory memory = new AgentMemory();
        memory.setId(id);
        memory.setUserId(42L);
        memory.setMemoryKey(key);
        memory.setKind(kind);
        memory.setContent(content);
        memory.setHitCount(hitCount);
        memory.setUpdatedAt(LocalDateTime.now().minusMinutes(id == null ? 0 : id));
        return memory;
    }
}
