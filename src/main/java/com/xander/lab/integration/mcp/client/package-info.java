/**
 * 出站 MCP 客户端：本平台作为客户端，主动连接第三方 MCP 服务器。
 *
 * <p>与 {@code com.xander.lab.integration.mcp} 下的服务端实现方向相反：那边是本平台
 * 对外提供 MCP 服务（外部宿主连进来），这边是本平台连出去用别人的工具。
 * 两者共用 {@code io.modelcontextprotocol.sdk} 依赖，但配置、表与生命周期都彼此独立。</p>
 *
 * <p>分层的职责：{@code api} 只做协议转换与归属校验；{@code application} 管连接、
 * 工具投影与配置生命周期；{@code domain} 是配置与工具目录的模型；
 * {@code infrastructure} 是持久化、出站地址校验与凭据加解密。</p>
 */
package com.xander.lab.integration.mcp.client;
