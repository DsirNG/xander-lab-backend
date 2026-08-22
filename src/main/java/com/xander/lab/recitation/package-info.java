/**
 * 中文背诵检查模块，拥有背诵材料、录音识别任务与确定性文本对齐结果。
 *
 * <p>本模块通过 admin 模块公开的 ModelGateway 读取模型端点，通过 integration.oss
 * 上传录音；其他模块只能调用本模块 application 层，不得直接访问其 mapper。</p>
 */
package com.xander.lab.recitation;
