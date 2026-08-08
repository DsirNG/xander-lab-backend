package com.xander.lab.studio.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xander.lab.publishing.application.BlogMediaService;
import com.xander.lab.studio.api.Img2ThreeTaskVO;
import com.xander.lab.studio.domain.Img2ThreeTask;
import com.xander.lab.studio.infrastructure.Img2ThreeModelClient;
import com.xander.lab.studio.infrastructure.Img2ThreePipeline;
import com.xander.lab.studio.infrastructure.Img2ThreeProperties;
import com.xander.lab.studio.infrastructure.Img2ThreeTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Img2ThreeServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Img2ThreeTask.class
        );
    }

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void defaultModeCompletesWithDeterministicReliefWithoutRemoteModel() throws Exception {
        Path imagePath = tempDir.resolve("reference.png");
        BufferedImage image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 240, 160);
        graphics.setColor(new Color(8, 116, 72));
        graphics.fillRoundRect(30, 54, 180, 52, 18, 18);
        graphics.dispose();
        ImageIO.write(image, "png", imagePath.toFile());

        Img2ThreeTaskMapper taskMapper = mock(Img2ThreeTaskMapper.class);
        BlogMediaService mediaService = mock(BlogMediaService.class);
        Img2ThreeModelClient modelClient = mock(Img2ThreeModelClient.class);
        Img2ThreePipeline pipeline = mock(Img2ThreePipeline.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Img2ThreeReliefGenerator reliefGenerator = new Img2ThreeReliefGenerator(objectMapper);
        Img2ThreeProperties properties = new Img2ThreeProperties();
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        Img2ThreeTask created = task(41L, "created", imagePath.toUri().toString());
        Img2ThreeTask running = task(41L, "running", imagePath.toUri().toString());
        Img2ThreeTask ready = task(41L, "ready", imagePath.toUri().toString());
        AtomicReference<String> generatedSpec = new AtomicReference<>();

        when(taskMapper.selectOne(any())).thenReturn(created, created, running, ready);
        when(taskMapper.update(isNull(), any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(1);
            if (wrapper instanceof LambdaUpdateWrapper<?> update) {
                update.getParamNameValuePairs().values().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(value -> value.contains("\"schemaVersion\":\"xander-image-relief/1\""))
                        .findFirst()
                        .ifPresent(value -> {
                            generatedSpec.set(value);
                            ready.setSpecJson(value);
                            ready.setTitle("reference");
                        });
            }
            return 1;
        });

        Img2ThreeService service = new Img2ThreeService(
                taskMapper,
                mediaService,
                modelClient,
                pipeline,
                reliefGenerator,
                properties,
                objectMapper,
                transactionTemplate
        );
        List<String> events = new ArrayList<>();

        Img2ThreeTaskVO result = service.runStream(41L, 7L,
                (event, data) -> events.add(event + ":" + data));

        assertEquals("ready", result.getStatus());
        assertNotNull(generatedSpec.get());
        assertNotNull(result.getSceneSpec());
        assertTrue(events.stream().anyMatch(event -> event.startsWith("stage:spec|")));
        verify(modelClient, never()).reconstruct(any(), any(), any());
        verify(pipeline, never()).createStarterSpec();
    }

    private Img2ThreeTask task(Long id, String status, String url) {
        Img2ThreeTask task = new Img2ThreeTask();
        task.setId(id);
        task.setUserId(7L);
        task.setStatus(status);
        task.setStage("analyze");
        task.setTitle("reference");
        task.setReferenceMediaUrl(url);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }
}
