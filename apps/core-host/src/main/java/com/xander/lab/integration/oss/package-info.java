/**
 * 阿里云 OSS 适配器。
 *
 * <p>端口（{@code OssPort}）与返回类型（{@code UploadResponse}）已搬到
 * {@code contracts/integration-api}（包 {@code com.xander.lab.integration.api}），
 * 因为 agent / knowledgebase / publishing / recitation 四个域分属四个部署单元却都要用它们。
 * 本包只留实现：阿里云 SDK、{@code @Value} 配置、懒建的客户端，以及
 * {@code upload(MultipartFile, String)} 这个本域内用的便捷重载。</p>
 */
package com.xander.lab.integration.oss;
