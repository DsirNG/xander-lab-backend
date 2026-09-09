package com.xander.lab.agent.application;

import com.xander.lab.agent.domain.AgentAttachment;
import com.xander.lab.integration.oss.OssService;
import com.xander.lab.integration.oss.UploadResponse;
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
        OssService oss = mock(OssService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(oss.upload(file, "agent/42/")).thenReturn(UploadResponse.builder()
                .url("https://cdn.example.com/agent/42/notes.pdf")
                .originalName("notes.pdf").size(3).contentType("application/pdf").build());

        AgentAttachment result = new AgentAttachmentService(oss, null).upload(42L, file);

        assertThat(result.name()).isEqualTo("notes.pdf");
        assertThat(result.url()).startsWith("https://cdn.example.com/agent/42/");
        verify(oss).upload(file, "agent/42/");
    }

    @Test
    void rejectsUnsupportedExecutableBeforeRemoteUpload() {
        OssService oss = mock(OssService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "unsafe.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> new AgentAttachmentService(oss, null).upload(42L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("暂不支持该附件格式");
    }
}
