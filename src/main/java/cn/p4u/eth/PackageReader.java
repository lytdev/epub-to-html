package cn.p4u.eth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.w3c.dom.NodeList;
import static cn.p4u.eth.EpubArchive.read;
import static cn.p4u.eth.EpubArchive.requiredEntry;
import static cn.p4u.eth.EpubPaths.normalize;
import static cn.p4u.eth.EpubPaths.resolve;
import static cn.p4u.eth.EpubXml.xml;

/**
 * EPUB 包描述读取器，将归档文件清单与正文阅读顺序转为 {@link PackageData}。
 *
 * <p>由流水线先调用 packagePath 定位 OPF，再调用 readPackage 读取它。 manifest 类似文件索引表，spine
 * 类似阅读顺序表。本类不解析正文，也不处理媒体字节。
 */
final class PackageReader {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private PackageReader() {}

  /**
   * 读取容器入口，确定 OPF 包描述文件的归档路径。
   *
   * <p>EPUB 的固定入口为 META-INF/container.xml。本实现选择第一个 rootfile，返回值随后传入 readPackage。
   *
   * @param zip 已打开的 EPUB ZIP 文件
   * @return 第一个 rootfile 的 full-path 经整理后的路径: OEBPS/content.opf
   * @throws IOException 容器条目缺失、XML 解析失败或没有 rootfile 时抛出
   */
  static String packagePath(ZipFile zip) throws IOException {
    // 固定容器文件保存 OPF 的位置，OPF 不一定就在 ZIP 根目录。
    ZipEntry container = requiredEntry(zip, "META-INF/container.xml");
    org.w3c.dom.Document xml = xml(read(zip, container));
    // * 允许不同命名空间前缀；当前只选第一个 rootfile。
    NodeList roots = xml.getElementsByTagNameNS("*", "rootfile");
    if (roots.getLength() == 0)
      throw new IOException("Invalid EPUB: META-INF/container.xml has no rootfile");
    return normalize(((org.w3c.dom.Element) roots.item(0)).getAttribute("full-path"));
  }

  /**
   * 解析 OPF 清单和阅读顺序，生成流水线需要的包信息。
   *
   * <p>manifest 把资源 ID 映射为路径；spine 使用 ID 指定正文顺序。本实现跳过无法映射的 itemref，不过滤 linear 属性；多个 NCX/nav
   * 声明时保留最后一次匹配。
   *
   * @param zip 已打开的 EPUB 归档
   * @param opfPath packagePath 返回的 OPF 条目路径
   * @return 章节路径列表及可选的 NCX、nav 路径
   * @throws IOException OPF 缺失、解析失败或没有可用 spine 条目时抛出
   */
  static PackageData readPackage(ZipFile zip, String opfPath) throws IOException {
    org.w3c.dom.Document xml = xml(read(zip, requiredEntry(zip, opfPath)));
    // manifest 的 id 是 spineList 引用的“键”，值是相对于 OPF 解析后的文件路径。
    Map<String, String> items = new HashMap<>();
    String ncx = resolve(opfPath, "toc.ncx"), nav = null;
    // 第一轮读取资源清单，同时找出两种可选导航文件。
    NodeList manifest = xml.getElementsByTagNameNS("*", "item");
    for (int i = 0; i < manifest.getLength(); i++) {
      org.w3c.dom.Element item = (org.w3c.dom.Element) manifest.item(i);
      // href 相对的是 OPF 文件，不是操作系统的当前工作目录。
      String path = resolve(opfPath, item.getAttribute("href"));
      items.put(item.getAttribute("id"), path);
      String properties = item.getAttribute("properties");
      // NCX 用 MIME 类型识别；EPUB 3 nav 用 properties 中的标记识别。
      if ("application/x-dtbncx+xml".equals(item.getAttribute("media-type"))) ncx = path;
      if (Arrays.asList(properties.split("\\s+")).contains("nav")) nav = path;
    }
    // 第二轮按 spineList 的 itemref 顺序取路径，清单中的排列顺序不决定正文顺序。
    ArrayList<String> spineList = new ArrayList<>();
    NodeList refs = xml.getElementsByTagNameNS("*", "itemref");
    for (int i = 0; i < refs.getLength(); i++) {
      String path = items.get(((org.w3c.dom.Element) refs.item(i)).getAttribute("idref"));
      // 无法在 manifest 中找到的 idref 被跳过；最终仍需有至少一个可用章节。
      if (path != null) spineList.add(path);
    }
    if (spineList.isEmpty()) throw new IOException("Invalid EPUB: OPF spineList is empty");
    return new PackageData(spineList, ncx, nav);
  }
}
