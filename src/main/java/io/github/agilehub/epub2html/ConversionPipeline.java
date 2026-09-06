package io.github.agilehub.epub2html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

/**
 * 编排单次转换的章节流水线：样式、标题、媒体、合并。
 *
 * <p>归档、HTML 文档及资源缓存均限定在一次调用内，不向门面或处理组件泄漏可变状态。
 */
final class ConversionPipeline {
  /**
   * 执行一次完整转换，并返回输出位置与资源处理摘要。
   *
   * <p>由 {@link EpubConverter} 调用。先读取书籍结构，再逐章处理，全部章节完成后才写出 HTML；发生异常时归档会关闭，但已保存或上传的媒体不会自动回滚。
   *
   * @param epub 输入 EPUB 的本地文件路径
   * @param outputHtml 输出 HTML 路径；父目录由门面提前创建
   * @param handler 调用方提供的同步资源策略，其返回 URL 用于替换原资源引用
   * @return 转换结果；资源目录为 null，资源清单保存去重后的归档路径
   * @throws IOException 归档打开、XML/条目读取、资源处理或 HTML 写出失败时抛出
   */
  ConversionResult convert(Path epub, Path outputHtml, EpubResourceHandler handler)
      throws IOException {
    // try-with-resources：正常结束或抛出异常时，都自动关闭 EPUB 文件句柄。
    try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
      // EPUB 是 ZIP；先由固定容器入口定位 OPF，再读取书籍的文件清单和阅读顺序。
      PackageData pkg = PackageReader.readPackage(zip, PackageReader.packagePath(zip));
      // 导航文件提供“标题文字、目标位置、层级”，它与正文阅读顺序是两份信息。
      List<TocItem> toc = NavigationReader.readToc(zip, pkg);
      Map<String, List<TocItem>> targets = indexTargets(toc);
      // 缓存放在章节循环之外、方法之内：本书各章共享，不同转换之间互不影响。
      Map<String, String> resourceUrls = new LinkedHashMap<>();
      // 一次转换使用一个输出文档，已处理章节不断追加到它的 body 中。
      HtmlAssembler output = new HtmlAssembler();
      // spine 定义阅读顺序，不能用 ZIP 条目顺序或文件名排序替代。
      for (String chapter : pkg.spine()) {
        Document document = readChapter(zip, chapter);
        processChapter(zip, document, chapter, targets, handler, resourceUrls);
        // 将处理完的节点移动到结果文档，后续不再修改原章节。
        output.append(chapter, document);
      }
      // 只有所有章节完成后才发布 HTML；此前已处理的媒体可能已经落盘或上传。
      output.write(outputHtml);
      // List.copyOf 固定资源清单；toc.size 是解析条目数，不等于成功插入的标题数。
      return new ConversionResult(outputHtml, null, List.copyOf(resourceUrls.keySet()), toc.size());
    }
  }

  /**
   * 按导航目标分组目录项，供标题处理器查找。
   *
   * <p>LinkedHashMap 保留目标首次出现顺序；同一目标可有多个目录标签，所以值使用列表。
   *
   * @param toc 导航读取器返回的目录项列表
   * @return 目标路径到目录项列表的映射；空目录得到空映射
   */
  private Map<String, List<TocItem>> indexTargets(List<TocItem> toc) {
    Map<String, List<TocItem>> targets = new LinkedHashMap<>();
    for (TocItem item : toc) {
      // 目标第一次出现时建立列表；已有目标则直接取出原列表，再追加目录项。
      targets.computeIfAbsent(item.target(), ignored -> new ArrayList<>()).add(item);
    }
    return targets;
  }

  /**
   * 将归档中的 XHTML 文本解析为可修改的节点树。
   *
   * <p>DOM 可以理解为标签构成的树；后续处理器直接在这棵树上修改样式、标题和资源属性。
   *
   * @param zip 当前调用已打开的 EPUB 归档
   * @param chapter 章节在 ZIP 内的路径，同时作为解析器的基准地址
   * @return 由 Jsoup XML 解析器创建的章节文档
   * @throws IOException 章节条目不存在或读取失败时抛出
   */
  private Document readChapter(ZipFile zip, String chapter) throws IOException {
    // 先验证章节存在，再读文本；XML 解析模式适配 EPUB 中的 XHTML。
    return Jsoup.parse(
        EpubArchive.read(zip, EpubArchive.requiredEntry(zip, chapter)),
        chapter,
        Parser.xmlParser());
  }

  /**
   * 按样式、标题、媒体的固定顺序修改章节文档。
   *
   * <p>这是原地修改方法，没有返回值；处理后的 document 随后由 {@link HtmlAssembler#append(String, Document)} 接收。新插入的标题不再经过前面的样式处理步骤。
   *
   * @param zip 当前 EPUB 归档，供 CSS 和媒体读取使用
   * @param document 待修改的章节节点树
   * @param chapter 当前章节归档路径，用于解析相对引用和筛选目录
   * @param targets 按目标分组的整本书目录项
   * @param handler 媒体存储或编码策略
   * @param resourceUrls 整次转换共用的归档路径到结果 URL 缓存
   * @throws IOException 样式读取或资源处理失败时抛出
   */
  private void processChapter(
      ZipFile zip,
      Document document,
      String chapter,
      Map<String, List<TocItem>> targets,
      EpubResourceHandler handler,
      // 缓存放在章节循环之外、方法之内：本书各章共享，不同转换之间互不影响。
      Map<String, String> resourceUrls)
      throws IOException {
    // 先匹配原始章节节点上的 CSS，避免新增标题干扰原有选择器匹配。
    StyleProcessor.inlineStyles(zip, document, chapter);
    // 按目录目标找锚点，将层级标题放到对应正文之前。
    HeadingProcessor.injectTocHeadings(document, chapter, targets);
    // 资源策略返回最终访问地址；随后组装器只需合并已完成修改的节点。
    MediaProcessor.rewriteMedia(zip, document, chapter, handler, resourceUrls);
  }
}
