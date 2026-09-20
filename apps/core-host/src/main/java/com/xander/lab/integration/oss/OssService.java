package com.xander.lab.integration.oss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.xander.lab.integration.api.OssPort;
import com.xander.lab.integration.api.UploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * 阿里云 OSS 服务
 * 对应前端 axios 封装中的 upload() 方法
 *
 * <p>它是 {@link OssPort} 的**实现**，端口本身在 {@code contracts/integration-api}。
 * 这样 agent / knowledgebase / publishing / recitation 四个域只依赖接口，
 * 将来本服务被拆成独立进程时，换成 HTTP 客户端实现即可，调用方代码不用动。</p>
 */
@Slf4j
@Service
public class OssService implements OssPort {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    @Value("${aliyun.oss.domain}")
    private String domain;

    /** OSS 客户端按需创建并复用，避免每次上传重建连接。 */
    private volatile OSS ossClient;

    private OSS client() {
        OSS local = ossClient;
        if (local == null) {
            synchronized (this) {
                local = ossClient;
                if (local == null) {
                    ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
                    configuration.setConnectionTimeout(10_000);
                    configuration.setSocketTimeout(30_000);
                    configuration.setMaxErrorRetry(2);
                    local = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret, configuration);
                    ossClient = local;
                }
            }
        }
        return local;
    }

    /**
     * 上传一个 Spring 上传对象，**本域内**的便捷重载。
     *
     * <p>刻意不放进 {@link OssPort}：{@code MultipartFile} 是 spring-web 类型，
     * 放进契约会让 spring-web 出现在每个依赖契约的服务（含 WebFlux Gateway）的
     * classpath 上，而且跨进程时没有 {@code MultipartFile} 可传。
     * 只有本域的 {@code FileController} 用它；跨域调用方走
     * {@link #upload(byte[], String, String, String)}。</p>
     *
     * <p>与端口方法的一个真实差别：这里把 {@code MultipartFile} 的输入流直接交给 SDK，
     * 不把文件读进内存；端口方法收的是 {@code byte[]}，天然是全量的。</p>
     *
     * @param file       文件对象
     * @param pathPrefix 路径前缀（如 avatars/）
     * @return 上传结果
     */
    public UploadResponse upload(MultipartFile file, String pathPrefix) {
        try {
            String originalName = file.getOriginalFilename();
            String extension = "";
            if (originalName != null && originalName.contains(".")) {
                extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
            }

            String storedName = UUID.randomUUID().toString() + (extension.isEmpty() ? "" : "." + extension);
            String objectName = pathPrefix + storedName;

            InputStream inputStream = file.getInputStream();
            client().putObject(bucketName, objectName, inputStream);

            String fileUrl = domain + "/" + objectName;

            log.info("[OSS] 文件上传成功: {}", fileUrl);

            return new UploadResponse(fileUrl, originalName, storedName,
                    file.getSize(), file.getContentType(), extension);
        } catch (Exception e) {
            log.error("[OSS] 文件上传异常: {}", e.getMessage());
            throw new RuntimeException("文件上传至云存储失败", e);
        }
    }

    @Override
    public UploadResponse upload(byte[] content, String originalName, String contentType, String pathPrefix) {
        try {
            String extension = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                    : "png";
            String storedName = UUID.randomUUID() + "." + extension;
            String objectName = pathPrefix + storedName;
            try (InputStream inputStream = new ByteArrayInputStream(content)) {
                client().putObject(bucketName, objectName, inputStream);
            }
            return new UploadResponse(domain + "/" + objectName, originalName, storedName,
                    (long) content.length, contentType, extension);
        } catch (Exception e) {
            // 措辞保持中立：这条端口方法服务 4 个域（智能体附件、知识库文件、博客媒体、
            // 背诵音频），写死"智能体图片"会让其余三处失败时的日志指向错误的对象。
            log.error("[OSS] 内容上传失败: {}", e.getMessage());
            throw new IllegalStateException("文件上传至云存储失败", e);
        }
    }

    /**
     * 按访问地址尽力删除一个对象，失败只记日志。
     *
     * <p>只删地址前缀等于本 bucket 配置域名的对象：地址是从库里读出来的，
     * 不校验前缀就直接截路径的话，一条被改写过的记录就能让我们去删别处的 key。</p>
     *
     * @return 是否确实完成了删除
     */
    @Override
    public boolean deleteByUrlQuietly(String url) {
        String prefix = domain + "/";
        if (url == null || !url.startsWith(prefix)) {
            log.warn("[OSS] 跳过删除：地址不属于当前存储域名");
            return false;
        }
        String objectName = url.substring(prefix.length());
        if (objectName.isBlank()) return false;
        try {
            client().deleteObject(bucketName, objectName);
            return true;
        } catch (Exception e) {
            // 删不掉的对象交给存储侧生命周期规则回收，不能让它阻断调用方的业务删除。
            log.warn("[OSS] 对象删除失败，objectName={}: {}", objectName, e.getMessage());
            return false;
        }
    }
}
