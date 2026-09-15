package cn.p4u.eth.internal.content;

import cn.p4u.eth.resource.EpubResourceHandler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static cn.p4u.eth.internal.archive.EpubPaths.resolve;
import static cn.p4u.eth.internal.archive.EpubPaths.isExternal;

/**
 * 媒体处理器，连接 EPUB 内的资源引用和宿主应用提供的存储策略。
 *
 * <p>本类只识别 DOM 属性并解析相对路径；资源读取和去重委托给 {@link ResourceResolver}。
 * 存储方式通过 {@link EpubResourceHandler} 策略替换，不影响 DOM 遍历。
 */
final class MediaProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private MediaProcessor() {}

  /** 普通 HTML 媒体标签；SVG image 的引用由 resourceAttributes 单独选择。 */
  private static final Set<String> MEDIA_TAGS =
      Set.of("img", "audio", "video", "source", "track", "object", "embed");

  /**
   * 将章节的本地媒体引用交给资源策略处理，并用结果 URL 回写属性。
   *
   * <p>按归档路径去重，缓存由流水线跨章节共享。支持 HTML src/data 和 SVG image 的 href/xlink:href。
   * SVG 两个引用同时存在时分别处理，共用资源缓存。缺失条目直接跳过，不处理 poster、srcset、CSS URL 等。
   *
   * @param doc 待修改的章节文档
   * @param documentPath 当前章节归档路径，用于解析媒体相对地址
   * @param resources 本次转换共享的资源读取与缓存会话
   * @throws IOException 媒体读取失败、处理器失败或返回 null/空白 URL 时抛出
   * @throws IllegalArgumentException 媒体属性不能解析为合法 URI 时可能抛出
   */
  static void rewriteMedia(Document doc, String documentPath, ResourceResolver resources)
      throws IOException {
    // 扫描章节 DOM，普通正文标签不需要进入资源处理流程。
    for (Element element : doc.getAllElements()) {
      for (String attribute : resourceAttributes(element)) {
        rewriteReference(element, attribute, documentPath, resources);
      }
    }
  }

  /**
   * 选择元素中需要回写的资源属性，保留 SVG 元素而不将其替换成 img。
   *
   * @param element 当前 DOM 元素
   * @return 待处理的属性名；不支持的元素返回空列表
   */
  private static List<String> resourceAttributes(Element element) {
    String tag = element.normalName();
    if (tag.equals("image") || tag.equals("svg:image")) {
      // EPUB 封面常嵌在 SVG 中，SVG 1.1 使用 xlink:href，SVG 2 使用 href。
      return List.of("href", "xlink:href");
    }
    if (!MEDIA_TAGS.contains(tag)) return List.of();
    return element.hasAttr("src") ? List.of("src")
        : element.hasAttr("data") ? List.of("data") : List.of();
  }

  /**
   * 读取单个引用并委托已有资源策略，成功后回写原属性。
   *
   * @param element 持有资源引用的元素
   * @param attribute 引用属性名称
   * @param documentPath 当前章节路径，作为相对地址基准
   * @param resources 本次转换共享的资源会话
   * @throws IOException 读取失败、处理器失败或返回空白 URL 时抛出
   */
  private static void rewriteReference(
      Element element, String attribute, String documentPath,
      ResourceResolver resources) throws IOException {
      // data: 或带 scheme 的地址已能独立引用，纯 # 片段也不作为 ZIP 文件读取。
      if (element.attr(attribute).isBlank()
          || isExternal(element.attr(attribute))) return;
      // 将 image/a.png 等相对引用转换为 ZIP 条目名，才能定位实际字节。
      String resource = resolve(documentPath, element.attr(attribute));
      String url = resources.resolve(resource);
      if (url == null) return;
      // 新上传的 URL 与缓存中的 URL 都在这里写回 DOM；Jsoup 负责属性序列化。
      element.attr(attribute, url);
  }


}
