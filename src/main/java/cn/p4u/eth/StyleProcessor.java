package cn.p4u.eth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static cn.p4u.eth.EpubArchive.read;
import static cn.p4u.eth.EpubArchive.requiredEntry;
import static cn.p4u.eth.EpubPaths.resolve;

/**
 * 样式处理器，把 EPUB 中链接的 CSS 规则尽量转换为元素的内联 style。
 *
 * <p>由流水线在标题和媒体处理前调用。CSS 选择器描述“哪些元素受影响”，声明描述“设置哪些属性”。
 * 本类先收集文本，再拆分规则，最后匹配 DOM。其简化算法不等同于浏览器完整 CSS 计算。
 */
final class StyleProcessor {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private StyleProcessor() {}

  /**
   * 单条简化样式规则，仅用于 StyleProcessor 内部。
   *
   * @param selector 用于匹配 DOM 元素的单个选择器，例如 p.note
   * @param declarations 大括号内的属性声明，例如 color: red
   */
  private record CssRule(String selector, String declarations) {
    /**
     * 将简单 CSS 规则展开成单选择器的规则列表。
     *
     * <p>逗号连接的选择器分别生成规则；使用正则和字符串拆分，不是完整 CSS 语法解析器，不可靠支持嵌套规则或选择器内部逗号。
     *
     * @param css 合并后的 CSS 文本
     * @return 按扫描顺序排列的规则列表
     */
    static List<CssRule> parse(String css) {
      ArrayList<CssRule> result = new ArrayList<>();
      // 先移除 CSS 注释，再提取不含嵌套大括号的规则；不是完整的 CSS 语法分析。
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("(?s)([^{}@]+)\\{([^{}]*)}")
              .matcher(css.replaceAll("(?s)/\\*.*?\\*/", ""));
      // 一个规则写成 p, div 时，拆为两条规则，后续分别查询 DOM。
      while (m.find())
        for (String selector : m.group(1).split(",")) {
          String s = selector.trim();
          if (!s.isEmpty() && !s.startsWith("@")) result.add(new CssRule(s, m.group(2).trim()));
        }
      return result;
    }
  }

  /**
   * 读取章节外部样式并写入匹配元素的 style 属性。
   *
   * <p>由流水线先于标题处理调用。仅收集 link 引用的样式，不处理 head 中的 style 标签；现有实现会删除所有带 href 的 link，包括非样式链接。
   *
   * @param zip EPUB 归档，供 CSS 读取使用
   * @param doc 待原地修改的章节 DOM
   * @param documentPath 当前章节路径，用于解析样式表 href
   * @throws IOException 样式表存在但读取失败时抛出
   */
  static void inlineStyles(ZipFile zip, Document doc, String documentPath)
      throws IOException {
    // 收集样式表路径。当前实现随后移除所有带 href 的 link。
    List<String> cssFiles = new ArrayList<>();
    // 用列表快照遍历，因为循环内会从 DOM 删除 link 节点。
    for (Element link : new ArrayList<>(doc.select("link[href]"))) {
      if (link.attr("rel").toLowerCase(Locale.ROOT).contains("stylesheet"))
        cssFiles.add(resolve(documentPath, link.attr("href")));
      link.remove();
    }
    // 汇总外部样式文本；每个顶层样式表有独立的循环导入检测集合。
    StringBuilder css = new StringBuilder();
    for (String file : cssFiles) appendCss(zip, file, css, new HashSet<>());
    // 原始内联样式与外部规则分开保存，避免把前一条 CSS 误当成高优先级内联样式。
    Map<Element, StringBuilder> matchedStyles = new LinkedHashMap<>();
    for (CssRule rule : CssRule.parse(css.toString())) {
      try {
        for (Element element : doc.select(rule.selector())) {
          StringBuilder declarations =
              matchedStyles.computeIfAbsent(element, ignored -> new StringBuilder());
          appendDeclarations(declarations, rule.declarations());
        }
      } catch (Exception ignored) {
        /* 不支持的选择器不阻断整本书转换。 */
      }
    }
    // 匹配结束后统一回写，既保留规则顺序，也避免 [style] 选择器受中途修改影响。
    matchedStyles.forEach((element, declarations) ->
        element.attr("style", mergeStyles(element.attr("style"), declarations.toString())));
  }

  /**
   * 将一个规则的声明追加到缓冲区，仅在末尾缺少分隔符时补充分号。
   *
   * <p>CSS 规则通常已经以分号结尾，无条件追加会产生双分号。
   * 此方法只处理声明块边界，不替换内容内部的分号，以免破坏字符串和 data URL。
   *
   * @param target 按规则顺序累计的声明缓冲区
   * @param declarations 当前规则声明，可为空或带尾部空白
   */
  private static void appendDeclarations(StringBuilder target, String declarations) {
    String value = declarations.trim();
    if (value.isEmpty()) return;
    target.append(value);
    // 原规则已提供终止分号时直接复用，不再额外添加一个。
    if (!value.endsWith(";")) target.append(';');
    target.append(' ');
  }


  /**
   * 先收集导入的 CSS，再追加当前样式表文本。
   *
   * <p>seen 防止同一次递归读取重复路径和循环导入；每个顶层样式表使用独立 seen。缺失条目直接跳过。
   *
   * @param zip 当前 EPUB 归档
   * @param path 当前 CSS 条目路径
   * @param target 累计 CSS 文本的缓冲区，本方法向其中追加
   * @param seen 本次顶层 CSS 收集已访问的路径集合，会被修改
   * @throws IOException 已存在的 CSS 条目读取失败时抛出
   */
  static void appendCss(ZipFile zip, String path, StringBuilder target, Set<String> seen)
      throws IOException {
    // Set.add 返回 false 说明已访问过；这可阻止 A 导入 B、B 又导入 A 的无限递归。
    if (!seen.add(path) || zip.getEntry(path) == null) return;
    String css = read(zip, requiredEntry(zip, path));
    // 导入路径相对于当前 CSS 文件，不能继续使用章节 XHTML 的目录作为基准。
    java.util.regex.Matcher imports =
        java.util.regex.Pattern.compile("@import\\s+(?:url\\()?['\"]?([^'\" )]+)").matcher(css);
    while (imports.find()) appendCss(zip, resolve(path, imports.group(1)), target, seen);
    // 先收集依赖，再追加当前文件；导入语句本身仍保留在文本中。
    target.append(css).append('\n');
  }


  /**
   * 合并规则声明与元素已有声明，返回新的 style 字符串。
   *
   * <p>外部声明保持源码顺序，原始内联声明放在最后。不能按属性名去重：
   * margin 与 margin-bottom 等简写和长写之间存在覆盖关系，重复声明也可能是兼容性回退。
   * 保留声明和 !important 交由浏览器解释；本方法不计算选择器 specificity。
   *
   * @param inline 元素当前 style 属性，可为空字符串
   * @param declarations 当前规则的大括号内声明
   * @return 以分号和空格连接的属性声明
   */
  static String mergeStyles(String inline, String declarations) {
    // 仅整理声明之间的边界，不拆分属性值，避免破坏 data URL 或字符串里的分号。
    String external = declarations.trim();
    String original = inline.trim();
    if (external.isEmpty()) return original;
    if (original.isEmpty()) return external;
    return external + (external.endsWith(";") ? " " : "; ") + original;
  }
}
