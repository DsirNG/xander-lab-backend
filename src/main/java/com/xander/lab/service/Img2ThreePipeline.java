package com.xander.lab.service;

import com.xander.lab.config.Img2ThreeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Executes the deterministic gates and code generator supplied by img2threejs. */
@Component
@RequiredArgsConstructor
public class Img2ThreePipeline {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private final Img2ThreeProperties properties;

    public String createStarterSpec() {
        if (!properties.isPipelineEnabled()) {
            throw new IllegalStateException("img2threejs pipeline is disabled");
        }
        Path root = pipelineRoot();
        if (!Files.isRegularFile(root.resolve("forge/stage2_spec/new_sculpt_spec.py"))) {
            throw new IllegalStateException("img2threejs pipeline is unavailable: " + root);
        }
        try {
            Path work = Files.createTempDirectory("img2three-scaffold-");
            try {
                Path scaffold = work.resolve("starter-spec.json");
                run(root, List.of(
                        "forge/stage2_spec/new_sculpt_spec.py",
                        "reference-object",
                        "--out",
                        scaffold.toString(),
                        "--force"
                ));
                return Files.readString(scaffold, StandardCharsets.UTF_8);
            } finally {
                deleteTree(work);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create img2threejs starter spec", e);
        }
    }

    public String validateAndGenerate(String title, String specJson) {
        if (!properties.isPipelineEnabled()) {
            throw new IllegalStateException("img2threejs pipeline is disabled");
        }
        Path root = pipelineRoot();
        if (!Files.isRegularFile(root.resolve("forge/stage2_spec/validate_sculpt_spec.py"))) {
            throw new IllegalStateException("img2threejs pipeline is unavailable: " + root);
        }
        try {
            Path work = Files.createTempDirectory("img2three-");
            try {
                Path spec = work.resolve("sculpt-spec.json");
                Path factory = work.resolve(safeFileName(title) + ".ts");
                Files.writeString(spec, specJson, StandardCharsets.UTF_8);
                run(root, List.of("forge/stage2_spec/validate_sculpt_spec.py", spec.toString(), "--strict-quality"));
                run(root, List.of("forge/stage3_build/generate_threejs_factory.py", spec.toString(), "--out", factory.toString()));
                return Files.readString(factory, StandardCharsets.UTF_8);
            } finally {
                deleteTree(work);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run img2threejs pipeline", e);
        }
    }

    private Path pipelineRoot() {
        return Path.of(properties.getPipelineRoot()).toAbsolutePath().normalize();
    }

    private void run(Path root, List<String> scriptArgs) {
        List<String> command = new java.util.ArrayList<>();
        command.add(StringUtils.hasText(properties.getPythonCommand()) ? properties.getPythonCommand() : "python");
        command.addAll(scriptArgs);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("img2threejs pipeline timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("img2threejs quality gate rejected this reconstruction: " + truncate(output, 12000));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start Python for img2threejs; configure IMG2THREE_PYTHON_COMMAND", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("img2threejs pipeline was interrupted", e);
        }
    }

    private String safeFileName(String value) {
        String safe = StringUtils.hasText(value) ? value.replaceAll("[^A-Za-z0-9_-]", "-") : "model";
        return safe.isBlank() ? "model" : safe;
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The operating system will reclaim the temporary directory eventually.
                }
            });
        }
    }
}
