package io.github.agilehub.epub2html;

import java.io.IOException;

/**
 * EPUB 资源的统一处理策略。
 *
 * <p>{@link EpubConverter} 在合并正文时调用本接口，并将返回值回写至对应 HTML 元素。Spring Boot
 * 调用方可实现为本地文件存储、阿里云 OSS 上传或 Base64 内嵌；实现必须在本次调用中完成资源消费，且不得返回空 URL。</p>
 */
@FunctionalInterface
public interface EpubResourceHandler {
    /**
     * 存储或转换一个 EPUB 资源，并返回写入 HTML 属性的最终地址。
     *
     * @param resource 转换器读取的资源；包含路径、MIME 类型和完整字节内容
     * @return 非空 URL，可为相对地址、OSS/CDN 地址或 {@code data:} URL
     * @throws IOException 当资源无法保存、上传或编码时抛出；异常会终止当前 EPUB 转换
     */
    String handle(EpubResource resource) throws IOException;
}
