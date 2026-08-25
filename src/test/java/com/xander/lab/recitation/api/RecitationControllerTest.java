package com.xander.lab.recitation.api;

import com.xander.lab.identity.security.UserContext;
import com.xander.lab.recitation.application.KnowledgeQuizService;
import com.xander.lab.recitation.application.RecitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecitationControllerTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void knowledgeListForwardsEveryFilterToTheSameQueryPathTheAgentUses() {
        RecitationService service = mock(RecitationService.class);
        RecitationController controller = new RecitationController(service, mock(KnowledgeQuizService.class));
        UserContext.setUserId(42L);
        RecitationService.MaterialQuery expected =
                new RecitationService.MaterialQuery("勾股", "MASTERED", true, 30);
        when(service.searchMaterials(42L, expected)).thenReturn(List.of());

        List<RecitationMaterialView> body =
                controller.listMaterials("勾股", "MASTERED", true, 30, null).getData();

        assertThat(body).isEmpty();
        verify(service).searchMaterials(42L, expected);
    }

    @Test
    void omittingEveryFilterStillReturnsTheRecentBatchAsBefore() {
        RecitationService service = mock(RecitationService.class);
        RecitationController controller = new RecitationController(service, mock(KnowledgeQuizService.class));
        UserContext.setUserId(42L);

        controller.listMaterials(null, null, false, 100, null);

        verify(service).searchMaterials(42L,
                new RecitationService.MaterialQuery(null, null, false, 100));
    }
}
