package com.xander.lab.studio.infrastructure;

import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Img2ThreePropertiesTest {

    @Test
    void bindsPipelineAtRootAndModelSettingsUnderAgent() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "img2three.pipeline-enabled", "false",
                "img2three.pipeline-root", "custom/img2threejs",
                "img2three.python-command", "C:/Python312/python.exe",
                "img2three.agent.enabled", "true",
                "img2three.agent.base-url", "https://model.example/v1",
                "img2three.agent.api-key", "top-secret-key",
                "img2three.agent.model", "vision-model"
        )));

        Img2ThreeProperties properties = binder
                .bind("img2three", Bindable.of(Img2ThreeProperties.class))
                .orElseThrow(() -> new AssertionError("img2three properties did not bind"));

        assertThat(properties.isPipelineEnabled()).isFalse();
        assertThat(properties.getPipelineRoot()).isEqualTo("custom/img2threejs");
        assertThat(properties.getPythonCommand()).isEqualTo("C:/Python312/python.exe");
        assertThat(properties.isAgentEnabled()).isTrue();
        assertThat(properties.getBaseUrl()).isEqualTo("https://model.example/v1");
        assertThat(properties.getApiKey()).isEqualTo("top-secret-key");
        assertThat(properties.getModel()).isEqualTo("vision-model");
        assertThat(properties.toString()).doesNotContain("top-secret-key");
        assertThat(properties.getAgent().toString()).doesNotContain("top-secret-key");
    }

    @Test
    void remoteAgentIsDisabledByDefault() {
        Img2ThreeProperties properties = new Img2ThreeProperties();

        assertThat(properties.isPipelineEnabled()).isTrue();
        assertThat(properties.getPythonCommand()).isBlank();
        assertThat(properties.isAgentEnabled()).isFalse();
        assertThat(properties.getBaseUrl()).isBlank();
        assertThat(properties.getApiKey()).isBlank();
        assertThat(properties.getModel()).isBlank();
    }
}
