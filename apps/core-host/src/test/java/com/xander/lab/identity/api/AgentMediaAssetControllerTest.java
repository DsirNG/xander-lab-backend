package com.xander.lab.identity.api;

import com.xander.lab.content.api.MediaAssetPort;
import com.xander.lab.content.api.MediaAssetRef;
import com.xander.lab.content.api.StoredImage;
import com.xander.lab.identity.application.SecurityStateSyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMediaAssetControllerTest {

    @Test
    void forwardsAuthorizedMediaOperations() {
        MediaAssetPort media = mock(MediaAssetPort.class);
        MediaAssetRef asset = new MediaAssetRef(7L, 42L, "https://cdn/image.png", "image.png",
                "stored.png", 3L, "image/png", 1, 1, "user_upload", null, null, null);
        when(media.recordStored(42L, new StoredImage("https://cdn/image.png", "image.png",
                "stored.png", 3L, "image/png"))).thenReturn(asset);
        AgentMediaAssetController controller = new AgentMediaAssetController(media, properties());

        MediaAssetRef response = controller.recordStored("sync-secret",
                new AgentMediaAssetController.RecordStoredRequest(42L,
                        new StoredImage("https://cdn/image.png", "image.png", "stored.png", 3L, "image/png")));

        assertThat(response).isSameAs(asset);
        verify(media).recordStored(42L, new StoredImage("https://cdn/image.png", "image.png",
                "stored.png", 3L, "image/png"));
    }

    @Test
    void hidesMediaOperationsWithoutTheInternalToken() {
        MediaAssetPort media = mock(MediaAssetPort.class);
        AgentMediaAssetController controller = new AgentMediaAssetController(media, properties());

        assertThatThrownBy(() -> controller.listByTask("wrong-secret",
                new AgentMediaAssetController.TaskRequest(42L, 7L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(media);
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
