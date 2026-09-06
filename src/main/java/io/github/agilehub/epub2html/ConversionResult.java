package io.github.agilehub.epub2html;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次 EPUB 转换的结果摘要。
 *
 * <p>由 {@link EpubConverter} 的两个 {@code convert} 重载返回，供调用方取得最终 HTML、审计资源处理范围
 * 或记录目录解析情况。使用自定义 {@link EpubResourceHandler} 时，资源去向由调用方决定，
 * {@link #mediaDirectory()} 因而为 {@code null}。</p>
 *
 * @param htmlFile 已生成的单一 HTML 文件
 * @param mediaDirectory 本地资源根目录；仅使用本地目录重载时非 {@code null}
 * @param copiedMedia 已处理的 EPUB 资源归档路径，按首次遇到的顺序排列
 * @param tocEntries 读取到的目录项数量；不代表最终一定插入的标题数量
 */
public record ConversionResult(Path htmlFile, Path mediaDirectory, List<String> copiedMedia, int tocEntries) {
    /**
     * 创建转换结果摘要。
     *
     * <p>通常由 {@link EpubConverter} 返回；调用方自行构造时应保证 {@code copiedMedia} 与实际处理结果一致，
     * 并仅在资源由本地目录处理时提供 {@code mediaDirectory}。</p>
     *
     * @param htmlFile 已生成的单一 HTML 文件
     * @param mediaDirectory 本地资源根目录；没有本地资源目录时为 {@code null}
     * @param copiedMedia 已处理的 EPUB 资源归档路径
     * @param tocEntries 从导航文档读取的目录项数量
     */
    public ConversionResult {
    }

    /**
     * 返回已写出的单一 HTML 文件。
     *
     * @return HTML 文件路径
     */
    public Path htmlFile() {
        return htmlFile;
    }

    /**
     * 返回本地资源根目录。
     *
     * @return 本地目录重载指定的资源目录；自定义资源处理器模式下为 {@code null}
     */
    public Path mediaDirectory() {
        return mediaDirectory;
    }

    /**
     * 返回本次已委托处理的资源路径。
     *
     * @return 按首次出现顺序排列的 EPUB 归档路径列表
     */
    public List<String> copiedMedia() {
        return copiedMedia;
    }

    /**
     * 返回从 EPUB 导航文档读取的目录项数量。
     *
     * @return 目录项数量
     */
    public int tocEntries() {
        return tocEntries;
    }
}
