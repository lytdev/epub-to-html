package cn.p4u.eth;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** 验证外部重置规则、具体段落规则和原始内联声明不会因合并而颠倒。 */
class StyleProcessorTest {
  @Test
  void demoParagraphRetainsResetBeforeSpecificMargin() throws Exception {
    try (ZipFile zip = new ZipFile(Path.of("demo.epub").toFile())) {
      var doc = Jsoup.parse(EpubArchive.read(zip, EpubArchive.requiredEntry(zip,
          "OEBPS/中华优秀传统文化概论 - 副本.xhtml")));
      var paragraph = doc.selectFirst("p.x----2");
      assertNotNull(paragraph);
      // 明确覆盖用户报告的尾随空格情形，class 仍按空白分隔的词匹配。
      paragraph.attr("class", "x----2 ");
      StyleProcessor.inlineStyles(zip, doc, "OEBPS/中华优秀传统文化概论 - 副本.xhtml");
      String style = paragraph.attr("style").replaceAll("\\s+", "");
      assertTrue(style.contains("padding:0;"));
      assertTrue(style.contains("border-width:0;"));
      assertTrue(style.indexOf("margin:0;") >= 0);
      assertTrue(style.indexOf("margin:0;") < style.indexOf("margin-bottom:23px;"));
      assertEquals(style.indexOf("margin:0;"), style.lastIndexOf("margin:0;"));
      assertFalse(style.contains(";;"), "规则末尾已有分号，不应再次追加");
    }
  }

  @Test
  void keepsShorthandLonghandAndOriginalInlineOrder() {
    assertEquals("margin:0; margin-bottom:23px; margin:5px",
        StyleProcessor.mergeStyles("margin:5px", "margin:0; margin-bottom:23px;"));
    assertEquals("margin:0; margin-bottom:23px; margin:5px; margin-bottom:9px",
        StyleProcessor.mergeStyles("margin-bottom:9px",
            "margin:0; margin-bottom:23px; margin:5px;"));
  }

  @Test
  void preservesFallbacksImportantAndSemicolonsInsideValues() {
    String css = "display:block; display:grid; color:red!important; "
        + "background:url('data:image/png;base64,AA=='); content:'a;b'";
    assertEquals(css + "; color:blue", StyleProcessor.mergeStyles("color:blue", css));
  }

  @Test
  void joinsRulesWithAndWithoutTrailingSemicolon(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path archive = directory.resolve("styles.zip");
    // 同时覆盖原规则有分号、没有分号、空规则及属性值内的连续分号。
    String css = "p {color:red;   } p {margin:0} p {} "
        + "p {content:'a;;b'; background:url('data:image/png;base64,AA==');}";
    try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
      out.putNextEntry(new java.util.zip.ZipEntry("book.css"));
      out.write(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.closeEntry();
    }
    var doc = Jsoup.parse("<link rel='stylesheet' href='book.css'>"
        + "<p style='padding:1px;'>正文</p>");
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      StyleProcessor.inlineStyles(zip, doc, "book.xhtml");
    }
    assertEquals("color:red; margin:0; content:'a;;b'; "
            + "background:url('data:image/png;base64,AA=='); padding:1px;",
        doc.selectFirst("p").attr("style"));
  }
}
