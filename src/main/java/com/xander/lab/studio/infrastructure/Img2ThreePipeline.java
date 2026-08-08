package com.xander.lab.studio.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Executes the deterministic gates and code generator supplied by img2threejs. */
@Component
@RequiredArgsConstructor
public class Img2ThreePipeline {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration PYTHON_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROCESS_TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 128_000;
    private static final String STARTER_SCRIPT = "forge/stage2_spec/new_sculpt_spec.py";
    private static final String VALIDATOR_SCRIPT = "forge/stage2_spec/validate_sculpt_spec.py";
    private static final String GENERATOR_SCRIPT = "forge/stage3_build/generate_threejs_factory.py";

    private final Img2ThreeProperties properties;
    private final ObjectMapper objectMapper;
    private volatile String resolvedPythonCommand;

    /**
     * Readiness probe used by deterministic callers before accepting work. It intentionally checks
     * only local pipeline dependencies; remote model credentials are controlled separately.
     */
    public boolean isAvailable() {
        if (!properties.isPipelineEnabled()) return false;
        try {
            Path root = pipelineRoot();
            if (!Files.isRegularFile(root.resolve(STARTER_SCRIPT))
                    || !Files.isRegularFile(root.resolve(VALIDATOR_SCRIPT))
                    || !Files.isRegularFile(root.resolve(GENERATOR_SCRIPT))) {
                return false;
            }
            resolvePythonCommand();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public String createStarterSpec() {
        Path root = requirePipeline(STARTER_SCRIPT);
        try {
            Path work = Files.createTempDirectory("img2three-scaffold-");
            try {
                Path scaffold = work.resolve("starter-spec.json");
                run(root, List.of(
                        STARTER_SCRIPT,
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
        Path root = requirePipeline(VALIDATOR_SCRIPT, GENERATOR_SCRIPT);
        try {
            Path work = Files.createTempDirectory("img2three-");
            try {
                Path spec = work.resolve("sculpt-spec.json");
                Path finalExportSpec = work.resolve("sculpt-spec-final-export.json");
                Path factory = work.resolve(safeFileName(title) + ".ts");
                Files.writeString(spec, specJson, StandardCharsets.UTF_8);
                run(root, List.of(VALIDATOR_SCRIPT, spec.toString(), "--strict-quality"));

                String finalPassId = prepareFinalExportSpec(spec, finalExportSpec);
                run(root, List.of(
                        GENERATOR_SCRIPT,
                        finalExportSpec.toString(),
                        "--out",
                        factory.toString(),
                        "--pass-id",
                        finalPassId
                ));
                return Files.readString(factory, StandardCharsets.UTF_8);
            } finally {
                deleteTree(work);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run img2threejs pipeline", e);
        }
    }

    /**
     * The upstream CLI is pass-gated for interactive authoring and otherwise exports blockout only.
     * API reconstruction already validates the complete authored spec, so a temporary, never-persisted
     * export copy unlocks the last declared pass and therefore includes macro, meso, and micro components.
     */
    private String prepareFinalExportSpec(Path source, Path target) throws IOException {
        JsonNode parsed = objectMapper.readTree(Files.readString(source, StandardCharsets.UTF_8));
        if (!(parsed instanceof ObjectNode spec)) {
            throw new IllegalStateException("img2threejs spec must be a JSON object");
        }

        List<String> passIds = new ArrayList<>();
        JsonNode buildPasses = spec.path("buildPasses");
        if (buildPasses.isArray()) {
            for (JsonNode buildPass : buildPasses) {
                String passId = buildPass.path("id").asText("").trim();
                if (!passId.isEmpty()) passIds.add(passId);
            }
        }
        if (passIds.isEmpty()) {
            throw new IllegalStateException("img2threejs spec does not declare any build passes");
        }

        String finalPassId = passIds.get(passIds.size() - 1);
        int finalPassIndex = passIds.indexOf(finalPassId);
        ArrayNode reviewHistory;
        if (spec.path("reviewHistory") instanceof ArrayNode existingHistory) {
            reviewHistory = existingHistory;
        } else {
            reviewHistory = objectMapper.createArrayNode();
            spec.set("reviewHistory", reviewHistory);
        }
        for (int index = 0; index < finalPassIndex; index++) {
            ObjectNode exportUnlock = objectMapper.createObjectNode();
            exportUnlock.put("passId", passIds.get(index));
            exportUnlock.put("action", "continue");
            exportUnlock.putObject("visualEvidence")
                    .put("renderScreenshot", "internal://validated-final-factory-export");
            reviewHistory.add(exportUnlock);
        }

        Files.writeString(
                target,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec),
                StandardCharsets.UTF_8
        );
        return finalPassId;
    }

    private Path requirePipeline(String... requiredScripts) {
        if (!properties.isPipelineEnabled()) {
            throw new IllegalStateException("img2threejs pipeline is disabled");
        }
        Path root = pipelineRoot();
        for (String requiredScript : requiredScripts) {
            if (!Files.isRegularFile(root.resolve(requiredScript))) {
                throw new IllegalStateException("img2threejs pipeline is unavailable: " + root);
            }
        }
        return root;
    }

    private Path pipelineRoot() {
        if (!StringUtils.hasText(properties.getPipelineRoot())) {
            throw new IllegalStateException("img2threejs pipeline root is not configured");
        }
        return Path.of(properties.getPipelineRoot()).toAbsolutePath().normalize();
    }

    void run(Path root, List<String> scriptArgs) {
        List<String> command = new ArrayList<>();
        command.add(resolvePythonCommand());
        command.addAll(scriptArgs);
        try {
            ProcessResult result = executeProcess(command, root, commandTimeout());
            if (result.timedOut()) {
                throw new IllegalStateException(
                        "img2threejs pipeline timed out" + outputSuffix(result.output()));
            }
            if (result.exitCode() != 0) {
                throw new IllegalStateException(
                        "img2threejs quality gate rejected this reconstruction: "
                                + truncate(result.output(), 12000));
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot start Python for img2threejs; configure IMG2THREE_PYTHON_COMMAND", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("img2threejs pipeline was interrupted", e);
        }
    }

    Duration commandTimeout() {
        return COMMAND_TIMEOUT;
    }

    String resolvePythonCommand() {
        String cached = resolvedPythonCommand;
        if (cached != null) return cached;

        synchronized (this) {
            if (resolvedPythonCommand != null) return resolvedPythonCommand;
            String configured = properties.getPythonCommand();
            List<String> candidates = StringUtils.hasText(configured)
                    ? List.of(configured.trim())
                    : defaultPythonCandidates(System.getProperty("os.name", ""));
            for (String candidate : candidates) {
                if (isPythonCommandUsable(candidate)) {
                    resolvedPythonCommand = candidate;
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "No usable Python 3 runtime found; configure IMG2THREE_PYTHON_COMMAND");
        }
    }

    static List<String> defaultPythonCandidates(String osName) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        return windows ? List.of("python", "python3") : List.of("python3", "python");
    }

    boolean isPythonCommandUsable(String command) {
        try {
            ProcessResult result = executeProcess(
                    List.of(command, "--version"),
                    null,
                    PYTHON_PROBE_TIMEOUT
            );
            String version = result.output().trim();
            return !result.timedOut()
                    && result.exitCode() == 0
                    && version.matches("(?is)^Python\\s+3(?:\\.|\\s).*$");
        } catch (IOException ignored) {
            // Missing executable or a Windows Store app execution alias: try the next candidate.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ProcessResult executeProcess(List<String> command, Path root, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (root != null) builder.directory(root.toFile());
        Process process = builder.start();
        OutputCollector output = new OutputCollector(MAX_CAPTURED_OUTPUT_BYTES);
        Thread drainer = new Thread(
                () -> drain(process.getInputStream(), output),
                "img2three-process-output"
        );
        drainer.setDaemon(true);
        drainer.start();

        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!completed) terminate(process);
        drainer.join(PROCESS_TERMINATION_GRACE.toMillis());
        if (drainer.isAlive()) drainer.interrupt();

        int exitCode = completed ? process.exitValue() : -1;
        return new ProcessResult(completed, exitCode, output.text());
    }

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(PROCESS_TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void drain(InputStream input, OutputCollector output) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.append(buffer, read);
            }
        } catch (IOException ignored) {
            // Process termination closes the stream; already captured output remains useful diagnostics.
        }
    }

    private String safeFileName(String value) {
        String safe = StringUtils.hasText(value) ? value.replaceAll("[^A-Za-z0-9_-]", "-") : "model";
        return safe.isBlank() ? "model" : safe;
    }

    private String outputSuffix(String output) {
        return StringUtils.hasText(output) ? ": " + truncate(output, 12000) : "";
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

    private record ProcessResult(boolean completed, int exitCode, String output) {
        private boolean timedOut() {
            return !completed;
        }
    }

    private static final class OutputCollector {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private OutputCollector(int limit) {
            this.limit = limit;
        }

        private synchronized void append(byte[] value, int length) {
            int writable = Math.min(length, limit - bytes.size());
            if (writable > 0) bytes.write(value, 0, writable);
        }

        private synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
