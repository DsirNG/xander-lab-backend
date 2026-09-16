package com.xander.lab.studio.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Img2ThreeReliefGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Img2ThreeReliefGenerator generator = new Img2ThreeReliefGenerator(objectMapper);

    @Test
    void createsTexturePreservingReliefAndRemovesPlainBackground() throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(246, 248, 250));
        graphics.fillRect(0, 0, 320, 180);
        graphics.setColor(new Color(24, 134, 82));
        graphics.fillRoundRect(42, 52, 236, 76, 24, 24);
        graphics.dispose();

        Img2ThreeReliefGenerator.GeneratedRelief result = generator.generate(
                png(image),
                "image/png",
                "https://assets.example.test/reference.png",
                "green-product"
        );

        assertEquals(Img2ThreeReliefGenerator.SCHEMA_VERSION,
                result.sceneSpec().path("schemaVersion").asText());
        assertEquals("image-relief", result.sceneSpec().path("kind").asText());
        assertTrue(result.sceneSpec().path("image").path("dataUrl").asText().startsWith("data:image/png;base64,"));
        assertTrue(result.sceneSpec().path("image").path("backgroundRemoved").asBoolean());
        assertTrue(result.sceneSpec().path("image").path("aspectRatio").asDouble() > 2.0);
        assertTrue(result.sceneSpec().path("relief").path("depth").asDouble() > 0);
        assertTrue(result.analysis().path("usable").asBoolean());
        assertTrue(result.factoryCode().contains("createModel"));
    }

    @Test
    void keepsComplexFullFrameUsable() throws Exception {
        BufferedImage image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color((x * 7) % 255, (y * 11) % 255, ((x + y) * 13) % 255).getRGB());
            }
        }

        Img2ThreeReliefGenerator.GeneratedRelief result = generator.generate(
                png(image), "image/png", "https://assets.example.test/complex.png", "complex"
        );

        assertFalse(result.sceneSpec().path("image").path("dataUrl").asText().isBlank());
        assertTrue(result.sceneSpec().path("image").path("foregroundRatio").asDouble() > 0);
        assertTrue(result.sceneSpec().path("relief").path("resolution").asInt() >= 64);
    }

    private byte[] png(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
