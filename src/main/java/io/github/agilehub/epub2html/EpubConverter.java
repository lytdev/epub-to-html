package io.github.agilehub.epub2html;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 将 EPUB 解析为已填充章节内容的目录树，不合并或保存完整 HTML。
 *
 * <p>节点 content 是不额外插入目录标题的正文 HTML 片段，标题独立保存在 label，子章节通过 children 独立保存。
 * 调用方负责展示、合并或持久化；资源存储由 {@link EpubResourceHandler} 决定。
 * 本类无状态，可作为 Spring 单例；并发使用的资源处理器需自行保证线程安全。
 */
public final class EpubConverter {
  /** 创建无状态入口，转换状态仅在一次调用内有效。 */
  public EpubConverter() {}

  /**
   * 读取 EPUB 文件，返回可脱离归档独立使用的章节树。
   *
   * <p>流水线负责目录解析、内容切分、样式处理及资源替换。
   * 有目录时按目录目标读取，无目录时按 spine 创建无标题节点。
   * 不创建 HTML 文件；媒体是否落盘由调用方的资源策略决定。
   *
   * @param epub 可读取的 ZIP 格式 EPUB 文件路径
   * @param resourceHandler 同步资源策略，须返回非空白 URL；一次转换内同路径资源只处理一次
   * @return 已填充 content 的根节点列表，保留目录顺序；列表和节点可修改，叶子 children 为空列表
   * @throws NullPointerException 任一参数为 null 时抛出
   * @throws IOException 归档读取、目录目标解析或资源处理失败时抛出；已处理资源不自动回滚
   */
  public List<TocItem> convert(Path epub, EpubResourceHandler resourceHandler) throws IOException {
    return convert(epub, resourceHandler, false);
  }

  /**
   * 读取 EPUB 文件，并选择是否清除正文元素的 class 和 id。
   *
   * <p>清理在章节定位、样式内联、资源处理完成后执行，保留 style 及资源地址。
   * 不修改目录节点的 target；删除 id 会使正文锚点链接及部分 SVG 引用失效。
   *
   * @param epub 可读取的 EPUB 文件路径
   * @param resourceHandler 同步资源策略，须返回非空白 URL
   * @param isRemoveClass true 时同时删除正文所有元素的 class 和 id；默认重载使用 false
   * @return 已填充 content 的章节树，子章节独立保存在 children 中
   * @throws NullPointerException epub 或 resourceHandler 为 null 时抛出
   * @throws IOException EPUB 读取、解析或资源处理失败时抛出
   */
  public List<TocItem> convert(
      Path epub, EpubResourceHandler resourceHandler, boolean isRemoveClass) throws IOException {
    Objects.requireNonNull(epub, "epub");
    Objects.requireNonNull(resourceHandler, "resourceHandler");
    // 在返回前读取全部内容并关闭 ZIP，返回值不依赖打开的归档。
    return new ConversionPipeline().convert(epub, resourceHandler, isRemoveClass);
  }

  /**
   * 从输入流读取 EPUB 并返回章节树，适用于 Spring Boot 上传场景。
   *
   * <p>从流当前位置读取至结束，不要求 mark/reset，不关闭调用方输入流。
   * 内部暂存 EPUB 以支持 ZIP 随机访问，并在成功或失败时清理临时文件。
   * 不产生完整 HTML 字符串或 HTML 文件；已保存或上传的资源不自动回滚。
   *
   * @param epubInput EPUB 输入流，由调用方负责关闭
   * @param resourceHandler 同步资源策略，须返回非空白 URL
   * @return 与 {@link #convert(Path, EpubResourceHandler)} 相同的已填充内容的可变章节树
   * @throws NullPointerException 任一参数为 null 时抛出
   * @throws IOException 流读取、临时文件创建或清理、EPUB 解析或资源处理失败时抛出
   */
  public List<TocItem> convert(InputStream epubInput, EpubResourceHandler resourceHandler)
      throws IOException {
    return convert(epubInput, resourceHandler, false);
  }

  /**
   * 从输入流读取章节树，并选择是否在内容处理结束后清除 class 和 id。
   *
   * <p>输入流由调用方关闭，内部临时归档在结束时清理。
   * 清理语义与 {@link #convert(Path, EpubResourceHandler, boolean)} 一致，可能破坏锚点和 SVG 引用。
   *
   * @param epubInput EPUB 输入流，从当前位置读取至结束
   * @param resourceHandler 同步资源策略，须返回非空白 URL
   * @param removeClasses true 时同时删除正文所有元素的 class 和 id；默认重载使用 false
   * @return 已填充内容的章节树，不包含完整 HTML 文档外壳
   * @throws NullPointerException epubInput 或 resourceHandler 为 null 时抛出
   * @throws IOException 流读取、临时文件创建或清理、EPUB 解析或资源处理失败时抛出
   */
  public List<TocItem> convert(
      InputStream epubInput, EpubResourceHandler resourceHandler, boolean removeClasses)
      throws IOException {
    Objects.requireNonNull(epubInput, "epubInput");
    Objects.requireNonNull(resourceHandler, "resourceHandler");
    // 只管理内部临时归档；调用者仍拥有输入流。
    try (TemporaryEpub epub = TemporaryEpub.copyOf(epubInput)) {
      return convert(epub.path(), resourceHandler, removeClasses);
    }
  }
}
