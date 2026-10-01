package com.xander.lab.agent.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xander.lab.agent.domain.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** Persistence gateway for durable agent turn records. */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 扫描一批"持有者进程已经消失、没人再续租"的 RUNNING 执行记录。
     *
     * <p>租约时间写在 {@code agent_conversation.updated_at} 上（见
     * {@code AgentRunLifecycle#startLeaseRenewal}），而**不是** {@code agent_run.updated_at}：
     * 后者的 ON UPDATE 只在状态迁移和开头那一次 {@code recordSkillOffer} 时触发，一个跑得久
     * 但完全健康的 Run 在它看来同样"很久没动"。判定必须以会话那一列为准，所以这里用 join
     * 把两件事写进同一条 SQL——只按 {@code agent_run} 过滤会把健康的长任务也当成候选，
     * 候选一多就会把真正超时的那些挤出 LIMIT 窗口，让它们永远收不到。</p>
     *
     * <p>驱动侧走 {@code idx_agent_run_status_updated (status, updated_at)}：RUNNING 的行天然很少，
     * 再按主键 join 会话并取租约最新鲜的那批之前的部分。</p>
     */
    @Select("""
            SELECT r.* FROM agent_run r
            JOIN agent_conversation c ON c.id = r.conversation_id
            WHERE r.status IN ('CREATED', 'RUNNING')
              AND c.status = 'running'
              AND c.updated_at <= #{cutoff}
            ORDER BY c.updated_at ASC
            LIMIT #{limit}
            """)
    List<AgentRun> selectStaleRunningRuns(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
