package com.xander.lab.agent.spi;

/**
 * 远端工具在一次调用期间回传给编排层的通道。
 *
 * <p>为什么要有这个类型，而不是让 {@link RemoteTool#invoke} 直接收两个函数式参数：
 * 工具执行需要的能力是"报告进度"和"在协作点检查取消"，它们是<b>同一件事的两面</b>
 * ——都是这次调用对外的可见性。塞成两个裸参数（{@code Consumer<String>} +
 * {@code Runnable}）之后，将来要加"上报本轮子调用用量"就得再挂一个参数，而每加一次
 * 都要改所有实现；收成一个具名类型，加方法时改的是一处接口。</p>
 *
 * <p><b>只收调用方真正用到的两个能力。</b>agent 的 {@code AgentTool.ProgressListener}
 * 还有 {@code onToolDelta} / {@code onModelUsage} / {@code bindInvocationId} /
 * {@code isCancellationRequested}，远端工具一个都不用：它不产出交付草稿、不自己调模型、
 * 调用身份由编排层持有。端口宽一分，实现方就多一分要维护的空方法，也少一分"这个能力
 * 到底谁负责"的清晰。</p>
 *
 * <p>刻意<b>没有</b> {@code default} 方法。端口"变宽"不会触发任何一道防线——编译过、
 * 指标不动、门禁不报，加一个 {@code default} 就能在不通知任何实现的情况下扩大契约面。
 * 由 {@code RemoteToolCatalogTest} 反射钉住。</p>
 */
public interface RemoteToolCall {

    /**
     * 报告一条人类可读的进度。
     *
     * <p>实现必须廉价且不抛异常：它会被远端往返前后调用，抛出去会把一次本来能成功的
     * 调用变成失败。传进来的文案由提供方决定，编排层只负责转发。</p>
     */
    void onProgress(String message);

    /**
     * 协作式取消检查点：用户已停止本任务时抛 {@link java.util.concurrent.CancellationException}。
     *
     * <p>是"抛"而不是"返回布尔"，因为调用方十有八九会写成
     * {@code if (isCancelled()) return null;} ——那样就多了一条"被取消时返回什么"的路径，
     * 而取消本来就不该产出一个看起来正常的结果。抛出只有一个出口。</p>
     *
     * <p>实现方必须在<b>每一次外部往返前后</b>各调一次：只在前面调，用户在往返期间按了
     * 停止就得等这次往返自己超时；只在后面调，那次往返的副作用已经发生、撤不回来。</p>
     */
    void checkCancellation();
}
