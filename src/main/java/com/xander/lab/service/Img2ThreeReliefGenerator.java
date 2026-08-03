package com.xander.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

/**
 * Creates a deterministic, texture-preserving 2.5D mesh contract from one image.
 *
 * <p>This is the reliable baseline for Img2Three: it does not depend on a remote
 * vision model or on executing generated TypeScript. The browser turns the
 * transparent foreground texture into a front surface, back surface and boundary
 * walls, so the result is a real exportable mesh instead of a billboard.</p>
 */
@Component
@RequiredArgsConstructor
public class Img2ThreeReliefGenerator {
    static final String SCHEMA_VERSION = "xander-image-relief/1";
    private static final int MAX_IMAGE_PIXELS = 40_000_000;
    private static final int MAX_TEXTURE_EDGE = 640;
    private static final int MAX_EMBEDDED_SOURCE_BYTES = 10 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public GeneratedRelief generate(byte[] imageBytes,
                                    String declaredContentType,
                                    String sourceUrl,
                                    String requestedTitle) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("参考图片为空");
        }

        String contentType = detectContentType(imageBytes, declaredContentType);
        BufferedImage decoded = decodeBounded(imageBytes);
        ProcessedImage processed = decoded == null ? null : process(decoded);
        String title = normalizeTitle(requestedTitle);

        String imageSource;
        int width;
        int height;
        boolean backgroundRemoved;
        double foregroundRatio;
        int dominantColor;

        if (processed != null) {
            imageSource = toDataUrl("image/png", processed.pngBytes());
            width = processed.width();
            height = processed.height();
            backgroundRemoved = processed.backgroundRemoved();
            foregroundRatio = processed.foregroundRatio();
            dominantColor = processed.dominantColor();
        } else {
            imageSource = imageBytes.length <= MAX_EMBEDDED_SOURCE_BYTES
                    ? toDataUrl(contentType, imageBytes)
                    : sourceUrl;
            if (!StringUtils.hasText(imageSource)) {
                throw new IllegalArgumentException("图片格式无法解码，且没有可读取的图片地址");
            }
            width = 0;
            height = 0;
            backgroundRemoved = false;
            foregroundRatio = 1.0;
            dominantColor = 0x8a8f96;
        }

        double aspect = width > 0 && height > 0 ? (double) width / height : 1.0;
        double modelWidth = aspect >= 1.0 ? 2.4 : 2.4 * aspect;
        double modelHeight = aspect >= 1.0 ? 2.4 / aspect : 2.4;
        double depth = Math.max(0.08, Math.min(0.22, Math.min(modelWidth, modelHeight) * 0.11));
        int resolution = width > 0 && height > 0
                ? Math.max(64, Math.min(176, Math.max(width, height) / 4))
                : 128;

        ObjectNode sceneSpec = objectMapper.createObjectNode();
        sceneSpec.put("schemaVersion", SCHEMA_VERSION);
        sceneSpec.put("kind", "image-relief");
        sceneSpec.put("title", title);
        sceneSpec.put("targetName", title);
        sceneSpec.put("generator", "img2threejs-compatible-deterministic-relief");

        ObjectNode image = sceneSpec.putObject("image");
        image.put("dataUrl", imageSource);
        image.put("sourceUrl", StringUtils.hasText(sourceUrl) ? sourceUrl : "");
        image.put("contentType", processed == null ? contentType : "image/png");
        image.put("width", width);
        image.put("height", height);
        image.put("aspectRatio", aspect);
        image.put("backgroundRemoved", backgroundRemoved);
        image.put("foregroundRatio", round(foregroundRatio, 4));

        ObjectNode relief = sceneSpec.putObject("relief");
        relief.put("resolution", resolution);
        relief.put("width", round(modelWidth, 4));
        relief.put("height", round(modelHeight, 4));
        relief.put("depth", round(depth, 4));
        relief.put("heightScale", round(depth * 0.22, 4));
        relief.put("alphaThreshold", 0.06);
        relief.put("edgeSoftness", 0.04);

        ObjectNode material = sceneSpec.putObject("material");
        material.put("baseColor", String.format(Locale.ROOT, "#%06X", dominantColor));
        material.put("metalness", 0.18);
        material.put("roughness", 0.48);
        material.put("sideColor", darken(dominantColor, 0.48));

        ObjectNode camera = sceneSpec.putObject("camera");
        camera.putArray("position").add(2.6).add(1.8).add(3.3);
        camera.putArray("target").add(0).add(0).add(0);
        camera.put("fov", 34);

        ObjectNode analysis = objectMapper.createObjectNode();
        analysis.put("mode", "deterministic-image-relief");
        analysis.put("usable", true);
        analysis.put("sourceWidth", decoded == null ? 0 : decoded.getWidth());
        analysis.put("sourceHeight", decoded == null ? 0 : decoded.getHeight());
        analysis.put("textureWidth", width);
        analysis.put("textureHeight", height);
        analysis.put("backgroundRemoved", backgroundRemoved);
        analysis.put("foregroundRatio", round(foregroundRatio, 4));
        analysis.put("frontViewFidelity", "texture-preserving");
        analysis.putArray("limitations")
                .add("单张图片看不到的背面和遮挡区域采用统一厚度推断")
                .add("这是可导出的单视图浮雕网格，不等同于多视角摄影测量");

        return new GeneratedRelief(
                title,
                analysis,
                sceneSpec,
                createFactoryCode(
                        title,
                        StringUtils.hasText(sourceUrl) ? sourceUrl : imageSource,
                        modelWidth,
                        modelHeight,
                        depth
                )
        );
    }

    private BufferedImage decodeBounded(byte[] imageBytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 2 || height < 2 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IllegalArgumentException("图片尺寸无效或像素数量过大");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProcessedImage process(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int background = estimateBorderColor(source);
        double borderSpread = estimateBorderSpread(source, background);
        double threshold = Math.max(20.0, Math.min(62.0, 22.0 + borderSpread * 1.8));

        BufferedImage cutout = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        long foreground = 0;
        long red = 0;
        long green = 0;
        long blue = 0;

        boolean sourceTransparency = hasMeaningfulTransparency(source);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getRGB(x, y);
                int sourceAlpha = (argb >>> 24) & 0xff;
                int alpha;
                if (sourceTransparency) {
                    alpha = sourceAlpha;
                } else {
                    double distance = colorDistance(argb, background);
                    double coverage = smoothStep(threshold, threshold + 34.0, distance);
                    alpha = (int) Math.round(sourceAlpha * coverage);
                }
                int output = (alpha << 24) | (argb & 0x00ffffff);
                cutout.setRGB(x, y, output);
                if (alpha > 20) {
                    foreground++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    red += (argb >>> 16) & 0xff;
                    green += (argb >>> 8) & 0xff;
                    blue += argb & 0xff;
                }
            }
        }

        double ratio = foreground / (double) ((long) width * height);
        boolean removed = sourceTransparency || (ratio >= 0.008 && ratio <= 0.92);
        if (!removed) {
            cutout = copyAsArgb(source);
            minX = 0;
            minY = 0;
            maxX = width - 1;
            maxY = height - 1;
            ratio = 1.0;
            foreground = (long) width * height;
            red = green = blue = 0;
            int step = Math.max(1, Math.max(width, height) / 320);
            long samples = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int rgb = source.getRGB(x, y);
                    red += (rgb >>> 16) & 0xff;
                    green += (rgb >>> 8) & 0xff;
                    blue += rgb & 0xff;
                    samples++;
                }
            }
            foreground = Math.max(1, samples);
        }

        int margin = Math.max(2, Math.round(Math.max(maxX - minX + 1, maxY - minY + 1) * 0.025f));
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(width - 1, maxX + margin);
        maxY = Math.min(height - 1, maxY + margin);
        BufferedImage cropped = cutout.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        BufferedImage scaled = scaleDown(cropped, MAX_TEXTURE_EDGE);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(scaled, "png", output)) {
                throw new IllegalStateException("无法编码模型纹理");
            }
            int dominant = foreground <= 0
                    ? 0x8a8f96
                    : (((int) (red / foreground)) << 16)
                    | (((int) (green / foreground)) << 8)
                    | (int) (blue / foreground);
            return new ProcessedImage(
                    output.toByteArray(),
                    scaled.getWidth(),
                    scaled.getHeight(),
                    removed,
                    ratio,
                    dominant
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法生成模型纹理：" + e.getMessage(), e);
        }
    }

    private int estimateBorderColor(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int step = Math.max(1, (image.getWidth() + image.getHeight()) / 800);
        for (int x = 0; x < image.getWidth(); x += step) {
            int[] pair = {image.getRGB(x, 0), image.getRGB(x, image.getHeight() - 1)};
            for (int argb : pair) {
                if (((argb >>> 24) & 0xff) < 32) continue;
                red += (argb >>> 16) & 0xff;
                green += (argb >>> 8) & 0xff;
                blue += argb & 0xff;
                count++;
            }
        }
        for (int y = 0; y < image.getHeight(); y += step) {
            int[] pair = {image.getRGB(0, y), image.getRGB(image.getWidth() - 1, y)};
            for (int argb : pair) {
                if (((argb >>> 24) & 0xff) < 32) continue;
                red += (argb >>> 16) & 0xff;
                green += (argb >>> 8) & 0xff;
                blue += argb & 0xff;
                count++;
            }
        }
        if (count == 0) return 0xffffff;
        return (((int) (red / count)) << 16)
                | (((int) (green / count)) << 8)
                | (int) (blue / count);
    }

    private double estimateBorderSpread(BufferedImage image, int background) {
        double total = 0;
        long count = 0;
        int step = Math.max(1, (image.getWidth() + image.getHeight()) / 400);
        for (int x = 0; x < image.getWidth(); x += step) {
            total += colorDistance(image.getRGB(x, 0), background);
            total += colorDistance(image.getRGB(x, image.getHeight() - 1), background);
            count += 2;
        }
        for (int y = 0; y < image.getHeight(); y += step) {
            total += colorDistance(image.getRGB(0, y), background);
            total += colorDistance(image.getRGB(image.getWidth() - 1, y), background);
            count += 2;
        }
        return count == 0 ? 0 : total / count;
    }

    private boolean hasMeaningfulTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return false;
        int step = Math.max(1, Math.max(image.getWidth(), image.getHeight()) / 256);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) < 245) return true;
            }
        }
        return false;
    }

    private BufferedImage copyAsArgb(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private BufferedImage scaleDown(BufferedImage source, int maxEdge) {
        int currentMax = Math.max(source.getWidth(), source.getHeight());
        if (currentMax <= maxEdge) return copyAsArgb(source);
        double scale = maxEdge / (double) currentMax;
        int width = Math.max(2, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(2, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private double colorDistance(int left, int right) {
        int dr = ((left >>> 16) & 0xff) - ((right >>> 16) & 0xff);
        int dg = ((left >>> 8) & 0xff) - ((right >>> 8) & 0xff);
        int db = (left & 0xff) - (right & 0xff);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private double smoothStep(double low, double high, double value) {
        if (value <= low) return 0;
        if (value >= high) return 1;
        double t = (value - low) / (high - low);
        return t * t * (3.0 - 2.0 * t);
    }

    private String detectContentType(byte[] bytes, String declared) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') return "image/webp";
        return StringUtils.hasText(declared) && declared.toLowerCase(Locale.ROOT).startsWith("image/")
                ? declared.toLowerCase(Locale.ROOT)
                : "image/jpeg";
    }

    private String toDataUrl(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String normalizeTitle(String value) {
        String title = StringUtils.hasText(value) ? value.trim() : "Image relief model";
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    private String createFactoryCode(String title, String imageSource, double width, double height, double depth) {
        try {
            String titleLiteral = objectMapper.writeValueAsString(title);
            String imageLiteral = objectMapper.writeValueAsString(imageSource);
            return """
                    import * as THREE from 'three';

                    /** Deterministic single-image relief generated by Xander Lab. */
                    export function createModel(): THREE.Group {
                      const root = new THREE.Group();
                      root.name = %s;
                      const material = new THREE.MeshStandardMaterial({
                        color: 0xffffff,
                        metalness: 0.18,
                        roughness: 0.48,
                        transparent: true,
                        alphaTest: 0.06,
                        side: THREE.DoubleSide,
                      });
                      new THREE.TextureLoader().load(%s, (texture) => {
                        texture.colorSpace = THREE.SRGBColorSpace;
                        material.map = texture;
                        material.needsUpdate = true;
                      });
                      const front = new THREE.Mesh(
                        new THREE.PlaneGeometry(%.4f, %.4f, 128, 128),
                        material,
                      );
                      front.position.z = %.4f;
                      root.add(front);
                      return root;
                    }
                    """.formatted(titleLiteral, imageLiteral, width, height, depth / 2.0);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Three.js 工厂", e);
        }
    }

    private String darken(int color, double factor) {
        int red = (int) Math.round(((color >>> 16) & 0xff) * factor);
        int green = (int) Math.round(((color >>> 8) & 0xff) * factor);
        int blue = (int) Math.round((color & 0xff) * factor);
        return String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue);
    }

    private double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    public record GeneratedRelief(
            String title,
            ObjectNode analysis,
            ObjectNode sceneSpec,
            String factoryCode
    ) {
    }

    private record ProcessedImage(
            byte[] pngBytes,
            int width,
            int height,
            boolean backgroundRemoved,
            double foregroundRatio,
            int dominantColor
    ) {
    }
}
