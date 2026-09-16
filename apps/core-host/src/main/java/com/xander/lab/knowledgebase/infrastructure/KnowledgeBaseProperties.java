package com.xander.lab.knowledgebase.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库文件中心的容量限制与结构图生成策略。
 *
 * <p>「自动 vs 按需」的分界线全在这里：章节数超过 {@code autoStructureChapterThreshold}
 * 或正文长度超过 {@code autoStructureMaxChars} 的大部头，上传时不整本出图，只发通知，
 * 等用户点进某一章再生成那一章。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge-base")
public class KnowledgeBaseProperties {

    /** 文件夹最大层数，根目录下第一层记为 1。 */
    private int maxFolderDepth = 8;
    /** 同一个用户最多建多少个文件夹。 */
    private int maxFolders = 2000;
    /** 单文件字节上限，默认 50MB。 */
    private long maxFileBytes = 50L * 1024 * 1024;
    /** 允许上传的扩展名（小写，不含点）。空列表表示不限制。 */
    private List<String> allowedExtensions = List.of(
            "txt", "md", "markdown", "html", "htm", "csv", "json", "log", "pdf", "doc", "docx");
    /** 能抽出纯文本、进而识别章节的扩展名；其余落 UNSUPPORTED，只存文件不出结构图。 */
    private List<String> textExtensions = List.of(
            "txt", "md", "markdown", "html", "htm", "csv", "json", "log");
    /** 抽取正文的字符上限，超出截断，防止超大文本把内存和 LONGTEXT 撑爆。 */
    private int maxExtractChars = 2_000_000;
    /** 章节识别条数上限。 */
    private int maxChapters = 2000;

    /** 章节数超过该阈值就不在上传时整本出图，改为按章按需生成。 */
    private int autoStructureChapterThreshold = 12;
    /** 正文长度超过该阈值同样转为按需生成。 */
    private int autoStructureMaxChars = 200_000;
    /** 单个结构节点下最多挂几个知识点。 */
    private int maxPointsPerNode = 8;
    /** 结构节点摘要截断长度。 */
    private int summaryLength = 200;
    /** 自动标签最多生成几个。 */
    private int maxAutoTags = 5;
    /** 单个用户的标签数量上限。 */
    private int maxTags = 500;

    /** 分片暂存目录。合并完成或会话回收后即删除。 */
    private String chunkStagingDir = "data/knowledge-base/chunks";
    /** 分片大小下限，默认 256KB，防止客户端拆出海量小分片。 */
    private int minChunkSize = 256 * 1024;
    /** 分片大小上限，默认 16MB。 */
    private int maxChunkSize = 16 * 1024 * 1024;
    /** 上传会话有效期（小时），过期后不再接受分片。 */
    private int sessionTtlHours = 24;
    /** 过期会话回收开关。涉及删本地暂存文件，默认关闭，由部署方显式打开。 */
    private boolean reaperEnabled = false;
    /** 单轮回收处理的会话条数上限。 */
    private int reaperBatchSize = 50;
}
