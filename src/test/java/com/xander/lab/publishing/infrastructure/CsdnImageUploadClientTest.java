package com.xander.lab.publishing.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnImageUploadClientTest {

    @Test
    void extractUrlAcceptsTextualData() {
        assertThat(read("""
                {"code":200,"data":"https://cdn.example.com/a.png"}
                """)).isEqualTo("https://cdn.example.com/a.png");
    }

    @Test
    void extractUrlAcceptsObjectData() {
        assertThat(read("""
                {"code":200,"data":{"url":"https://cdn.example.com/b.png"}}
                """)).isEqualTo("https://cdn.example.com/b.png");
    }

    private String read(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return CsdnImageUploadClient.extractUrl(mapper.readTree(json).path("data"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
