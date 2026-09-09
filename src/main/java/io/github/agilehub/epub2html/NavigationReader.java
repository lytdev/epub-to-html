package io.github.agilehub.epub2html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.w3c.dom.NodeList;
import static io.github.agilehub.epub2html.EpubArchive.read;
import static io.github.agilehub.epub2html.EpubArchive.requiredEntry;
import static io.github.agilehub.epub2html.EpubPaths.resolve;
import static io.github.agilehub.epub2html.EpubXml.xml;

/**
 * 将 NCX 或 EPUB 3 导航解析为目录树。
 *
 * <p>返回列表只包含根节点，下一层存入各节点 children；兄弟顺序与原导航一致。
 * 无链接的分组节点也保留，target 为 null，避免子目录被提升或借用子节点链接。
 */
final class NavigationReader {
  /** 工具类不需要实例。 */
  private NavigationReader() {}

  /**
   * 优先读取存在的 NCX，否则读取 EPUB 3 nav。
   *
   * @param zip 已打开的 EPUB
   * @param pkg 包描述，包含导航文件位置
   * @return 根目录节点列表，无导航时为空
   * @throws IOException 导航读取或 XML 解析失败；选中 NCX 后不因解析错误回退
   */
  static List<TocItem> readToc(ZipFile zip, PackageData pkg) throws IOException {
    if (pkg.ncx() != null && zip.getEntry(pkg.ncx()) != null)
      return readNcx(read(zip, requiredEntry(zip, pkg.ncx())), pkg.ncx());
    if (pkg.nav() != null && zip.getEntry(pkg.nav()) != null)
      return readNav(read(zip, requiredEntry(zip, pkg.nav())), pkg.nav());
    return List.of();
  }

  /**
   * 从第一个 navMap 读取 NCX 目录树。
   *
   * @param content NCX XML 文本
   * @param ncxPath 归档路径，用于解析相对链接
   * @return 仅含根节点的列表；子目录位于 children
   * @throws IOException XML 解析失败时抛出
   */
  static List<TocItem> readNcx(String content, String ncxPath) throws IOException {
    org.w3c.dom.Document document = xml(content);
    List<TocItem> result = new ArrayList<>();
    NodeList maps = document.getElementsByTagNameNS("*", "navMap");
    if (maps.getLength() > 0) ncxItems(maps.item(0), ncxPath, 1, result);
    return result;
  }

  /**
   * 读取当前层直接 navPoint 子元素，每个节点递归填充自己的 children。
   *
   * @param parent navMap 或父 navPoint
   * @param base NCX 归档路径
   * @param level 当前层级，从 1 开始
   * @param out 当前层的结果列表，不加入孙节点
   */
  static void ncxItems(org.w3c.dom.Node parent, String base, int level, List<TocItem> out) {
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (!(nodes.item(i) instanceof org.w3c.dom.Element node)
          || !"navPoint".equals(node.getLocalName())) continue;
      // 只取本节点自己的标签与链接，不能查找所有后代而误取子目录的信息。
      org.w3c.dom.Element navLabel = directChild(node, "navLabel");
      org.w3c.dom.Element text = navLabel == null ? null : directChild(navLabel, "text");
      org.w3c.dom.Element content = directChild(node, "content");
      String src = content == null ? "" : content.getAttribute("src");
      TocItem item = new TocItem(
          src.isBlank() ? null : resolve(base, src), level,
          text == null ? "" : text.getTextContent().trim());
      // 关键：递归的输出列表属于当前节点，不再传入上一层的 out。
      ncxItems(node, base, level + 1, item.getChildren());
      out.add(item);
    }
  }

  /**
   * 查找直接子元素，不穿透下层目录。
   *
   * @param parent 父元素
   * @param name 不含命名空间前缀的元素名
   * @return 首个匹配元素，不存在时为 null
   */
  private static org.w3c.dom.Element directChild(org.w3c.dom.Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof org.w3c.dom.Element element
          && name.equals(element.getLocalName())) return element;
    }
    return null;
  }

  /**
   * 读取 EPUB 3 导航的 ol/li 目录树，优先选 epub:type= toc 的导航。
   *
   * @param content XHTML 文本
   * @param navPath 导航归档路径
   * @return 根目录列表
   */
  static List<TocItem> readNav(String content, String navPath) {
    Document doc = Jsoup.parse(content, navPath, Parser.xmlParser());
    Element nav = doc.select("nav").stream()
        .filter(e -> List.of(e.attr("epub:type").trim().split("\\s+")).contains("toc"))
        .findFirst().orElse(doc.selectFirst("nav"));
    List<TocItem> result = new ArrayList<>();
    if (nav != null) navItems(nav.selectFirst("ol"), navPath, 1, result);
    return result;
  }

  /**
   * 将当前列表的直接 li 转为节点，无链接的 span 分组也保留。
   *
   * @param list 当前 ol，null 时表示无子目录
   * @param base 导航归档路径
   * @param level 当前层级
   * @param out 当前层结果列表
   */
  static void navItems(Element list, String base, int level, List<TocItem> out) {
    if (list == null) return;
    for (Element li : list.children()) {
      if (!li.normalName().equals("li")) continue;
      Element label = li.children().stream()
          .filter(e -> e.normalName().equals("a") || e.normalName().equals("span"))
          .findFirst().orElse(null);
      String href = label != null && label.normalName().equals("a") ? label.attr("href") : "";
      TocItem item = new TocItem(href.isBlank() ? null : resolve(base, href), level,
          label == null ? li.ownText() : label.text());
      for (Element child : li.children()) {
        if (child.normalName().equals("ol")) navItems(child, base, level + 1, item.getChildren());
      }
      out.add(item);
    }
  }
}
