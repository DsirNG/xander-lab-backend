package com.xander.lab.agent.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * A tool an orchestration agent may invoke. Tools are always executed with an
 * explicit user id; they must not rely on request thread context.
 */
public interface AgentTool {

    String name();

    /** For the model: what the tool does and when to use it. */
    String description();

    /** JSON Schema (as a JsonNode) of the tool arguments. */
    JsonNode parametersSchema();

    /**
     * 这个工具自带的提示词准则，只在它本轮真在工具表里时才进提示词。
     *
     * <p>{@link #description()} 说的是"这个工具是什么"，会和 schema 一起渲染；
     * 这里说的是"本轮该怎么用它、什么时候别用它"，属于行动准则的一部分。
     * 两者分开是因为前者要短（每个工具都渲染），后者可以长。</p>
     *
     * <p>默认没有准则。工具只有在"用错了会直接毁掉这一轮答案"时才需要覆写它——
     * 比如交付卡该不该开、答题卡能不能写进正文。</p>
     */
    default Optional<AgentPromptSection> promptSection() {
        return Optional.empty();
    }

    /**
     * Executes the tool.
     *
     * @param userId    owning user
     * @param args      validated JSON arguments
     * @param onProgress stage/progress hints to stream to the conversation
     * @return structured JSON result visible to the model
     */
    JsonNode execute(Long userId, JsonNode args, ProgressListener onProgress);

    /**
     * 带会话上下文的执行入口，编排循环统一走这里。
     *
     * <p>默认转发到只带 userId 的重载，所以既有工具无需改动；
     * 需要写回会话状态的工具（任务计划等）覆写本方法。</p>
     */
    default JsonNode execute(AgentToolContext context, JsonNode args, ProgressListener onProgress) {
        return execute(context.userId(), args, onProgress);
    }

    /**
     * Looks up the durable effect of an interrupted invocation without issuing
     * a second mutation. Tools that cannot prove an outcome leave it unknown.
     */
    default Optional<JsonNode> reconcile(AgentToolContext context, JsonNode args) {
        return Optional.empty();
    }

    /**
     * Returns why this exact call needs user approval, or empty when pre-approved.
     *
     * <p>实现必须是**纯函数**：只看 {@code args}，不改任何状态。编排层会在真正执行之前先调它
     * 判断这一批能不能并发（命中审批就得退回串行逐个执行），执行管道里还会再调一次。
     * 有副作用的实现（比如顺手记一次调用次数）会被调用两次，计数直接翻倍。</p>
     */
    default Optional<String> approvalReason(JsonNode args) {
        return Optional.empty();
    }

    /**
     * 这个工具是否只读取状态、不产生任何副作用（不改库、不写文件、不发外部写请求）。
     *
     * <p>它是**并发执行**的准入条件，不是文档性标注：编排循环只在一步里的每个调用都
     * 返回 {@code true} 时才把它们放到线程池上同时跑，否则一律串行。默认 {@code false}
     * 是刻意的——新增工具忘了声明只会退化成串行（慢一点），而反过来误标成只读会让两个
     * 写操作真正并发起来（写坏数据）。拿不准就别覆写。</p>
     *
     * <p>这个标记只说明"并发安全"，不说明"要审批"：只读工具照样可以覆写
     * {@link #approvalReason(JsonNode)} 要求审批。</p>
     */
    default boolean readOnly() {
        return false;
    }

    /** Receives human-readable progress hints during a long-running tool call. */
    interface ProgressListener {
        void onProgress(String message);

        /**
         * 把这次调用的持久化身份交给监听器，让它之后推的每条进度/增量都带上同一个
         * {@code invocationId}。
         *
         * <p>为什么不能构造时就给：身份是执行管道在开单那一刻生成的，而监听器必须**先于**
         * 开单交给管道（管道要在开单前用它做取消检查）。所以只能"先交监听器、后补身份"。
         * 补的时机严格早于任何执行，因此监听器一旦开始推送，身份必定已经就位。</p>
         *
         * <p>默认空实现：不对外暴露调用身份的实现（比如子智能体的 QuietProgress）无需关心。
         * 那类实现不转发也不丢信息——子智能体的进度本来就归它自己那次调用所有。</p>
         */
        default void bindInvocationId(String invocationId) {
        }

        /** Streams tool-owned draft/output text without pretending it is the agent's final answer. */
        default void onToolDelta(String delta) {
        }

        /**
         * 回报工具内部自行发起的模型调用用量。
         *
         * <p>子智能体这类工具会自己调模型，如果不回报，这部分 token 就绕过了本轮计费。
         * 默认空实现让普通工具无需关心。</p>
         */
        default void onModelUsage(com.xander.lab.common.ModelUsage usage, String model) {
        }

        /** Lets a long-running tool cooperatively stop between remote stages. */
        default boolean isCancellationRequested() {
            return false;
        }

        /** Throws at cooperative cancellation checkpoints. */
        default void checkCancellation() {
            if (isCancellationRequested()) {
                throw new java.util.concurrent.CancellationException("用户已停止当前智能体任务");
            }
        }
    }
}
