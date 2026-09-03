package com.xander.lab.agent.application;

/**
 * 一段随工具一起挂载的提示词准则。
 *
 * <p>为什么不写在全局行动准则里：那份准则曾经有一条"用户要代码就必须调用 emit_artifact"，
 * 和 {@code EmitArtifactTool.description()} 里的同义要求各写了一份。改工具描述的人看不到准则那份，
 * 于是两处一起把模型逼到"任何代码请求都开交付卡"，一句"给我一个 sse 代码"也开卡。
 * 准则和工具放在同一个文件里，改一处就不会漏另一处。</p>
 *
 * <p>另一个好处是条件性：只有本轮真在工具表里的工具，它的准则才进提示词。
 * 全局准则做不到这件事——它会为一个当轮根本不存在的工具规定行为。</p>
 *
 * @param order 渲染顺序，小的在前；同序按工具名排序，保证提示词前缀每轮一致（便于上游 prompt 缓存）
 * @param text  准则正文，一行一条，不带序号（序号由渲染时统一生成）
 */
public record AgentPromptSection(int order, String text) {

    /** 会话内交付类：答题卡、交付卡这些"这一轮答案长什么样"的规定。 */
    public static final int ORDER_DELIVERY = 100;
    /** 判分与评估类：依赖用户在对话里的作答。 */
    public static final int ORDER_ASSESSMENT = 200;
    /** 产生外部副作用的工具：发布、派发子智能体这些要额外说清边界的。 */
    public static final int ORDER_SIDE_EFFECT = 300;

    public AgentPromptSection {
        text = text == null ? "" : text.trim();
    }

    public static AgentPromptSection delivery(String text) {
        return new AgentPromptSection(ORDER_DELIVERY, text);
    }

    public static AgentPromptSection assessment(String text) {
        return new AgentPromptSection(ORDER_ASSESSMENT, text);
    }

    public static AgentPromptSection sideEffect(String text) {
        return new AgentPromptSection(ORDER_SIDE_EFFECT, text);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }
}
