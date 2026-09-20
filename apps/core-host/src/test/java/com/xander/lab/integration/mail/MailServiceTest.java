package com.xander.lab.integration.mail;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MailService} 的三个端口方法。
 *
 * <p>⚠️ 本类此前<b>不存在</b>：{@code MailService} 在此之前没有实现任何契约，
 * 于是"它做了什么"只在三个消费方的测试里以 mock 的形式出现 —— 真实实现一次都没被跑过。
 * 它成为端口实现之后，按"端口实现必须有测试"的规矩补上本类。</p>
 *
 * <p>这里刻意<b>不</b>断言"模板被调用了"：模板是 {@code LoginVerificationMailTemplates}，
 * 它有自己的测试。本类只断言"投递这一步把该带的东西带上了"。</p>
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailService = new MailService(mailSender);
        ReflectionTestUtils.setField(mailService, "from", "noreply@dinqor.test");
    }

    @Test
    void sendSimpleMailCarriesFromToSubjectAndBody() {
        mailService.sendSimpleMail("alice@example.com", "主题", "正文");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertEquals("noreply@dinqor.test", sent.getFrom());
        assertEquals(List.of("alice@example.com"), List.of(sent.getTo()));
        assertEquals("主题", sent.getSubject());
        assertEquals("正文", sent.getText());
    }

    /**
     * ⭐ {@code sendHtmlMail} 收的是<b>两份成品正文</b>，两份都要进 MIME 树。
     *
     * <p>这一条同时钉住"端口只带发什么"：若调用方给的是
     * {@code scheduledAt / contentType / templateId}，HTML 由邮件服务自己渲染；
     * 现在调用方给的是渲染好的 {@code plainText} 与 {@code html}，邮件服务<b>不做任何加工</b>。
     * 断言"原样出现"就是在钉这一点。</p>
     */
    @Test
    void sendHtmlMailCarriesBothFinishedBodiesVerbatim() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        mailService.sendHtmlMail("alice@example.com", "主题", "纯文本兜底", "<p>HTML 正文</p>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertEquals("noreply@dinqor.test", sent.getFrom()[0].toString());
        assertEquals("主题", sent.getSubject());
        List<String> bodies = flattenedBodies(sent.getContent());
        assertTrue(bodies.contains("纯文本兜底"), "纯文本兜底要原样进 MIME 树：" + bodies);
        assertTrue(bodies.contains("<p>HTML 正文</p>"), "HTML 正文要原样进 MIME 树：" + bodies);
    }

    /**
     * 验证码要出现在两份正文里 —— 但两份的"出现方式"不一样。
     *
     * <p>⚠️ 纯文本里是连续的 {@code 246810}；HTML 里每个数字住在<b>自己的</b>
     * {@code <span>} 里（{@code LoginVerificationMailTemplates.buildDigitCells}，
     * 排版与防抓取需要），所以 HTML 原文里<b>根本不含</b>连续子串 {@code 246810}。
     * 第一版这条断言写的是对两份正文都 {@code contains("246810")}，于是它红了 ——
     * <b>红得对</b>：它证明"验证码进了 HTML"这件事只能按"剥掉标签与空白后的文本"来判，
     * 按原文子串判会得出相反结论。本方法同时钉住"HTML 是逐位渲染的"这个既有形状。</p>
     */
    @Test
    void sendVerificationCodeWiresInTheCodeAndBothBodies() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        mailService.sendVerificationCode("alice@example.com", "246810");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertTrue(sent.getSubject() != null && !sent.getSubject().isBlank(), "验证码邮件要有主题");
        List<String> bodies = flattenedBodies(sent.getContent());
        assertEquals(2, bodies.size(), "纯文本 + HTML 两份正文：" + bodies);

        String html = bodies.stream()
                .filter(body -> body.contains("<!DOCTYPE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("两份正文里没有一份是 HTML：" + bodies));
        String plain = bodies.stream()
                .filter(body -> !body.equals(html))
                .findFirst()
                .orElseThrow();

        assertTrue(plain.contains("246810"), "纯文本正文里验证码是连续的：" + plain);
        assertTrue(collapsedText(html).contains("246810"),
                "HTML 正文剥掉标签与空白后必须能拼回验证码：" + collapsedText(html));
    }

    /** 去掉 HTML 标签与所有空白 —— 验证码在 HTML 里逐位分居不同的 {@code <span>}。 */
    private static String collapsedText(String html) {
        return html.replaceAll("<[^>]*>", "").replaceAll("\\s+", "");
    }

    /** 投递失败必须<b>抛</b>：调用方（调度器）靠"抛没抛"决定重试还是标失败。 */
    @Test
    void deliveryFailureIsThrownNotSwallowed() {
        doThrow(new MailSendException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> mailService.sendSimpleMail("alice@example.com", "主题", "正文"));
        assertTrue(thrown.getMessage() != null && !thrown.getMessage().isBlank());
    }

    /** MIME 树里所有叶子正文（{@code multipart/mixed → multipart/alternative → text/*}）。 */
    private static List<String> flattenedBodies(Object content) throws Exception {
        List<String> out = new ArrayList<>();
        collect(content, out);
        return out;
    }

    private static void collect(Object content, List<String> out) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i).getContent(), out);
            }
        } else if (content instanceof String text) {
            out.add(text);
        }
    }
}
