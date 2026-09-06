package io.github.agilehub.epub2html;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 资源处理器接收的 EPUB 二进制资源。
 *
 * <p>由 {@link EpubConverter} 在扫描章节媒体元素时创建，并传递给
 * {@link EpubResourceHandler#handle(EpubResource)}。调用方可依据资源路径生成对象存储 key，依据 MIME
 * 类型设置上传 Content-Type，或将字节转为 Base64。资源字节仅适合在该次同步回调内使用；异步处理器应自行复制。</p>
 *
 * @param archivePath 资源在 EPUB ZIP 中的规范化相对路径
 * @param mediaType 根据文件名推断的 MIME 类型；无法识别时为 {@code application/octet-stream}
 * @param content 资源完整二进制内容
 */
public record EpubResource(String archivePath, String mediaType, byte[] content) {
    /**
     * 创建资源描述。
     *
     * @param archivePath 资源在 EPUB ZIP 中的规范化相对路径
     * @param mediaType 资源 MIME 类型
     * @param content 资源完整二进制内容
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     */
    public EpubResource {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(content, "content");
    }

    /**
     * 返回资源在 EPUB 归档中的路径；可用于构造稳定的对象存储 key。
     *
     * @return 资源归档相对路径
     */
    public String archivePath() {
        return archivePath;
    }

    /**
     * 返回资源 MIME 类型；用于决定 HTML {@code data:} 前缀或上传请求的 Content-Type。
     *
     * @return 推断的 MIME 类型
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * 返回资源字节，供资源处理器在同步调用中保存、上传或编码。
     *
     * @return 资源完整二进制内容
     */
    public byte[] content() {
        return content;
    }

    /**
     * 返回从 {@link #archivePath()} 提取的文件名，便于作为上传对象名或本地文件名。
     *
     * @return 不含目录的原始文件名
     */
    public String fileName() {
        return Path.of(archivePath).getFileName().toString();
    }
}
