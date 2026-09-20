package com.xander.lab.identity.api;

import com.xander.lab.identity.application.SecurityStateSyncProperties;
import com.xander.lab.recitation.spi.MaterialFilter;
import com.xander.lab.recitation.spi.MaterialView;
import com.xander.lab.recitation.spi.RecitationMaterialPort;
import com.xander.lab.recitation.spi.RecitationQuizPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRecitationControllerTest {

    @Test
    void forwardsAuthorizedMaterialAndQuizOperations() {
        RecitationMaterialPort materials = mock(RecitationMaterialPort.class);
        RecitationQuizPort quizzes = mock(RecitationQuizPort.class);
        MaterialView material = new MaterialView(7L, "title", "content", "CONCEPT", "CONCEPT",
                null, "NEW", 0, null);
        when(materials.searchMaterials(42L, MaterialFilter.all())).thenReturn(List.of(material));
        AgentRecitationController controller = new AgentRecitationController(materials, quizzes, properties());

        assertThat(controller.searchMaterials("sync-secret",
                new AgentRecitationController.MaterialFilterRequest(42L, MaterialFilter.all()))
                .materials()).containsExactly(material);
        verify(materials).searchMaterials(42L, MaterialFilter.all());
    }

    @Test
    void hidesRecitationOperationsWithoutTheInternalToken() {
        RecitationMaterialPort materials = mock(RecitationMaterialPort.class);
        RecitationQuizPort quizzes = mock(RecitationQuizPort.class);
        AgentRecitationController controller = new AgentRecitationController(materials, quizzes, properties());

        assertThatThrownBy(() -> controller.openQuiz("wrong-secret",
                new AgentRecitationController.OpenQuizRequest(42L, 7L, 9L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(materials, quizzes);
    }

    private SecurityStateSyncProperties properties() {
        SecurityStateSyncProperties properties = new SecurityStateSyncProperties();
        properties.setInternalApiEnabled(true);
        properties.setInternalToken("sync-secret");
        return properties;
    }
}
