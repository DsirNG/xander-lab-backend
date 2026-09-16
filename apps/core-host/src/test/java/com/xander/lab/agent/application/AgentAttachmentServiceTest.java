package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.integration.api.UploadResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAttachmentServiceTest {

    @Test
    void uploadsSupportedAttachmentIntoTheOwningUsersPrefix() {
        OssPort oss = mock(OssPort.class);
        byte[] content = {1, 2, 3};
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", content);
        // 端口收的是 byte[] 而不是 MultipartFile：服务间调用要能在没有 Servlet 的进程里成立。
        when(oss.upload(content, "notes.pdf", "application/pdf", "agent/42/"))
                .thenReturn(new UploadResponse("https://cdn.example.com/agent/42/notes.pdf",
                        "notes.pdf", "notes.pdf", 3L, "application/pdf", "pdf"));

        AgentAttachment result = new AgentAttachmentService(oss, null).upload(42L, file);

        assertThat(result.name()).isEqualTo("notes.pdf");
        assertThat(result.url()).startsWith("https://cdn.example.com/agent/42/");
        verify(oss).upload(content, "notes.pdf", "application/pdf", "agent/42/");
    }

    @Test
    void rejectsUnsupportedExecutableBeforeRemoteUpload() {
        OssPort oss = mock(OssPort.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "unsafe.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> new AgentAttachmentService(oss, null).upload(42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("暂不支持该附件格式");
    }
}
