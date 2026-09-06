package io.github.agilehub.epub2html;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;


/**
 * EPUB 到单一 HTML 的转换入口。
 *
 * <p>该类面向需要在 Web 页面、内容审核或全文检索场景中消费 EPUB 的应用：它以 OPF spine 作为正文顺序，以 NCX 或 EPUB 3 导航文档生成标题层级，并将外部 CSS
 * 规则写入元素内联样式。 {@link EpubResourceHandler} 是资源存储的扩展点，因而调用方可以统一接入本地文件、对象存储或 Base64
 * 策略。转换器不保存状态；同一实例可被多个请求并发调用。
 */
public final class EpubConverter {

  /**
   * 创建无状态转换器。
   *
   * <p>通常可作为 Spring Bean 单例注入；资源的具体去向由每次 {@code convert} 调用传入的参数决定。
   */
  public EpubConverter() {}

  /**
   * 转换 EPUB，并将媒体资源写入指定本地目录。
   *
   * <p>这是 {@link #convert(Path, Path, EpubResourceHandler)} 的本地文件便捷形式：内部创建 {@link
   * LocalResourceHandler}，再将其返回的相对地址回写至 HTML。需要上传阿里云 OSS、添加鉴权 URL 或嵌入 Base64 时，应改用资源处理器重载。
   *
   * @param epub 输入 EPUB 文件；必须是可读取的 ZIP 格式 EPUB
   * @param outputHtml 生成的单一 HTML 文件路径；其父目录会自动创建
   * @param mediaDirectory 媒体文件输出目录；HTML 中使用该目录的目录名作为资源 URL 前缀
   * @return 转换结果，{@link ConversionResult#mediaDirectory()} 为 {@code mediaDirectory}
   * @throws NullPointerException 当任一参数为 {@code null} 时抛出
   * @throws IOException 当 EPUB 无法读取、结构不完整、HTML 或资源无法写入时抛出
   */
  public ConversionResult convert(Path epub, Path outputHtml, Path mediaDirectory)
      throws IOException {
    // 尽早检查必填参数，避免流程执行到一半才出现难以定位的空指针。
    Objects.requireNonNull(epub, "epub");
    Objects.requireNonNull(outputHtml, "outputHtml");
    Objects.requireNonNull(mediaDirectory, "mediaDirectory");
    createOutputParent(outputHtml);
    Files.createDirectories(mediaDirectory);
    // 便捷重载只使用资源目录名；通常要求 HTML 与资源目录位于同一父目录。
    String urlPrefix = mediaDirectory.getFileName().toString();
    // 本地策略与 OSS/Base64 策略走同一套转换流水线，避免维护两套实现。
    ConversionResult result =
        convert(epub, outputHtml, new LocalResourceHandler(mediaDirectory, urlPrefix));
    // 自定义策略结果不含本地目录，这里补回调用方明确指定的目录。
    return new ConversionResult(
        result.htmlFile(), mediaDirectory, result.copiedMedia(), result.tocEntries());
  }

  /**
   * 转换 EPUB，并将每个不同的媒体资源委托给调用方处理。
   *
   * <p>该方法是完整转换链路的编排入口：读取包描述与目录、按 spine 合并章节、内联 CSS、调用 {@code resourceHandler} 处理图片/音视频等资源，最后写出
   * HTML。处理器返回的非空 URL 会替换 原元素的 {@code src} 或 {@code data} 属性。同一路径的资源在一次转换中只交给处理器一次，
   * 因此适合对接对象存储，避免重复上传。
   *
   * @param epub 输入 EPUB 文件；必须包含有效的 {@code META-INF/container.xml} 与 OPF spine
   * @param outputHtml 生成的单一 HTML 文件路径；其父目录会自动创建
   * @param resourceHandler 资源处理策略；必须同步消费资源字节并返回可用于 HTML 的非空 URL
   * @return 转换结果；使用自定义处理器时 {@link ConversionResult#mediaDirectory()} 为 {@code null}
   * @throws NullPointerException 当任一参数为 {@code null} 时抛出
   * @throws IOException 当 EPUB 解析失败、资源处理器失败或输出文件无法写入时抛出
   */
  public ConversionResult convert(Path epub, Path outputHtml, EpubResourceHandler resourceHandler)
      throws IOException {
    // 尽早检查必填参数，避免流程执行到一半才出现难以定位的空指针。
    Objects.requireNonNull(epub, "epub");
    Objects.requireNonNull(outputHtml, "outputHtml");
    Objects.requireNonNull(resourceHandler, "resourceHandler");
    createOutputParent(outputHtml);

    // 门面不解析文件；把具体处理交给流水线，调用方仍使用原来的 convert API。
    return new ConversionPipeline().convert(epub, outputHtml, resourceHandler);
  }

  /**
   * 为 HTML 输出路径创建父目录。
   *
   * <p>两个公开重载都会调用此方法；仅准备目录，不创建 HTML 文件。
   *
   * @param outputHtml 待写出的 HTML 文件路径
   * @throws IOException 父目录无法创建时抛出
   */
  private static void createOutputParent(Path outputHtml) throws IOException {
    // 转为绝对路径后，单独的 book.html 文件名也能取得实际输出父目录。
    Path parent = outputHtml.toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);
  }

}
