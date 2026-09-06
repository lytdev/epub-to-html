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
import static io.github.agilehub.epub2html.EpubXml.textOf;
import static io.github.agilehub.epub2html.EpubXml.attributeOf;

/**
 * 导航读取器，把 NCX 或 EPUB 3 导航树转换为带层级的 {@link TocItem} 列表。
 *
 * <p>由 {@link ConversionPipeline} 在章节循环前调用；结果交给 {@link HeadingProcessor} 使用。
 * “扁平列表”不是丢弃层级：每条记录仍有 level，所以后续能决定生成 h1、h2 等标题。
 */
final class NavigationReader {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private NavigationReader() {}

  /**
   * 选择可用的导航文件并读取层级目录。
   *
   * <p>先尝试存在的 NCX，再尝试 nav；两者都不存在时返回空列表。已选 NCX 解析报错时直接传播异常，不会再回退 nav。
   *
   * @param zip 当前 EPUB 归档
   * @param pkg 包读取器给出的导航文件位置
   * @return 按解析遍历顺序排列的目录项，没有可用导航时为空列表
   * @throws IOException 导航读取或 NCX XML 解析失败时抛出
   */
  static List<TocItem> readToc(ZipFile zip, PackageData pkg)
      throws IOException {
    // 使用存在的 NCX；选中后若解析失败，不会静默切换到另一个目录来源。
    if (pkg.ncx() != null && zip.getEntry(pkg.ncx()) != null)
      /**
       * 从 NCX 的首个 navMap 开始展开目录树。
       *
       * <p>交给 ncxItems 做深度优先遍历，父目录先进入结果列表，子目录紧随其后。
       *
       * @param content NCX 文件的完整 XML 文本
       * @param ncxPath NCX 归档路径，用于解析 content 的相对 src
       * @return 保留导航层级的目录项列表；没有 navMap 时为空
       * @throws IOException NCX XML 无法解析时抛出
       */
      return readNcx(read(zip, requiredEntry(zip, pkg.ncx())), pkg.ncx());
    // 没有可用 NCX 才尝试 EPUB 3 的导航 XHTML。
    if (pkg.nav() != null && zip.getEntry(pkg.nav()) != null)
      /**
       * 从 EPUB 3 导航 XHTML 中读取有序列表。
       *
       * <p>当前选择器取首个匹配的 nav 元素；因包含通用 nav 分支，不保证在多个 nav 中优先选择 toc。随后从首个 ol 开始递归解析。
       *
       * @param content 导航 XHTML 的完整文本
       * @param navPath 导航文件归档路径，作为相对链接的基准
       * @return 导航目录项；没有 nav 或 ol 时为空列表
       * @throws org.jsoup.select.Selector.SelectorParseException 当前 Jsoup 版本无法解析所用选择器时抛出
       */
      return readNav(read(zip, requiredEntry(zip, pkg.nav())), pkg.nav());
    return List.of();
  }


  static List<TocItem> readNcx(String content, String ncxPath) throws IOException {
    org.w3c.dom.Document document = xml(content);
    ArrayList<TocItem> result = new ArrayList<>();
    // navMap 是 NCX 的目录根节点，从一级目录开始遍历。
    NodeList maps = document.getElementsByTagNameNS("*", "navMap");
    if (maps.getLength() > 0) ncxItems(maps.item(0), ncxPath, 1, result);
    return result;
  }


  /**
   * 递归读取 navPoint，并将当前节点和子节点加入结果列表。
   *
   * <p>递归是方法调用自身：每次向下进入一层目录，level 加一；没有子节点时自然结束。没有 src 的节点不加入结果，但仍继续遍历其子目录。
   *
   * @param parent 本层查询起点，可为 navMap 或上层 navPoint
   * @param base NCX 文件路径，用来解析相对链接
   * @param level 当前层级，从 1 开始
   * @param out 用于累计目录项的可变列表，本方法直接追加元素
   */
  static void ncxItems(org.w3c.dom.Node parent, String base, int level, List<TocItem> out) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      org.w3c.dom.Node node = children.item(i);
      // DOM 子节点还可能是空白文本；只处理元素类型且名称为 navPoint 的节点。
      if (!(node instanceof org.w3c.dom.Element e) || !"navPoint".equals(e.getLocalName()))
        continue;
      // 标签文字用于生成 h 标签，src 用于定位标题应插入哪个章节、哪个锚点。
      String label = textOf(e, "text");
      String src = attributeOf(e, "content", "src");
      if (src != null && !src.isBlank()) out.add(new TocItem(resolve(base, src), level, label));
      // 先加入父目录，再递归读子目录；level + 1 保留嵌套深度。
      ncxItems(e, base, level + 1, out);
    }
  }


  static List<TocItem> readNav(String content, String navPath) {
    Document doc = Jsoup.parse(content, navPath, Parser.xmlParser());
    // 选择器含通用 nav 回退，因此取得的是文档顺序中首个匹配项。
    Element nav = doc.selectFirst("nav[*|type=\"toc\"], nav[epub:type=\"toc\"], nav");
    if (nav == null) return List.of();
    ArrayList<TocItem> result = new ArrayList<>();
    navItems(nav.selectFirst("ol"), navPath, 1, result);
    return result;
  }


  /**
   * 递归读取 ol 下的直接 li 子节点，将链接映射为目录项。
   *
   * <p>只查直接子节点，避免一次把所有后代提前读入而丢失层级；无链接的 li 仍可继续读取其子 ol。
   *
   * @param list 当前有序列表节点；为 null 时结束递归
   * @param base 导航文件归档路径
   * @param level 当前目录层级，从 1 开始
   * @param out 累计结果的可变列表
   */
  static void navItems(Element list, String base, int level, List<TocItem> out) {
    // 没有下层 ol 表示已走到目录叶子，直接返回上一层调用。
    if (list == null) return;
    // > 只选直接子节点，子列表由下面的递归单独处理。
    for (Element li : list.select("> li")) {
      Element link = li.selectFirst("> a[href]");
      if (link != null) out.add(new TocItem(resolve(base, link.attr("href")), level, link.text()));
      // 即使当前 li 没有 a 链接，也继续查找它下面的目录项。
      navItems(li.selectFirst("> ol"), base, level + 1, out);
    }
  }

}
