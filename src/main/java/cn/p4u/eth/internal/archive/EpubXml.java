package cn.p4u.eth.internal.archive;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;


/**
 * 包描述和 NCX 共用的 XML 工具，负责安全解析与节点内容查询。
 *
 * <p>XML DOM 是文档的树形表示。调用者使用树中的元素名、属性与文本来读取书籍结构。
 * 所有解析器都经过本类配置，以避免一处开启外部实体而另一处关闭的行为差异。
 */
final class EpubXml {
  /** 仅提供静态方法，禁止创建没有用途的工具类实例。 */
  private EpubXml() {}

  /**
   * 在禁用外部 DTD 和实体读取的条件下解析 XML 文本。
   *
   * <p>供 {@link PackageReader} 和 {@link NavigationReader} 使用。这里返回 JDK DOM，区别于处理 XHTML 的 Jsoup Document。每次新建解析器，避免共享可变解析状态。
   *
   * @param input 已解码的 XML 文本
   * @return 支持命名空间的 JDK DOM 文档
   * @throws IOException XML 格式错误、解析器配置失败或解析失败时抛出，并保留原始异常
   */
  static org.w3c.dom.Document xml(String input) throws IOException {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      // EPUB XML 常带命名空间，开启后才能按 localName 查找 NCX 节点。
      f.setNamespaceAware(true);
      // 启用 JDK 安全处理限制，并禁止读取外部 DTD/实体，避免访问外部文件或网络。
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      // StringReader 把现有字符串作为解析输入，不需要额外落盘。
      return f.newDocumentBuilder().parse(new InputSource(new StringReader(input)));
    } catch (Exception e) {
      throw new IOException("Invalid EPUB XML", e);
    }
  }


  /**
   * 读取首个匹配后代元素的文本，供 NCX 导航标签提取使用。
   *
   * <p>查询包括所有层级的后代，不仅限于直接子节点；星号表示不限制命名空间。
   *
   * @param parent 查询起点元素
   * @param local 元素的本地名称，不含命名空间前缀，例如 text
   * @return 首个匹配元素的文本并去除首尾空白；不存在时返回空字符串
   */
  static String textOf(org.w3c.dom.Element parent, String local) {
    // 查找所有匹配后代元素；调用者只使用第一个结果。
    NodeList items = parent.getElementsByTagNameNS("*", local);
    return items.getLength() == 0 ? "" : items.item(0).getTextContent().trim();
  }


  /**
   * 读取首个匹配后代元素上的指定属性。
   *
   * @param parent 查询起点元素
   * @param local 后代元素的本地名称，例如 content
   * @param attribute 属性名称，例如 src
   * @return 没有匹配元素时为 null；找到元素但缺少该属性时为空字符串
   */
  static String attributeOf(org.w3c.dom.Element parent, String local, String attribute) {
    // 查找所有匹配后代元素；调用者只使用第一个结果。
    NodeList items = parent.getElementsByTagNameNS("*", local);
    return items.getLength() == 0
        ? null
        : ((org.w3c.dom.Element) items.item(0)).getAttribute(attribute);
  }

}
