package cn.p4u.eth.util;

import java.util.List;
import java.util.Objects;

import cn.p4u.eth.model.TocItem;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.select.Elements;

/** 展示层输出工具：先序遍历章节树，生成带目录标题的 HTML 或保留树结构的 JSON。 */
public final class TocSupport {
  private TocSupport() {}

  /** 汇总目录标题与正文，不清理正文原有标题；不改变原节点内容。 */
  public static String contentToHtml(List<TocItem> itemList) {
    return contentToHtml(itemList, false);
  }

  /**
   * 汇总目录标题与正文，输出顺序为父节点后接子节点。
   * @param itemList 待展示的目录树
   * @param clearTitle 是否移除正文第一个同级标题，并将其余同级标题转为 strong；null 按 false 处理
   * @return HTML 片段，不包含 html/body 外壳
   */
  public static String contentToHtml(List<TocItem> itemList, Boolean clearTitle) {
    StringBuilder result = new StringBuilder();
    for (TocItem item : itemList) {
      String label = item.getLabel();
      Integer level = item.getLevel();
      result.append(getTitleHtml(label, level));
      String content = item.getContent();
      // 清理已存在的标题
      if (Boolean.TRUE.equals(clearTitle)) {
        Document document = Jsoup.parse(content);
        Elements titleEleList = document.getElementsByTag("h" + level);
        boolean isFirstLevel = true;
        for (Element titleEle : titleEleList) {
          // 查找到的第一个删除
          if (isFirstLevel) {
            titleEle.remove();
            isFirstLevel = false;
          } else {
            // 其他的设置为strong标签
            Element strong = document.createElement("strong").text(titleEle.text());
            titleEle.replaceWith(strong);
          }
        }
        Elements children = document.body().children();
        for (Element child : children) {
          String text = child.text();
          if (!StringUtil.isBlank(text)) {
            String resultText = text.replaceAll("[\\s\\p{Z}]+", "");
            String labelResult = label == null ? "" : label.replaceAll("[\\s\\p{Z}]+", "");
            if (Objects.equals(resultText, labelResult)) {
              // 内容开始和标题一样，需要删除
              child.remove();
            }
            break;
          }
        }
        content = document.body().html();
      }
      result.append(content);
      result.append(contentToHtml(item.getChildren(), clearTitle));
    }
    return result.toString();
  }

  /**
   * 获取标题html
   *
   * @param label 目录文字；特殊字符会进行 HTML 转义
   * @param level 1 至 6 使用对应标题元素，其他值使用段落
   * @return 标题 HTML 片段
   */
  private static String getTitleHtml(String label, Integer level) {
    if (StringUtil.isBlank(label)) {
      return "";
    }
    String escapedLabel = Entities.escape(label);
    StringBuilder result = new StringBuilder();
    if (level != null && level >= 1 && level <= 6) {
      result
          .append("<h")
          .append(level)
          .append(">")
          .append(escapedLabel)
          .append("</h")
          .append(level)
          .append(">");
    } else {
      result.append("<p>").append(escapedLabel).append("</p>");
    }
    return result.toString();
  }

  /** 序列化全部节点及 children，转义 JSON 字符串中的引号、反斜杠和控制字符。 */
  public static String writeToJson(List<TocItem> roots) {
    StringBuilder json = new StringBuilder();
    appendItemList(json, roots);
    return json.append('\n').toString();
  }

  private static void appendItemList(StringBuilder json, List<TocItem> items) {
    json.append('[');
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) json.append(',');
      TocItem item = items.get(i);
      json.append("{\"target\":");
      appendString(json, item.getTarget());
      json.append(",\"level\":").append(item.getLevel() == null ? "null" : item.getLevel());
      json.append(",\"label\":");
      appendString(json, item.getLabel());
      json.append(",\"content\":");
      appendString(json, item.getContent());
      json.append(",\"children\":");
      appendItemList(json, item.getChildren());
      json.append('}');
    }
    json.append(']');
  }

  private static void appendString(StringBuilder json, String value) {
    if (value == null) {
      json.append("null");
      return;
    }
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
          else json.append(c);
        }
      }
    }
    json.append('"');
  }
}
