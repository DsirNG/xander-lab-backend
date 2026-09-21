package com.xander.lab.integration.internal.api;

import com.xander.lab.integration.api.MailPort;
import com.xander.lab.integration.internal.application.IntegrationInternalApiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Narrow Core mail delivery boundary for content-automation.
 *
 * <p>The caller sends already-rendered content. Templates and display timezones stay in the
 * automation owner; this controller exposes no verification-code or mail configuration API.</p>
 */
@RestController
@RequestMapping("/internal/automation/mail")
@ConditionalOnProperty(prefix = "integration.internal", name = "api-enabled", havingValue = "true")
public class InternalAutomationMailController {

    private static final String INTERNAL_TOKEN_HEADER = "X-DinQor-Internal-Token";

    private final MailPort mailPort;
    private final IntegrationInternalApiProperties properties;

    public InternalAutomationMailController(MailPort mailPort,
                                             IntegrationInternalApiProperties properties) {
        this.mailPort = mailPort;
        this.properties = properties;
        requireToken(properties);
    }

    @PostMapping("/simple")
    public void sendSimple(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                           @RequestBody SimpleMailRequest request) {
        authorize(token);
        validate(request.to(), request.subject());
        mailPort.sendSimpleMail(request.to(), request.subject(), request.content());
    }

    @PostMapping("/html")
    public void sendHtml(@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                         @RequestBody HtmlMailRequest request) {
        authorize(token);
        validate(request.to(), request.subject());
        if (request.plainText() == null || request.html() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮件正文不能为空");
        }
        mailPort.sendHtmlMail(request.to(), request.subject(), request.plainText(), request.html());
    }

    private void authorize(String token) {
        if (token == null || !MessageDigest.isEqual(
                properties.getInternalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found");
        }
    }

    private void validate(String to, String subject) {
        if (to == null || to.isBlank() || subject == null || subject.isBlank()
                || subject.contains("\r") || subject.contains("\n")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮件请求参数非法");
        }
    }

    private void requireToken(IntegrationInternalApiProperties properties) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException(
                    "integration.internal.token 在启用自动化邮件内网 API 时不能为空");
        }
    }

    public record SimpleMailRequest(String to, String subject, String content) {
    }

    public record HtmlMailRequest(String to, String subject, String plainText, String html) {
    }
}
