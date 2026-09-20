package com.xander.lab.filter;

import com.xander.lab.observability.CorrelationHeaders;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsRequestBeforePassingItToTheApplication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/blog/posts");
        request.setQueryString("access_token=must-not-be-logged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("Received request: method=POST uri=/api/blog/posts "
                            + "traceId=") && message.contains(" requestId="));
        });

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .first()
                .asString()
                .doesNotContain("access_token", "must-not-be-logged");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("query=[PRESENT]"));
        assertThat(response.getHeader(CorrelationHeaders.TRACE_ID)).matches("[A-Za-z0-9-]{36}");
        assertThat(MDC.get(CorrelationHeaders.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationHeaders.MDC_REQUEST_ID)).isNull();
    }

    @Test
    void sanitizesInboundCorrelationHeadersBeforeLoggingAndProcessingThem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(CorrelationHeaders.TRACE_ID, "trace-client-7");
        request.addHeader(CorrelationHeaders.REQUEST_ID, "bad\r\nforged-header");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(MDC.get(CorrelationHeaders.MDC_TRACE_ID)).isEqualTo("trace-client-7");
            assertThat(MDC.get(CorrelationHeaders.MDC_REQUEST_ID)).matches("[A-Za-z0-9-]{36}");
        });

        assertThat(response.getHeader(CorrelationHeaders.TRACE_ID)).isEqualTo("trace-client-7");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> !message.contains("forged-header"));
    }
}
