package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.xander.lab.agent.domain.AgentInputTurn;
import com.xander.lab.agent.domain.AgentMessage;
import com.xander.lab.agent.domain.AgentTokenEstimator;
import com.xander.lab.agent.infrastructure.AgentConversationProperties;
import com.xander.lab.agent.infrastructure.AgentModelClient;
import com.xander.lab.common.ModelCallResult;
import com.xander.lab.common.ModelUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 上下文压缩：把对话记录的<b>前半段</b>换成一份结论式检查点，尾部原样留着。
 *
 * <p>为什么必须有它：只靠"保留最近 N 条"这种窗口，一超出窗口的消息就是直接消失——
 * 用户几十条消息之前定下的目标、给过的纠正、已经查到的关键事实，全都无声无息地没了，
 * 智能体于是开始重复提问、重复调工具，甚至推翻自己早先答应过的事。窗口丢掉的不是"旧消息"，
 * 是任务本身的前提。压缩换一种丢法：先让模型把这一段读成一份检查点，再用检查点替换这一段。
 * 事实和承诺留下来，中间过程的字节没了。</p>
 *
 * <p>两个触发条件，缺一个都会让压缩形同虚设：</p>
 * <ul>
 *   <li><b>token 压力到阈值。</b>少量消息也可能个个巨大（长草稿、大工具结果），
 *       这一条防的是真正的窗口溢出。</li>
 *   <li><b>条数即将超出窗口。</b>只看 token 压力的话，
 *       {@code contextMessageLimit} 会先把旧消息静默丢掉，压力永远到不了阈值，
 *       压缩一次也不会触发——那正是"有窗口没压缩"这个 bug 的形状。
 *       所以窗口要丢东西的那一刻就是该压缩的那一刻。</li>
 * </ul>
 *
 * <p>三条不能违反的规则：</p>
 * <ol>
 *   <li><b>尾部原样保留。</b>最近这一段是模型正在做的事，摘要化它等于把手上的活变模糊。
 *       只有超出保留预算的那部分才允许被换掉。</li>
 *   <li><b>不切开工具调用与它的结果。</b>切点落在两者之间，模型就会读到一条没有来源的结果。</li>
 *   <li><b>摘要必须真的更小。</b>模型完全可能把一段对话"摘要"成更长的一篇复述；
 *       那样压缩不但没减压，还白烧一次调用。换不下来就不换。</li>
 * </ol>
 *
 * <p>失败时一律退回原窗口而不是让这一轮失败：压缩是省上下文的优化，
 * 它自己出问题不该把用户的这轮对话搭进去。退回后的行为与没有压缩时完全一致。</p>
 */
@Slf4j
@Service
public class AgentCompactionService {
    /** 检查点消息的 kind，落库后即成为对话记录的地板：它之前的消息不再进入上下文。 */
    public static final String CHECKPOINT_KIND = "compaction";
    /** 摘要正文的长度上限，防止模型把"摘要"写成另一篇长文。 */
    static final int MAX_SUMMARY_CHARS = 6000;
    /** 头部至少要有这么多条才值得压缩，否则省下的还不够一次摘要调用的成本。 */
    static final int MIN_COMPACTABLE_MESSAGES = 4;

    private final AgentModelClient modelClient;
    private final AgentConversationProperties properties;
    private final AgentEventProjector eventProjector;

    @Autowired
    public AgentCompactionService(AgentModelClient modelClient, AgentConversationProperties properties,
                                  AgentEventProjector eventProjector) {
        this.modelClient = modelClient;
        this.properties = properties;
        this.eventProjector = eventProjector;
    }

    /**
     * 压缩的结果：这一步该用的对话记录，外加压缩自己烧掉的 token。
     *
     * <p>用量必须交回去：摘要是一次真实的模型调用，不报账就等于让压缩绕过计费。
     * 摘要成功但被"必须更小"挡掉时同样已经花掉了 token，那一次也在这里。</p>
     */
    public record Compacted(List<AgentMessage> messages, ModelUsage usage, String model) {
    }

    /**
     * 需要时压缩，并返回这一步该用的对话记录。
     *
     * @param conversationId   会话 ID
     * @param userId           归属用户
     * @param runVersion       当前代次，检查点要落在这一代上
     * @param messages         按 id 升序的对话记录（本方法自己裁到最近一个检查点之后）
     * @param toolDescriptions 这一步的工具面；摘要调用刻意沿用它，让请求成为主请求的真前缀
     * @param cancelled        取消信号
     * @return 压缩后的记录与本次压缩的用量；未触发或压缩失败时记录原样返回
     */
    public Compacted compactIfNeeded(Long conversationId, Long userId, long runVersion,
                                     List<AgentMessage> messages, String toolDescriptions,
                                     BooleanSupplier cancelled) {
        List<AgentMessage> current = afterLatestCheckpoint(messages);
        if (!properties.isCompactionEnabled() || current.isEmpty()) return unchanged(current);

        int pressure = estimate(current);
        int threshold = thresholdTokens();
        boolean overflowing = pressure >= threshold;
        boolean windowWouldDrop = current.size() > windowMessageLimit();
        if (!overflowing && !windowWouldDrop) return unchanged(current);

        int cut = compactionCutIndex(current);
        if (cut < MIN_COMPACTABLE_MESSAGES) {
            // 压力全在尾部（比如一条巨大的工具结果），压缩解决不了：
            // 交给装配层原有的单条截断，别白烧一次调用。
            log.info("agent compaction skipped conversation={} messages={} pressure={} threshold={} "
                    + "cut={} reason=tail-dominates", conversationId, current.size(), pressure, threshold, cut);
            return unchanged(current);
        }

        List<AgentMessage> head = current.subList(0, cut);
        int headTokens = estimate(head);
        ModelCallResult call;
        try {
            call = summarize(head, toolDescriptions, cancelled);
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (AgentRunSupersededException superseded) {
            throw superseded;
        } catch (RuntimeException failure) {
            log.warn("agent compaction summarization failed conversation={} reason={} message={}",
                    conversationId, failure.getClass().getSimpleName(), failure.getMessage());
            return unchanged(current);
        }
        ModelUsage spent = call.usage() == null ? ModelUsage.NONE : call.usage();
        String checkpoint = frame(summaryOf(call.payload()));
        int checkpointTokens = AgentTokenEstimator.estimateTurn(checkpoint);
        if (checkpointTokens >= headTokens) {
            // 换上去反而更大：这次压缩没有意义，保持原样，下一步再按同样的条件重试。
            log.warn("agent compaction rejected conversation={} checkpointTokens={} headTokens={}",
                    conversationId, checkpointTokens, headTokens);
            return new Compacted(current, spent, call.model());
        }

        // 检查点必须落库：它是对话记录的新地板，只活在内存里的话，
        // 下一步重新读库就把刚换掉的那一段整段读回来，压缩等于没发生。
        eventProjector.saveActiveMessage(conversationId, userId, runVersion,
                AgentInputTurn.USER, CHECKPOINT_KIND, null, checkpoint);
        log.info("agent compaction committed conversation={} shadowed={} headTokens={} checkpointTokens={} "
                        + "pressure={} threshold={}",
                conversationId, head.size(), headTokens, checkpointTokens, pressure, threshold);

        List<AgentMessage> compacted = new ArrayList<>();
        compacted.add(checkpointMessage(conversationId, runVersion, checkpoint));
        compacted.addAll(current.subList(cut, current.size()));
        return new Compacted(List.copyOf(compacted), spent, call.model());
    }

    /**
     * 最近一个检查点及其之后的消息，即这一轮真正该送进模型的那一段。
     *
     * <p>静态是有意的：装配层每一步都要按同一条地板裁剪，
     * 但它不该为此依赖一个会发起模型调用的服务。</p>
     */
    public static List<AgentMessage> afterLatestCheckpoint(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (CHECKPOINT_KIND.equals(messages.get(index).getKind())) {
                return List.copyOf(messages.subList(index, messages.size()));
            }
        }
        return List.copyOf(messages);
    }

    /** 压力阈值：留出两成余量，压到窗口边缘再动手就已经晚了。 */
    int thresholdTokens() {
        return Math.max(1, (int) (contextWindowTokens()
                * clampRatio(properties.getCompactionThresholdRatio(), 0.8D)));
    }

    /** 尾部原样保留的 token 预算。 */
    int retainTokens() {
        return Math.max(1, (int) (contextWindowTokens()
                * clampRatio(properties.getCompactionRetainRatio(), 0.16D)));
    }

    /** 装配层的条数窗口，压缩用它判断"窗口是不是马上要丢东西了"。 */
    private int windowMessageLimit() {
        return Math.max(5, properties.getContextMessageLimit());
    }

    /**
     * 尾部最多保留的条数。
     *
     * <p>纯粹为了让条数触发有回差：如果压完还紧贴着窗口上限，
     * 下一轮再追加两条就又触发一次，用户每答一句都要多付一次摘要调用。
     * 压到半个窗口，接下来还能长半个窗口才需要再压一次。</p>
     */
    private int maxTailMessages() {
        return Math.max(2, windowMessageLimit() / 2);
    }

    private int contextWindowTokens() {
        return Math.max(4096, properties.getContextWindowTokens());
    }

    private double clampRatio(double ratio, double fallback) {
        return ratio > 0D && ratio <= 1D ? ratio : fallback;
    }

    private Compacted unchanged(List<AgentMessage> messages) {
        return new Compacted(messages, ModelUsage.NONE, null);
    }

    /**
     * 切点：尾部同时受 token 预算和条数上限约束，两者取更紧的那个，再退到不切开工具对的位置。
     *
     * <p>返回的下标既是"要被换掉的条数"，也是 head 的右开边界。
     * 返回 0 表示尾部本身就吃掉了全部预算，没有可压缩的头部。</p>
     */
    int compactionCutIndex(List<AgentMessage> messages) {
        int keepFrom = Math.max(0, messages.size() - maxTailMessages());
        int retain = retainTokens();
        int accumulated = 0;
        for (int index = messages.size() - 1; index >= keepFrom; index--) {
            accumulated += AgentTokenEstimator.estimateTurn(text(messages.get(index)));
            if (accumulated >= retain) {
                keepFrom = index;
                break;
            }
        }
        while (keepFrom > 0 && orphansAToolResult(messages, keepFrom)) keepFrom--;
        return keepFrom;
    }

    /**
     * 这个切点是否会留下一条没有来源的工具结果。
     *
     * <p>保留段以 {@code tool_result} 打头，说明它的 {@code tool_call} 落在被换掉的头部里，
     * 模型会读到一条不知道自己是怎么来的结果。往前退一格把调用一起留下即可。</p>
     */
    private boolean orphansAToolResult(List<AgentMessage> messages, int keepFrom) {
        return "tool_result".equals(messages.get(keepFrom).getKind());
    }

    private int estimate(List<AgentMessage> messages) {
        int total = 0;
        for (AgentMessage message : messages) total += AgentTokenEstimator.estimateTurn(text(message));
        return total;
    }

    private String text(AgentMessage message) {
        return message.getContent() == null ? "" : message.getContent();
    }

    /**
     * 让模型把这一段读成一份检查点。
     *
     * <p>工具面沿用主请求的那一份，摘要指令作为最后一轮 user 追加在对话记录之后——
     * 于是这次调用是主请求的一个真前缀：instructions 逐字节相同，input 只在末尾多一轮，
     * 上游的前缀缓存能直接命中，真正新增的输入只有那条指令。</p>
     */
    private ModelCallResult summarize(List<AgentMessage> head, String toolDescriptions,
                                      BooleanSupplier cancelled) {
        List<AgentInputTurn> turns = new ArrayList<>();
        for (AgentMessage message : head) {
            turns.add(AgentInputTurn.ASSISTANT.equals(message.getRole())
                    ? AgentInputTurn.assistant(text(message))
                    : AgentInputTurn.user(text(message)));
        }
        turns.add(AgentInputTurn.user(COMPACTION_INSTRUCTION));
        return modelClient.decideNextStepStream(
                AgentInputTurn.merged(turns), toolDescriptions, ignored -> { }, cancelled);
    }

    /** 结论从决策信封的 reply 里取；带工具面的调用走 json_schema，reply 一定是纯文本。 */
    private String summaryOf(JsonNode decision) {
        if (decision == null || !decision.path("done").asBoolean(false)) {
            throw new IllegalStateException("压缩调用没有收口，模型返回了 action");
        }
        String reply = decision.path("reply").asText("").trim();
        if (!StringUtils.hasText(reply)) throw new IllegalStateException("压缩调用没有产出摘要正文");
        return reply.length() <= MAX_SUMMARY_CHARS ? reply : reply.substring(0, MAX_SUMMARY_CHARS);
    }

    /** 内存里的检查点副本，形状与刚落库的那条一致，这一步立刻就能当上下文用。 */
    private AgentMessage checkpointMessage(Long conversationId, long runVersion, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRunVersion(runVersion);
        message.setRole(AgentInputTurn.USER);
        message.setKind(CHECKPOINT_KIND);
        message.setContent(content);
        message.setAttachments(List.of());
        return message;
    }

    private String frame(String summary) {
        return CHECKPOINT_PREAMBLE + "\n\n" + SUMMARY_OPEN_TAG + "\n" + summary + "\n" + SUMMARY_CLOSE_TAG;
    }

    static final String SUMMARY_OPEN_TAG = "<compacted-summary>";
    static final String SUMMARY_CLOSE_TAG = "</compacted-summary>";

    /** 检查点的开场：它是既成事实的背景，不是一件需要向用户交代的事。 */
    static final String CHECKPOINT_PREAMBLE =
            "以下是这段对话较早部分的自动检查点，用于腾出上下文。把它当作已经成立的背景直接使用，"
                    + "不要向用户复述它，也不要提起上下文被压缩过。";

    /**
     * 摘要指令，作为最后一轮 user 追加在被压缩的那一段之后。
     *
     * <p>刻意不另起一套 system prompt：主请求的 instructions 与工具表保持在前，
     * 这次调用才是主请求的真前缀，缓存才不会失效。也因此要在这里显式免掉工具准则——
     * 前面那一大段说的是"该动手就调工具"，而这一轮唯一该做的事是写检查点。</p>
     */
    static final String COMPACTION_INSTRUCTION = """
            现在只做一件事：把上面这段对话压成一份检查点，让另一个模型能凭它无损地接着干活。
            忽略上面所有关于调用工具的准则——这一轮不要调用任何工具，直接返回 done=true，把检查点写在 reply 里。

            reply 用下面的结构，每一节都要写，空的写"（无）"，不要删节，条目用短句不要写成段落：

            ## 用户的诉求
            - 用户最初的目标，以及后来变化过的目标；措辞本身重要时原话引用

            ## 已经查到的事实
            - 从工具结果里得到的真实数据、结论与出处，保留标识符、数字、路径与原文片段

            ## 已经做过的事
            - 已经真正执行完的写入与交付，各自的结果

            ## 遇到的问题与处理
            - 失败的调用、原因、后来怎么绕过去的

            ## 还没做完的
            - 用户明确要求但还没收口的部分

            ## 当前进度
            - 这一刻正在做的那件事

            ## 需要记住的约束
            - 用户的取舍、偏好、纠正意见，以及继续干活必须知道的前提

            规则：保留文件路径、命令、报错原文、标识符、数字与公式片段，不要改写它们。
            用户的纠正和明确指示要如实保留。不要提起这次压缩请求本身。
            上面若已经有一份 %s 检查点，那是更早的一次压缩：不要照抄，
            把仍然成立的事实并进来、已经过期的删掉，合成一份统一的检查点。
            """.formatted(SUMMARY_OPEN_TAG);
}
