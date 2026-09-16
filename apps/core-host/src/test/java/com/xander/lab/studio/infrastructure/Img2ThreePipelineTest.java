package com.xander.lab.studio.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.studio.infrastructure.Img2ThreePipeline;
import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Img2ThreePipelineTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void windowsPrefersWorkingPythonCommandBeforeStoreAliasFallback() {
        assertThat(Img2ThreePipeline.defaultPythonCandidates("Windows 11"))
                .containsExactly("python", "python3");
        assertThat(Img2ThreePipeline.defaultPythonCandidates("Linux"))
                .containsExactly("python3", "python");
    }

    @Test
    void availabilityRequiresEnabledPipelineScriptsAndPython() throws IOException {
        Path root = tempDir.resolve("pipeline");
        createPipelineFiles(root);
        Img2ThreeProperties properties = properties(root);
        Img2ThreePipeline pipeline = new Img2ThreePipeline(properties, objectMapper) {
            @Override
            String resolvePythonCommand() {
                return "working-python";
            }
        };

        assertThat(pipeline.isAvailable()).isTrue();

        properties.setPipelineEnabled(false);
        assertThat(pipeline.isAvailable()).isFalse();
    }

    @Test
    void finalFactoryUsesLastFullFidelityPassWithoutMutatingPersistedSpec() throws IOException {
        Path root = tempDir.resolve("pipeline");
        createPipelineFiles(root);
        RecordingPipeline pipeline = new RecordingPipeline(properties(root), objectMapper);
        String specJson = """
                {
                  "targetName": "Reference knife",
                  "buildPasses": [
                    {"id": "blockout"},
                    {"id": "material-pass"},
                    {"id": "optimization-pass"}
                  ],
                  "componentTree": [
                    {"id": "macro", "level": "macro"},
                    {"id": "meso", "level": "meso"},
                    {"id": "micro", "level": "micro"}
                  ],
                  "reviewHistory": []
                }
                """;

        String factory = pipeline.validateAndGenerate("Reference knife", specJson);

        assertThat(factory).isEqualTo("// complete final factory");
        assertThat(pipeline.commands).hasSize(2);
        assertThat(pipeline.commands.get(1))
                .containsSubsequence("--pass-id", "optimization-pass");
        assertThat(pipeline.exportedSpec.path("componentTree")).hasSize(3);
        assertThat(pipeline.exportedSpec.path("reviewHistory")).hasSize(2);
        assertThat(objectMapper.readTree(specJson).path("reviewHistory")).isEmpty();
    }

    @Test
    void timedOutProcessIsTerminatedBeforeOutputDrainCompletes() throws IOException {
        Path slowSource = tempDir.resolve("SlowCommand.java");
        Files.writeString(slowSource, """
                public class SlowCommand {
                    public static void main(String[] args) throws Exception {
                        System.out.println("started");
                        System.out.flush();
                        Thread.sleep(30000L);
                    }
                }
                """, StandardCharsets.UTF_8);
        Img2ThreePipeline pipeline = new Img2ThreePipeline(properties(tempDir), objectMapper) {
            @Override
            String resolvePythonCommand() {
                return javaCommand();
            }

            @Override
            Duration commandTimeout() {
                return Duration.ofMillis(250);
            }
        };
        Instant started = Instant.now();

        assertThatThrownBy(() -> pipeline.run(tempDir, List.of(slowSource.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    private Img2ThreeProperties properties(Path root) {
        Img2ThreeProperties properties = new Img2ThreeProperties();
        properties.setPipelineRoot(root.toString());
        return properties;
    }

    private void createPipelineFiles(Path root) throws IOException {
        createFile(root, "forge/stage2_spec/new_sculpt_spec.py");
        createFile(root, "forge/stage2_spec/validate_sculpt_spec.py");
        createFile(root, "forge/stage3_build/generate_threejs_factory.py");
    }

    private void createFile(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    private static String javaCommand() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    private static final class RecordingPipeline extends Img2ThreePipeline {
        private final ObjectMapper objectMapper;
        private final List<List<String>> commands = new ArrayList<>();
        private JsonNode exportedSpec;

        private RecordingPipeline(Img2ThreeProperties properties, ObjectMapper objectMapper) {
            super(properties, objectMapper);
            this.objectMapper = objectMapper;
        }

        @Override
        void run(Path root, List<String> scriptArgs) {
            commands.add(List.copyOf(scriptArgs));
            if (!scriptArgs.get(0).endsWith("generate_threejs_factory.py")) return;
            try {
                exportedSpec = objectMapper.readTree(Files.readString(
                        Path.of(scriptArgs.get(1)), StandardCharsets.UTF_8));
                int outputIndex = scriptArgs.indexOf("--out") + 1;
                Files.writeString(
                        Path.of(scriptArgs.get(outputIndex)),
                        "// complete final factory",
                        StandardCharsets.UTF_8
                );
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
