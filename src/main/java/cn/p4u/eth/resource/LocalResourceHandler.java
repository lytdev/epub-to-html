package cn.p4u.eth.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 将 EPUB 资源保存到本地目录的默认 {@link EpubResourceHandler} 实现。
 *
 * <p>由调用方创建并传入 转换器的 convert 方法。
 * 输出路径保留 EPUB 内部目录层级，以避免同名资源相互覆盖；同时会拒绝越出目标目录的路径。</p>
 */
public final class LocalResourceHandler implements EpubResourceHandler {
    /** 本地保存根目录，构造时转为绝对路径，供后续边界比较。 */
    private final Path directory;
    /** 返回给 HTML 的地址前缀，与本地文件系统路径是两个不同概念。 */
    private final String urlPrefix;

    /**
     * 创建本地资源处理器。
     *
     * @param directory 接收资源的根目录；不存在时在首次处理资源时创建
     * @param urlPrefix 写入 HTML 的 URL 前缀，例如 {@code media}；可为空以直接返回资源相对路径
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     */
    public LocalResourceHandler(Path directory, String urlPrefix) {
        // 归一化消除路径中的点段，后续保存时才能用同一标准比较父子路径。
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        // 去掉末尾斜杠，后续统一在前缀和资源路径之间添加一个斜杠。
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
        // 保留归档目录结构，减少不同目录下同名图片的冲突；冒号替换为下划线。
        String relativePath = resource.archivePath().replace(':', '_').replace('\\', '/');
        // 合成并归一化实际落盘路径，检查 .. 等点段是否使路径越过保存根目录。
        Path target = directory.resolve(relativePath).normalize();
        if (!target.startsWith(directory)) throw new IOException("Unsafe EPUB resource path: " + resource.archivePath());
        // 资源可能在多级目录内，先创建父目录，再写入原始字节；已有文件会被覆盖。
        Files.createDirectories(target.getParent());
        Files.write(target, resource.content());
        // HTML 需要可访问 URL，不应直接写入服务器上的绝对文件路径。
        return urlPrefix.isEmpty() ? relativePath : urlPrefix + "/" + relativePath;
    }
}
