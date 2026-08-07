package com.xander.lab.publishing.infrastructure;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CsdnPublisherTest {

    @Test
    void extractCoverImagesUsesFirstImage() throws Exception {
        Method method = CsdnPublisher.class.getDeclaredMethod("extractCoverImages", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var covers = (java.util.List<String>) method.invoke(null, """
                <p>intro</p><img src="https://cdn.example.com/a.png"><img src="https://cdn.example.com/b.png">
                """);
        assertThat(covers).containsExactly("https://cdn.example.com/a.png");
    }
}
