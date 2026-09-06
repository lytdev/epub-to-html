package io.github.agilehub.epub2html;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static io.github.agilehub.epub2html.EpubPaths.resolve;
import static io.github.agilehub.epub2html.EpubPaths.isExternal;

/**
 * 媒体处理器，连接 EPUB 内的资源引用和宿主应用提供的存储策略。
 *
 * <p>流水线把章节和共享缓存传进来，本类读取资源字节，调用 {@link EpubResourceHandler}，
 * 再用返回地址修改元素。它不知道 OSS 凭证、本地目录或 Base64 细节，也不自行重试或回滚。
 */
final class MediaProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private MediaProcessor() {}

  /** 仅这些标签参与资源回写；同一元素优先处理 src，没有 src 时才考虑 data。 */
  private static final Set<String> MEDIA_TAGS =
      Set.of("img", "audio", "video", "source", "track", "object", "embed");

  /**
   * 将章节的本地媒体引用交给资源策略处理，并用结果 URL 回写属性。
   *
   * <p>按归档路径去重，缓存由流水线跨章节共享。缺失条目或目录条目直接跳过；只处理支持标签的 src 或 data，不处理 poster、srcset、CSS URL 等。
   *
   * @param zip 当前 EPUB 归档
   * @param doc 待修改的章节文档
   * @param documentPath 当前章节归档路径，用于解析媒体相对地址
   * @param handler 保存本地、上传或编码资源的同步策略
   * @param resourceUrls 已处理归档路径到最终 URL 的映射；本方法会加入新结果
   * @throws IOException 媒体读取失败、处理器失败或返回 null/空白 URL 时抛出
   * @throws IllegalArgumentException 媒体属性不能解析为合法 URI 时可能抛出
   */
  static void rewriteMedia(
      ZipFile zip,
      Document doc,
      String documentPath,
      EpubResourceHandler handler,
      Map<String, String> resourceUrls)
      throws IOException {
    // 扫描章节 DOM，普通正文标签不需要进入资源处理流程。
    for (Element element : doc.getAllElements()) {
      if (!MEDIA_TAGS.contains(element.tagName())) continue;
      // object 常用 data，图片、音视频常用 src；一次只选一个属性。
      String attribute = element.hasAttr("src") ? "src" : element.hasAttr("data") ? "data" : null;
      // data: 或带 scheme 的地址已能独立引用，纯 # 片段也不作为 ZIP 文件读取。
      if (attribute == null
          || element.attr(attribute).isBlank()
          || isExternal(element.attr(attribute))) continue;
      // 将 image/a.png 等相对引用转换为 ZIP 条目名，才能定位实际字节。
      String resource = resolve(documentPath, element.attr(attribute));
      ZipEntry entry = zip.getEntry(resource);
      // 可选资源缺失时保留原属性，不中止正文转换。
      if (entry == null || entry.isDirectory()) continue;
      // 相同归档路径即使在别的章节出现，也复用处理器已经返回的地址。
      String url = resourceUrls.get(resource);
      if (url == null) {
        byte[] content;
        // 完整读入 byte[]；读取结束立即关闭流，大视频会在此占用较多内存。
        try (InputStream in = zip.getInputStream(entry)) {
          content = in.readAllBytes();
        }
        // 调用方同步保存或上传资源，转换器不需要知道具体存储实现。
        url = handler.handle(new EpubResource(resource, mediaType(resource), content));
        // 没有可用返回地址意味着资源处理契约未满足，直接报告失败。
        if (url == null || url.isBlank())
          throw new IOException("Resource handler returned no URL for: " + resource);
        // 只缓存成功且非空的返回值，避免后续重复上传同一文件。
        resourceUrls.put(resource, url);
      }
      // 新上传的 URL 与缓存中的 URL 都在这里写回 DOM；Jsoup 负责属性序列化。
      element.attr(attribute, url);
    }
  }


  /**
   * 根据资源文件名推断 MIME 类型，供上传请求或 Base64 前缀使用。
   *
   * <p>只查询 JDK 文件名映射，不检查字节内容，也没有读取 OPF 声明的 media-type。
   *
   * @param resource 资源归档路径
   * @return 推断的 MIME 类型；未知类型返回 application/octet-stream
   */
  static String mediaType(String resource) {
    String type = URLConnection.guessContentTypeFromName(resource);
    return type == null ? "application/octet-stream" : type;
  }

}
