/**
 * 知识库文件中心模块：拥有用户私有的文件夹树、文件、文件正文、标签词表、分片上传会话、
 * 章节目录与知识结构图节点。
 *
 * <p><b>拥有</b>：{@code knowledge_folder}、{@code knowledge_file}、{@code knowledge_file_content}、
 * {@code knowledge_tag}、{@code knowledge_file_tag}、{@code knowledge_upload_session}、
 * {@code knowledge_upload_chunk}、{@code knowledge_chapter}、{@code knowledge_structure_node}
 * 这 9 张表的全部生命周期，以及 {@code /api/knowledge-base/**} 路由。</p>
 *
 * <p><b>不拥有</b>：背诵知识与测验留档（属 {@code recitation} 的 {@code recitation_material}、
 * {@code knowledge_quiz_*}）、通知表 {@code blog_notification}（属 {@code automation}）、
 * 对象存储与本地文件投递（属 {@code integration}）、用户与登录态（属 {@code identity}）。</p>
 *
 * <p><b>依赖方向</b>：本模块通过契约 {@code integration.api.OssPort} 落对象存储，通过 {@code automation} 的
 * {@code BlogNotificationService#notifyResource} 发“大部头未自动出结构图”这类通知，
 * 通过 {@code identity} 的 {@code UserContext} 读当前用户。其他模块只能调用本模块 application 层，
 * 不得直接访问本模块 mapper。</p>
 */
package com.xander.lab.knowledgebase;
