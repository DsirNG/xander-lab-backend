package com.xander.lab.knowledgebase.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 分片上传的本地暂存区：一个上传会话一个目录，分片按序号存成独立文件。
 *
 * <p>分片先落本地再合并上传对象存储，是为了让「断线续传」只依赖服务端已收到的分片，
 * 客户端重连后不必重传整个文件。</p>
 *
 * <p>{@code uploadId} 是拼路径的唯一变量，因此必须先过 {@link #UPLOAD_ID} 校验：
 * 不校验就等于让调用方决定往磁盘哪儿写。</p>
 */
@Slf4j
@Component
public class ChunkStagingStore {

    /** 只接受 UUID 形状的会话标识，杜绝 {@code ../} 之类的路径穿越。 */
    private static final Pattern UPLOAD_ID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    private final KnowledgeBaseProperties properties;

    public ChunkStagingStore(KnowledgeBaseProperties properties) {
        this.properties = properties;
    }

    /**
     * 合并结果。
     *
     * @param sizeBytes 合并后的总字节数
     * @param sha256    合并结果的 SHA-256 十六进制小写值，用于与客户端声明的哈希比对
     */
    public record MergeResult(Path file, long sizeBytes, String sha256) {
    }

    /** 写入一个分片。同一分片重复上传直接覆盖，保证幂等。 */
    public void saveChunk(String uploadId, int chunkIndex, InputStream data) throws IOException {
        Path dir = sessionDir(uploadId);
        Files.createDirectories(dir);
        Path target = dir.resolve(chunkName(chunkIndex));
        Path temp = dir.resolve(chunkName(chunkIndex) + ".part");
        try (OutputStream out = Files.newOutputStream(temp)) {
            data.transferTo(out);
        }
        // 先写临时文件再原子改名：中途断开不会留下一个「看起来已完成」的半截分片。
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public long chunkSize(String uploadId, int chunkIndex) throws IOException {
        Path chunk = sessionDir(uploadId).resolve(chunkName(chunkIndex));
        return Files.exists(chunk) ? Files.size(chunk) : -1;
    }

    /** 按序号顺序拼接全部分片，同时算出整文件哈希。缺任何一片都直接失败。 */
    public MergeResult merge(String uploadId, int totalChunks) throws IOException {
        Path dir = sessionDir(uploadId);
        Path merged = dir.resolve("merged.bin");
        MessageDigest digest = sha256();
        long total = 0;
        try (OutputStream raw = Files.newOutputStream(merged);
             DigestOutputStream out = new DigestOutputStream(raw, digest)) {
            for (int index = 0; index < totalChunks; index++) {
                Path chunk = dir.resolve(chunkName(index));
                if (!Files.exists(chunk)) {
                    throw new IOException("分片缺失：" + index);
                }
                total += Files.copy(chunk, out);
            }
        }
        return new MergeResult(merged, total, toHex(digest.digest()));
    }

    public byte[] readMerged(String uploadId) throws IOException {
        return Files.readAllBytes(sessionDir(uploadId).resolve("merged.bin"));
    }

    /** 删掉整个会话目录。完成上传或回收过期会话后调用，失败只记日志。 */
    public void discardQuietly(String uploadId) {
        Path dir;
        try {
            dir = sessionDir(uploadId);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("[知识库] 暂存分片删除失败，path={}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[知识库] 暂存目录清理失败，uploadId={}: {}", uploadId, e.getMessage());
        }
    }

    private Path sessionDir(String uploadId) {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            throw new IllegalArgumentException("非法的上传会话标识");
        }
        return Path.of(properties.getChunkStagingDir()).resolve(uploadId).toAbsolutePath().normalize();
    }

    private String chunkName(int chunkIndex) {
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("非法的分片序号");
        }
        return String.format("%08d.chunk", chunkIndex);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /** 直传（不分片）场景下算整文件哈希，与分片合并走同一套算法。 */
    public static String sha256Hex(byte[] content) {
        return toHex(sha256().digest(content));
    }
}
