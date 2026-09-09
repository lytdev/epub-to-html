package io.github.agilehub.epub2html;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

/** 验证标题前置后仍能在最终 HTML 中保留，且不进入段落内部。 */
class HeadingProcessorTest {

  @Test
  void bodyAnchorTitleSurvivesChapterAssembly() throws Exception {
    Document html = convert(
        "<body id='chapter'><p>章节正文</p></body>",
        new TocItem("book.xhtml#chapter", 1, "第一章"));
    assertEquals("第一章 章节正文", html.body().text());
    assertEquals("h1", html.body().child(0).tagName());
  }

  @Test
  void inlineAnchorTitlePrecedesContainingParagraph() throws Exception {
    Document html = convert(
        "<body><p>前言</p><p>正文前缀<span id='part'>锚点</span>正文后缀</p></body>",
        new TocItem("book.xhtml#part", 2, "小节标题"));
    assertTrue(html.select("p h2").isEmpty());
    assertEquals("前言", html.selectFirst("h2").previousElementSibling().text());
    // HTML 格式化可能在行内元素两侧增加空白；此处验证正文内容和顺序。
    assertEquals("正文前缀锚点正文后缀",
        html.selectFirst("h2").nextElementSibling().text().replaceAll("\\s+", ""));
    assertNotNull(html.getElementById("part"));
  }

  @Test
  void chapterOpeningTitlesKeepNavigationOrder() throws Exception {
    Document html = convert("<body><p>正文</p></body>",
        new TocItem("book.xhtml", 1, "篇"),
        new TocItem("book.xhtml", 2, "章"));
    assertEquals("篇 章 正文", html.body().text());
    assertEquals(List.of("h1", "h2", "p"),
        html.body().children().stream().map(e -> e.tagName()).toList());
  }

  @Test
  void emptyFragmentMeansChapterOpening() throws Exception {
    Document html = convert("<body><p>正文</p></body>",
        new TocItem("book.xhtml#", 1, "章节"));
    assertEquals("章节 正文", html.body().text());
  }

  @Test
  void convertsExistingDivTitlesToAllSixHeadingLevels() throws Exception {
    for (int level = 1; level <= 7; level++) {
      Document html = convert(
          "<body><div id='title' class='title' style='color:red'><span>章节标题</span></div>"
              + "<p>正文</p></body>",
          new TocItem("book.xhtml#title", level, "章节标题"));
      assertEquals("h" + Math.min(level, 6), html.getElementById("title").tagName());
      assertEquals("color:red", html.getElementById("title").attr("style"));
      assertEquals("title", html.getElementById("title").className());
      assertNotNull(html.getElementById("title").selectFirst("span"));
      assertEquals(1, html.select("h1,h2,h3,h4,h5,h6").size());
      assertEquals("正文", html.getElementById("title").nextElementSibling().text());
    }
  }

  @Test
  void convertsTitleInsideContainerWithoutConvertingBodyContainer() throws Exception {
    Document html = convert(
        "<body><div id='chapter'><div id='title'>第一章</div><p>正文</p></div></body>",
        new TocItem("book.xhtml#chapter", 1, "第一章"));
    assertEquals("div", html.getElementById("chapter").tagName());
    assertEquals("h1", html.getElementById("title").tagName());
    assertEquals(1, html.select("h1").size());
    assertEquals("正文", html.selectFirst("p").text());
  }

  private Document convert(String body, TocItem... items) throws IOException {
    Document chapter = Jsoup.parse("<html><head/>" + body + "</html>", "", Parser.xmlParser());
    Map<String, List<TocItem>> toc = new LinkedHashMap<>();
    for (TocItem item : items) {
      toc.computeIfAbsent(item.target(), ignored -> new java.util.ArrayList<>()).add(item);
    }
    HeadingProcessor.injectTocHeadings(chapter, "book.xhtml", toc);
    TocItem item = new TocItem("book.xhtml", 1, "");
    item.setContent(chapter.body().html());
    return Jsoup.parse(item.getContent());
  }
}
