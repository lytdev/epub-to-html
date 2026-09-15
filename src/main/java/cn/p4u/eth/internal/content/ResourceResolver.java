package cn.p4u.eth.internal.content;

import cn.p4u.eth.resource.EpubResource;
import cn.p4u.eth.resource.EpubResourceHandler;

import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * 一次转换的资源会话：读取归档、委托存储策略并缓存成功结果。
 *
 * <p>这是对资源策略的内部封装，不是全局缓存。不同书籍可能有相同文件名，
 * 因此每次转换必须新建会话；归档的关闭仍由 转换流水线 负责。
 */
final class ResourceResolver {
  private final ZipFile zip;
  private final EpubResourceHandler handler;
  private final Map<String, String> urls;

  ResourceResolver(ZipFile zip, EpubResourceHandler handler, Map<String, String> urls) {
    this.zip = zip;
    this.handler = handler;
    this.urls = urls;
  }

  /**
   * 返回归档资源的最终地址；缺失的可选资源返回 null，让调用方保留原引用。
   * 只有成功结果才进入缓存；失败会立即传播，外部上传不会自动回滚。
   *
   * @param path 已解析的 ZIP 条目路径
   * @return 存储策略返回的非空白 URL，资源缺失时为 null
   * @throws IOException 读取失败或策略未返回有效地址
   */
  String resolve(String path) throws IOException {
    var entry = zip.getEntry(path);
    if (entry == null || entry.isDirectory()) return null;
    String url = urls.get(path);
    if (url != null) return url;
    byte[] content;
    try (var input = zip.getInputStream(entry)) {
      content = input.readAllBytes();
    }
    // 文件名推断保持原有语义；不检查字节，也不读取 OPF 的 media-type 声明。
    String type = java.net.URLConnection.guessContentTypeFromName(path);
    url = handler.handle(new EpubResource(path,
        type == null ? "application/octet-stream" : type, content));
    if (url == null || url.isBlank()) {
      throw new IOException("Resource handler returned no URL for: " + path);
    }
    urls.put(path, url);
    return url;
  }
}
