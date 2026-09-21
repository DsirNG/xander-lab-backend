package com.xander.lab.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformJuejinCatalogServiceTest {

    @Test
    void synchronizesCategoriesAndAllTagPagesThroughPlatformPorts() throws Exception {
        PlatformJuejinClient client = mock(PlatformJuejinClient.class);
        PlatformJuejinCredentialReader credentialReader = mock(PlatformJuejinCredentialReader.class);
        PlatformJuejinCatalogStore store = mock(PlatformJuejinCatalogStore.class);
        PlatformJuejinClient.Credential credential =
                new PlatformJuejinClient.Credential("cookie", "csrf", "2608", "uuid");
        PlatformJuejinProperties properties = new PlatformJuejinProperties();
        ObjectMapper mapper = new ObjectMapper();
        when(credentialReader.requireCredential(7L)).thenReturn(credential);
        when(client.queryCategories(credential)).thenReturn(mapper.readTree("""
                {"data":[{"category":{"category_id":101,"category_name":"前端","rank":1},
                "hot_tags":[{"tag_id":201,"tag_name":"JavaScript"}]}]}
                """));
        when(client.queryTagList(eq(credential), eq("0"), eq(100))).thenReturn(mapper.readTree("""
                {"data":[{"tag_id":202,"tag":{"tag_name":"Spring"}}],
                "has_more":true,"cursor":"next"}
                """));
        when(client.queryTagList(eq(credential), eq("next"), eq(100))).thenReturn(mapper.readTree("""
                {"data":[{"tag_id":203,"tag":{"tag_name":"MySQL"}}],
                "has_more":false}
                """));

        PlatformJuejinCatalogService service = new PlatformJuejinCatalogService(
                properties, client, credentialReader, store);

        service.sync(7L);

        verify(store).upsert(
                List.of(new PlatformJuejinCatalogStore.CategoryRow(101, "前端", 1)),
                List.of(
                        new PlatformJuejinCatalogStore.TagRow(201, "JavaScript", 101L),
                        new PlatformJuejinCatalogStore.TagRow(202, "Spring", null),
                        new PlatformJuejinCatalogStore.TagRow(203, "MySQL", null)));
        verify(client).queryTagList(credential, "next", 100);
    }
}
