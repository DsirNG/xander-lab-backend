/**
 * Shared mail delivery and verification-code templates.
 *
 * <p>⚠️ 本包<b>只做投递</b>。提醒邮件（定时提醒）的模板与渲染<b>不在这里</b>——
 * 它们在 {@code automation.domain}（{@code EmailReminderMailTemplates} /
 * {@code EmailReminderMailRenderer}）。改造前 {@code MailService} 自己渲染提醒邮件，
 * 于是它必须 import 业务域的模板类、并读 {@code email-reminder.display-zone}
 * （配置台账里 owner 记在 content-automation 名下的键），
 * 让 {@code integration → automation} 成了一条反向边。</p>
 *
 * <p>{@code LoginVerificationMailTemplates} 留在本包：登录验证码的正文是<b>邮件</b>这个能力
 * 自身的一部分，没有别的域需要引用它，也没有任何跨域 import。</p>
 */
package com.xander.lab.integration.mail;
