package com.xander.lab.agent.spi;

/**
 * 一个由外部来源贡献给智能体工具面的工具。
 *
 * <p>这不是 {@code com.xander.lab.agent.application.AgentTool}，而且<b>不能</b>是它。
 * 那一个有两个特点让它当不了契约类型：它的 {@code parametersSchema()} 返回
 * {@code com.fasterxml.jackson.databind.JsonNode}，契约模块必须与序列化
 * 框架无关；它还有 6 个 {@code default} 成员和一个 5 方法的嵌套监听器，搬过来等于把
 * agent 的整套工具词汇表塞进每个依赖方。它有 50 个实现、41 个覆写
 * {@code parametersSchema()}，所以"把 AgentTool 搬进契约"要改 50 个文件。</p>
 *
 * <p>本接口是那个问题的另一条出路：<b>只回答消费方真正要问的问题</b>，把 JSON 当作
 * 文本搬运，于是 50 个实现一个都不用动，Jackson 的切口被限制在端口这一层加两侧各一个
 * 适配器。端口形状是设计自由——选那个让实现侧改动退化成"换个类型"的形状。</p>
 *
 * <p><b>方法全部抽象，没有 {@code default}。</b>端口变宽不会触发任何一道防线（编译过、
 * 指标不动、门禁不报），而加 {@code default} 是变宽的静默形式：它不通知任何实现。要加
 * 能力就加抽象方法，让每个实现显式表态。由 {@code RemoteToolTest} 反射钉住。</p>
 *
 * <h2>名字的归属</h2>
 * <p>{@link #name()} 是<b>本平台工具面里</b>的名字，不是远端自己的名字。远端工具名不受本
 * 平台控制，必须由提供方加命名空间前缀并做字符清洗，否则一个叫 {@code remember} 的远端工具
 * 会撞上内置工具——而合并时内置工具优先（{@code putIfAbsent}），撞名的远端工具被<b>静默丢弃</b>。
 * 前缀里还要带上服务器标识：{@code agent_mcp_server} 的唯一键是
 * {@code (owner_user_id, server_key)}，所以两台服务器可以都叫 {@code github}，
 * 它们的同名工具只能靠前缀区分。</p>
 *
 * <h2>JSON 走文本</h2>
 * <p>schema、参数、返回值三处都是 {@code String}，内容都是 JSON 文本。这是「契约只许 JDK」的直接
 * 后果，不是风格选择。代价是调用侧多一次解析（适配器里缓存）、提供方多一次
 * {@code toString}；对照的是一次真实网络往返，可以忽略。</p>
 */
public interface RemoteTool {

    /**
     * 本平台工具面里的工具名，全局唯一（见类注释）。
     *
     * <p>返回 {@code null} 或空串的工具会被合并逻辑丢掉，而不是让整轮对话失败。</p>
     */
    String name();

    /**
     * 给模型看的说明：这个工具是什么、什么时候该用。
     *
     * <p>它会和 schema 一起渲染进每一轮的提示词，所以要短。长度由<b>调用方</b>封顶：
     * 这段文字的长度由第三方决定，不封顶等于把提示词预算的控制权交出去。</p>
     */
    String description();

    /**
     * 参数 JSON Schema，以 JSON 文本给出。
     *
     * <p>空串 / {@code null} 表示提供方没有 schema，此时<b>调用方必须替一个空对象 schema</b>
     * （{@code {"type":"object","properties":{}}}）。不能渲染成 {@code null}——工具表里出现
     * 一个 null 参数会让模型无从下手，而那看起来像"这个工具不需要参数"，是两回事。</p>
     */
    String parametersSchemaJson();

    /**
     * 这个工具自带的使用准则正文；空串 / {@code null} 表示没有。
     *
     * <p>准则跟着工具走而不是进全局提示词：全局准则会为一个当轮根本不在表里的工具规定行为。
     * 渲染顺序<b>不</b>在这里给——那是渲染方的策略，不是提供方的数据。远端工具的定义就是
     * "调用会离开本平台"，所以它的准则天然属于副作用类，适配器统一挂到
     * {@code AgentPromptSection.ORDER_SIDE_EFFECT} 上。若把 order 放进契约，agent 的常量表
     * 就得跨模块再抄一份，两份值可以无声漂移。</p>
     */
    String promptText();

    /**
     * 为什么这次调用需要用户审批；空串 / {@code null} 表示免审批。
     *
     * <p><b>实现必须是纯函数</b>：只看 {@code argsJson}，不改任何状态。编排层会在真正执行
     * 之前先调它判断这一批能不能并发（命中审批就得退回串行逐个执行），执行管道里还会再调
     * 一次。有副作用的实现会被调用两次。</p>
     *
     * <p>参数以 JSON 文本传入，同样是为了把 Jackson 挡在契约之外。远端工具的能力是本平台
     * 事先不知道的，所以默认应当<b>一律要求审批</b>：调用会带着用户授权离开本平台，出事了
     * 本平台既拦不住也撤不回。</p>
     */
    String approvalReason(String argsJson);

    /**
     * 这个工具是否只读、可以并发执行。
     *
     * <p>它是<b>并发执行的准入条件</b>，不是文档性标注：编排循环只在一步里每个调用都返回
     * {@code true} 时才把它们放到线程池上同时跑。拿不准必须返回 {@code false}——新增实现
     * 忘了表态只会退化成串行（慢一点），而误标成只读会让两个写操作真正并发起来（写坏数据）。</p>
     *
     * <p>它和 {@link #approvalReason(String)} 互不蕴含：只读工具照样可以要求审批。</p>
     */
    boolean readOnly();

    /**
     * 执行一次调用，返回结果的 JSON 文本。
     *
     * <p>返回<b>不得</b>为 {@code null} 或空白：调用侧要把它解析回结构再交给模型，
     * 一个空返回值在那里只能变成一次 NPE。没有结果时要返回一个能表达"没有结果"的 JSON
     * （例如 {@code {"isError":true,"text":"…"}}），而不是空串。由
     * {@code RemoteToolAdapterTest} 钉住调用侧对这条约定的处理。</p>
     *
     * <p>{@code userId} 显式传入而不是靠请求线程上下文：工具永远按用户归属执行，而
     * ThreadLocal 跨不过进程边界——这条从 {@code AgentTool} 继承下来的规矩在拆服务之后
     * 才真正开始生效。</p>
     *
     * <p>实现必须在每一次外部往返前后各调一次
     * {@link RemoteToolCall#checkCancellation()}（见它的注释），并在开始往返时先报一条进度。</p>
     *
     * @param userId   归属用户
     * @param argsJson 已校验的调用参数，JSON 文本；可能为 {@code null}，表示无参数
     * @param call     本次调用回传给编排层的通道
     */
    String invoke(Long userId, String argsJson, RemoteToolCall call);
}
