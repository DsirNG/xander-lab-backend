package com.xander.lab.agent.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentEffect;
import com.xander.lab.agent.domain.AgentRun;
import com.xander.lab.agent.domain.AgentToolInvocation;
import com.xander.lab.agent.infrastructure.AgentEffectMapper;
import com.xander.lab.agent.infrastructure.AgentToolInvocationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 逻辑副作用身份的铸造与采纳。
 *
 * <p>这个类只回答一个问题：**这次写调用说的还是不是之前那件事**。答"是"就把它接到既有的
 * {@link AgentEffect} 上（复用 {@code effectId}，于是下游幂等键也复用）；答"不是"就铸一个新的。</p>
 *
 * <p><b>判据是"意图周期 + 工具名 + 归一化指纹 + 更早的步"，不是参数比对。</b>
 * 参数比对有两个反例挡不住：模型把同一件事换个说法（同义改写）会被判成新动作；而用户真的要发
 * 两条内容相同的提醒时，第二次会被判成"重试"而吞掉。所以：</p>
 *
 * <ul>
 *   <li>{@code intent_epoch}——同一件事的重试必然落在同一周期（{@code resume()} 复用，
 *       用户新说话则开新周期）。**跨周期一律不采纳**。</li>
 *   <li>{@code args_fingerprint}——内容明显不同就是两个动作。</li>
 *   <li>{@code created_step_no < 当前步}——同一批次里的两次相同调用是**两个有意的动作**，
 *       不该互相采纳。这条是最容易被漏掉的：少了它，"用户要发两条提醒"会变成一条。</li>
 *   <li>{@code status = OPEN}——已完成的动作不该被"重试"复用，否则会吞掉用户真的要做的第二次。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEffectService {

    private final AgentEffectMapper effectMapper;

    /**
     * 只用来回答一个问题：某个副作用**到底发出去了没有**。
     *
     * <p>{@code effect_state} 挂在 invocation 上，不在 {@link AgentEffect} 里——因为"走到哪一步"
     * 是按物理调用计的，而"是不是同一件事"是按意图周期计的。两个问题的答案存在两张表，
     * 所以判断一次漂移值不值得告警时必须查这张表。</p>
     */
    private final AgentToolInvocationMapper invocationMapper;

    /**
     * 解析这次写调用的逻辑副作用身份。
     *
     * @param run   本次调用所属的执行记录，必须已落库（要它的 {@code id} 与 {@code intent_epoch}）
     * @param stepNo 本次调用所在的模型步序号
     * @return 复用的或新铸的 {@code effectId}；**返回 {@code null} 表示这次调用不该有副作用身份**
     *         （只读工具不调用本方法；迁移前的 Run 因为没有周期标识而无法界定"同一件事"的范围）
     */
    @Transactional
    public String resolveEffect(Long userId, Long conversationId, AgentRun run, int stepNo,
                                String toolName, JsonNode args) {
        if (run == null || run.getId() == null || !StringUtils.hasText(run.getIntentEpoch())) {
            // 迁移之前创建的 Run 没有周期标识。凭猜测编一个会把"这次重试属于哪次诉求"写错，
            // 而那是采纳的判据——宁可这次调用退回"每次调用一个新键"（B6 之前的行为），
            // 也不要把两件不同的事合并成一件。
            log.debug("agent effect skipped because the run has no intent epoch runId={} tool={}",
                    run == null ? null : run.getId(), toolName);
            return null;
        }
        String fingerprint = AgentArgsFingerprint.of(toolName, args);
        AgentEffect adoptable = findAdoptable(run.getIntentEpoch(), toolName, fingerprint, stepNo);
        if (adoptable != null) {
            log.debug("agent effect adopted effectId={} tool={} runId={} stepNo={}",
                    adoptable.getEffectId(), toolName, run.getId(), stepNo);
            return adoptable.getEffectId();
        }
        // 走到这里说明"没有可采纳的同指纹 Effect"。在铸新之前先看一眼：这是不是一次
        // **改了参数的重试**——那种情况下前一次可能已经写出去了，而新键拦不住第二次写。
        warnOnArgumentDrift(run.getIntentEpoch(), toolName, fingerprint, stepNo, args);
        return mint(userId, conversationId, run, stepNo, toolName, fingerprint, args);
    }

    /**
     * 找一个可以被采纳的既有 Effect。
     *
     * <p><b>这里与计划有一处偏离，且是刻意的。</b>计划写的是"存在**恰好一个**…，其余情况一律铸造新
     * {@code effectId}"。实现取的是**最早的那一个**，而不是"多于一个就不采纳"。理由是"铸新"会在
     * 这种情况下**级联**：同一批次里的两次相同调用会留下两个同指纹的 OPEN Effect（这是正确的，
     * 用户确实要两条），此后每一次重试都会因为"不止一个"而再铸一个新的，于是 OPEN 的候选越积越多，
     * 每次重试都拿到一个新的下游幂等键——**重试越多，重复写的风险越大**，而那正是 D1 要证明不发生的事。
     * 取最早则稳定：所有重试都落到同一个 {@code effectId} 上，不级联。</p>
     *
     * <p>代价是歧义情形下会选中最早的那个候选，而"模型这一次指的是哪一个"确实无从判断。这个歧义
     * 只在"同一批次发了多条完全相同的调用、且它们都还没完成"时才出现，此时告警比猜测更有用。</p>
     */
    private AgentEffect findAdoptable(String intentEpoch, String toolName, String fingerprint, int stepNo) {
        List<AgentEffect> candidates = effectMapper.selectList(new LambdaQueryWrapper<AgentEffect>()
                .eq(AgentEffect::getIntentEpoch, intentEpoch)
                .eq(AgentEffect::getToolName, toolName)
                .eq(AgentEffect::getStatus, AgentEffect.STATUS_OPEN)
                .eq(AgentEffect::getArgsFingerprint, fingerprint)
                .lt(AgentEffect::getCreatedStepNo, stepNo)
                .orderByAsc(AgentEffect::getId));
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() > 1) {
            log.warn("agent effect adoption is ambiguous, taking the earliest intentEpoch={} tool={}"
                            + " candidates={} chosenEffectId={}",
                    intentEpoch, toolName, candidates.size(), candidates.get(0).getEffectId());
        }
        return candidates.get(0);
    }

    /**
     * 检测"重试改了参数"这条已知残余风险，并留下可查的痕迹。
     *
     * <p><b>为什么值得单独告警。</b>采纳靠指纹，指纹靠参数。模型重试时若把同一件事换了个说法
     * （09:00 改成 09:30、措辞变了、多带一个字段），指纹就对不上，于是铸新 {@code effectId}、
     * 下游幂等键跟着换——**前一次可能已经写出去的请求再也拦不住第二次**。这就是 §7 风险表里
     * "Effect 采纳的残余漂移"那条。B6 只落地了机制本身（保守方向：宁可铸新也不误采纳），
     * 告警是 B6 明确留下的**真实未完成项**，在这里补上。</p>
     *
     * <p><b>只在"确实发出去过"时才告警。</b>没越过副作用边界的（{@code NONE} / {@code PREPARED}）
     * 远端什么都没发生，此时改参数只是"第一次真正要做的动作换了个样子"，不构成重复写；
     * 每条都告警会把真正危险的那几条淹掉。判据正是 B7 落地的 {@code effect_state}——
     * 这也是这件事必须等 B7 才能做的原因。</p>
     *
     * <p><b>为什么用 {@code log.warn} 而不是 {@code agent_event}。</b>事件表服务于**用户可见的
     * 会话流**；这是给运维排查用的信号，混进会话流既污染用户视图，又需要前端配合渲染。
     * 前缀 {@code EFFECT_ARG_DRIFT} 是稳定的检索锚点，日志里带上前一次的 {@code args_json}
     * 原文（本就不存凭据），供人工比对差异。</p>
     *
     * <p>代价是铸新这条路径上多一次查询（通常是零行的那次）；采纳成功的路径不受影响。
     * 铸新每个逻辑动作只发生一次，不在重试循环里。</p>
     */
    private void warnOnArgumentDrift(String intentEpoch, String toolName, String fingerprint,
                                     int stepNo, JsonNode args) {
        List<AgentEffect> drifted = effectMapper.selectList(new LambdaQueryWrapper<AgentEffect>()
                .eq(AgentEffect::getIntentEpoch, intentEpoch)
                .eq(AgentEffect::getToolName, toolName)
                .eq(AgentEffect::getStatus, AgentEffect.STATUS_OPEN)
                .lt(AgentEffect::getCreatedStepNo, stepNo)
                .ne(AgentEffect::getArgsFingerprint, fingerprint)
                .orderByAsc(AgentEffect::getId));
        if (drifted == null || drifted.isEmpty()) return;

        // 一次问清这批候选里哪些真的越过过副作用边界，避免逐个候选查一次。
        List<String> driftedIds = drifted.stream().map(AgentEffect::getEffectId).toList();
        List<AgentToolInvocation> crossed = invocationMapper.selectList(
                new LambdaQueryWrapper<AgentToolInvocation>()
                        .in(AgentToolInvocation::getEffectId, driftedIds)
                        .in(AgentToolInvocation::getEffectState,
                                AgentToolInvocation.EFFECT_STATE_DISPATCHED,
                                AgentToolInvocation.EFFECT_STATE_CONFIRMED));
        if (crossed == null || crossed.isEmpty()) return;
        Set<String> crossedIds = new HashSet<>();
        for (AgentToolInvocation invocation : crossed) {
            crossedIds.add(invocation.getEffectId());
        }
        for (AgentEffect effect : drifted) {
            if (!crossedIds.contains(effect.getEffectId())) continue;
            log.warn("EFFECT_ARG_DRIFT a retry in the same intent epoch changed its arguments and cannot"
                            + " adopt the earlier effect, so it will write with a NEW idempotency key and a"
                            + " duplicate side effect is possible intentEpoch={} tool={} stepNo={}"
                            + " previousEffectId={} previousArgs={} newArgs={}",
                    intentEpoch, toolName, stepNo, effect.getEffectId(), effect.getArgsJson(), args);
        }
    }

    private String mint(Long userId, Long conversationId, AgentRun run, int stepNo, String toolName,
                        String fingerprint, JsonNode args) {
        AgentEffect effect = new AgentEffect();
        effect.setEffectId(UUID.randomUUID().toString());
        effect.setUserId(userId);
        effect.setConversationId(conversationId);
        effect.setRunId(run.getId());
        effect.setIntentEpoch(run.getIntentEpoch());
        effect.setToolName(toolName);
        effect.setArgsFingerprint(fingerprint);
        effect.setArgsJson(args == null ? null : args.toString());
        effect.setCreatedStepNo(stepNo);
        effect.setStatus(AgentEffect.STATUS_OPEN);
        if (effectMapper.insert(effect) != 1) {
            throw new IllegalStateException("智能体副作用身份创建失败");
        }
        log.debug("agent effect minted effectId={} tool={} runId={} stepNo={}",
                effect.getEffectId(), toolName, run.getId(), stepNo);
        return effect.getEffectId();
    }

    /**
     * 这次副作用已经拿到成功确认，不再可被采纳。
     *
     * <p>不结算它的话，一个**已经成功**的动作会继续处于 OPEN，于是同周期里用户真的要做的第二次
     * 相同动作会被当成"重试"而接到它的键上，下游去重直接把第二次吞掉。</p>
     *
     * <p>条件更新限定在 {@code OPEN} 上，所以它幂等，也不可能把已结算的副作用拉回可采纳。</p>
     */
    @Transactional
    public void settleEffect(String effectId) {
        transition(effectId, AgentEffect.STATUS_SETTLED);
    }

    /**
     * 放弃这个副作用（例如审批被拒），后续不再采纳。
     *
     * <p>审批被拒意味着用户明确说了"不要做这件事"。如果还留着 OPEN，模型下一次重发同样的调用
     * 会接上它——那等于绕过了刚被拒绝的审批。</p>
     */
    @Transactional
    public void abandonEffect(String effectId) {
        transition(effectId, AgentEffect.STATUS_ABANDONED);
    }

    /** 按业务身份读一条，供对账与排障使用。 */
    @Transactional(readOnly = true)
    public Optional<AgentEffect> findByEffectId(String effectId) {
        if (!StringUtils.hasText(effectId)) return Optional.empty();
        return Optional.ofNullable(effectMapper.selectOne(new LambdaQueryWrapper<AgentEffect>()
                .eq(AgentEffect::getEffectId, effectId)));
    }

    private void transition(String effectId, String targetStatus) {
        if (!StringUtils.hasText(effectId)) return;
        int changed = effectMapper.update(null, new LambdaUpdateWrapper<AgentEffect>()
                .eq(AgentEffect::getEffectId, effectId)
                .eq(AgentEffect::getStatus, AgentEffect.STATUS_OPEN)
                .set(AgentEffect::getStatus, targetStatus)
                .set(AgentEffect::getSettledAt, LocalDateTime.now()));
        if (changed != 1) {
            // 已经不是 OPEN 了：另一个调用先一步结算了它，或者它根本不存在。
            // 两种都不该覆盖，也不该当成错误——副作用的状态只前进不后退。
            log.debug("agent effect {} was not open, transition to {} skipped", effectId, targetStatus);
        }
    }
}
