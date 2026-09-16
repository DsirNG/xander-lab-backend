/**
 * 个人知识掌握模块，拥有知识材料、持久化测验会话、评分记录、录音识别任务与确定性文本对齐结果。
 *
 * <p>本模块通过 admin 模块公开的 ModelGateway 读取模型端点，通过契约 {@code integration.api.OssPort}
 * 上传录音；其他模块只能调用本模块 application 层，不得直接访问其 mapper。</p>
 */
package com.xander.lab.recitation;
