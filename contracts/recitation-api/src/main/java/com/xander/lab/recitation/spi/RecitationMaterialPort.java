package com.xander.lab.recitation.spi;

import java.util.List;

/**
 * 背诵材料能力的跨域端口。
 *
 * <p>由 {@code recitation} 域实现（{@code RecitationService}），由 {@code agent} 与
 * {@code automation} 两个域的消费方注入。依赖方向是
 * {@code agent / automation -> recitation-api <- recitation}。</p>
 *
 * <h2>方法集就是"调用方被迫实现的那一组"</h2>
 * <p>四个方法来自两个域、四个文件的调用点并集，不是照抄 {@code RecitationService} 的公开面
 * （那个类还给自己的控制器暴露了录音任务、归档、更新、删除等十几条）：</p>
 * <table>
 *   <caption>谁要哪一条</caption>
 *   <tr><th>方法</th><th>消费方</th></tr>
 *   <tr><td>{@link #createMaterial}</td><td>{@code CreateKnowledgeTool}（agent）</td></tr>
 *   <tr><td>{@link #searchMaterials}</td><td>{@code QueryKnowledgeTool}（agent）</td></tr>
 *   <tr><td>{@link #countMaterials}</td><td>{@code QueryKnowledgeTool}（agent），一次匹配数 + 三次口径统计</td></tr>
 *   <tr><td>{@link #getMaterial}</td><td>{@code BlogContentPlanService}（存在性校验）、
 *       {@code BlogPlanRunService}（取 title/content 进提示词）（automation）</td></tr>
 * </table>
 *
 * <h2>第一个参数永远是调用方 id</h2>
 * <p>所有方法都显式收 {@code Long userId}。这不是冗余：调用方可能是另一个进程里的服务，
 * {@code UserContext} 的 ThreadLocal 跨不过去，把"当前用户"藏在实现里会让跨进程调用拿到
 * 一个空的或错误的身份。提供方在 userId 为 {@code null} 时抛 401，与它给控制器的行为一致。</p>
 *
 * <h2>{@code getMaterial} 为什么在端口上叫这个名字</h2>
 * <p>提供方原本有一个 {@code RecitationMaterialView getMaterial(Long, Long)}，给控制器返回前端视图。
 * 端口要的是收窄的 {@link MaterialView}，**同名同参只差返回值在 Java 里编译不过**，
 * 所以按既有先例（{@code OssService.uploadImage}、{@code EmailReminderService#updateStatusForView}）
 * <b>端口占规范名，域内的富方法加一个说明受众的后缀</b>：富方法改名
 * {@code getMaterialForView}。另外三个方法参数类型不同，可以同名重载，不需要改名。</p>
 *
 * <h2>{@code getMaterial} 的抛错语义属于提供方</h2>
 * <p>材料不存在、或存在但不属于该用户时，提供方抛 404。{@code BlogContentPlanService} 正是
 * 靠这个把"引用了别人的知识 id"挡在创建计划之前——它**丢弃返回值**只留副作用。
 * 这条语义是提供方的行为，端口只是不遮住它；实现侧不得为了"看起来更纯"把它改成返回
 * {@code null} 或 {@code Optional}，那会让校验悄悄失效。</p>
 *
 * <h2>没有 {@code default} 方法，这是硬约束</h2>
 * <p>给端口加一个 {@code default} 方法是**最隐蔽的变宽形式**：编译通过、耦合指标一动不动、
 * 26 条门禁全绿，只有本模块的反射测试会红（见 {@code RecitationMaterialPortTest}）。
 * 所以宽度**只能**靠反射测试守，改动这个接口时务必同步改测试里的完整签名清单。</p>
 */
public interface RecitationMaterialPort {

    /**
     * 创建并标准化一份用户确认过的背诵材料。
     *
     * <p>提供方在 {@code draft} 上做的校验与它给控制器的完全一致
     * （标题非空且 ≤100 字、正文非空且 ≤10000 字、正文归一化后非空），
     * 不通过抛 {@code IllegalArgumentException}。</p>
     */
    MaterialView createMaterial(Long userId, MaterialDraft draft);

    /**
     * 按条件筛选当前用户的材料，创建时间倒序。
     *
     * <p>只返回**未归档**的材料（{@link MaterialFilter} 不带归档维度，等价于提供方的默认口径）。</p>
     */
    List<MaterialView> searchMaterials(Long userId, MaterialFilter filter);

    /**
     * 按同一筛选条件统计条数。
     *
     * <p>单独给一条统计方法是必要的，不是冗余：{@link #searchMaterials} 有条数上限，
     * 调用方必须能如实区分"没有匹配"和"匹配了但没全返回"。</p>
     */
    long countMaterials(Long userId, MaterialFilter filter);

    /**
     * 返回一份属于当前用户的材料；不存在或不属于该用户时抛 404（见类注释）。
     */
    MaterialView getMaterial(Long userId, Long materialId);
}
