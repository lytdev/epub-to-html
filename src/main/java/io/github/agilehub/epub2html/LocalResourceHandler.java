package io.github.agilehub.epub2html;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 将 EPUB 资源保存到本地目录的默认 {@link EpubResourceHandler} 实现。
 *
 * <p>由 {@link EpubConverter#convert(Path, Path, Path)} 创建，也可独立传给自定义转换流程。
 * 输出路径保留 EPUB 内部目录层级，以避免同名资源相互覆盖；同时会拒绝越出目标目录的路径。</p>
 */
public final class LocalResourceHandler implements EpubResourceHandler {
    private final Path directory;
    private final String urlPrefix;

    /**
     * 创建本地资源处理器。
     *
     * @param directory 接收资源的根目录；不存在时在首次处理资源时创建
     * @param urlPrefix 写入 HTML 的 URL 前缀，例如 {@code media}；可为空以直接返回资源相对路径
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     */
    public LocalResourceHandler(Path directory, String urlPrefix) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.urlPrefix = Objects.requireNonNull(urlPrefix, "urlPrefix").replaceAll("/+$", "");
    }

    /**
     * 将资源写入 {@code directory}，并返回由 {@code urlPrefix} 与归档相对路径组成的 URL。
     *
     * @param resource 转换器传入的 EPUB 资源
     * @return 可回写到 HTML 的资源地址
     * @throws NullPointerException 当 {@code resource} 为 {@code null} 时抛出
     * @throws IOException 当路径越界、目录无法创建或文件无法写入时抛出
     */
    @Override
    public String handle(EpubResource resource) throws IOException {
        String relativePath = resource.archivePath().replace(':', '_').replace('\\', '/');
        Path target = directory.resolve(relativePath).normalize();
        if (!target.startsWith(directory)) throw new IOException("Unsafe EPUB resource path: " + resource.archivePath());
        Files.createDirectories(target.getParent());
        Files.write(target, resource.content());
        return urlPrefix.isEmpty() ? relativePath : urlPrefix + "/" + relativePath;
    }
}
