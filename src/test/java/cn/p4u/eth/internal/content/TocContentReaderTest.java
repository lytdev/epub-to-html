package cn.p4u.eth.internal.content;

import cn.p4u.eth.EpubConverter;
import cn.p4u.eth.model.TocItem;
import cn.p4u.eth.support.TocTestSupport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 content 的归属、目录顺序、同文件片段去重与归档关闭后独立使用。 */
class TocContentReaderTest {
  @TempDir Path directory;

  @Test
  void keepsSourceHeadingWithoutInsertingOrRenamingNavigationTitle() throws Exception {
    Path epub = archive(Map.of("a.xhtml",
        "<html><body><h3 id='original'>原文标题</h3><p>正文</p></body></html>"));
    TocItem root = new TocItem("a.xhtml#original", 1, "目录标题");
    try (ZipFile zip = new ZipFile(epub.toFile())) {
      new TocContentReader().read(zip, List.of(root), r -> "unused", new LinkedHashMap<>());
    }
    var doc = Jsoup.parse(root.getContent());
    assertEquals("目录标题", root.getLabel());
    assertFalse(root.getContent().contains("目录标题"));
    assertEquals("h3", doc.getElementById("original").tagName());
    assertEquals("原文标题", doc.selectFirst("h3").text());
    assertEquals("正文", doc.selectFirst("p").text());
  }

  @Test
  void captionWrappingPreservesProcessedStylesResourcesAndRemovalOption() throws Exception {
    Path epub = archive(Map.of(
        "a.xhtml", "<html><head><link rel='stylesheet' href='style.css'/></head><body>"
            + "<img class='photo' src='pic.png'/><p id='caption' class='caption'>图6 图注</p>"
            + "</body></html>",
        "style.css", ".caption {color:red} .photo {width:20px}",
        "pic.png", "image"));
    TocItem root = new TocItem("a.xhtml", 1, "章节");
    try (ZipFile zip = new ZipFile(epub.toFile())) {
      new TocContentReader(true).read(zip, List.of(root), r -> "https://cdn.example/image.png",
          new LinkedHashMap<>());
    }
    var doc = Jsoup.parse(root.getContent());
    assertEquals(1, doc.select("figure > img + figcaption").size());
    assertEquals("https://cdn.example/image.png", doc.selectFirst("figure img").attr("src"));
    assertTrue(doc.selectFirst("figcaption").attr("style").contains("color:red"));
    assertTrue(doc.select("[class], [id]").isEmpty());
    assertEquals("章节", root.getLabel());
    assertTrue(doc.select("h1").isEmpty());
  }

  @Test
  void storesOnlyOwnRangeAndPreservesStylesAndSharedResources() throws Exception {
    Path epub = archive(Map.of(
        "OPS/a.xhtml", """
            <html><head><link rel="stylesheet" href="style.css"/></head><body>
            <div><p>父章引言</p><p id="one">第一节正文</p><img src="pic.png"/>
            <p id="two">第二节正文</p><img src="pic.png"/></div></body></html>
            """,
        "OPS/style.css", "p {color:red}",
        "OPS/pic.png", "image"));
    TocItem parent = new TocItem("OPS/a.xhtml", 1, "父章");
    TocItem first = new TocItem("OPS/a.xhtml#one", 2, "第一节");
    TocItem second = new TocItem("OPS/a.xhtml#two", 2, "第二节");
    parent.setChildren(List.of(first, second));
    AtomicInteger calls = new AtomicInteger();
    try (ZipFile zip = new ZipFile(epub.toFile())) {
      new TocContentReader().read(zip, List.of(parent), resource -> {
        calls.incrementAndGet();
        return "https://cdn.example/pic.png";
      }, new LinkedHashMap<>());
    }
    assertTrue(parent.getContent().contains("父章引言"));
    assertFalse(parent.getContent().contains("第一节正文"));
    assertTrue(first.getContent().contains("第一节正文"));
    assertFalse(first.getContent().contains("第二节正文"));
    assertTrue(second.getContent().contains("第二节正文"));
    assertTrue(first.getContent().contains("color:red"));
    assertEquals(1, calls.get());
    // ZIP 已关闭后，仅靠目录树即可输出整书。
    var doc = Jsoup.parse(TocTestSupport.content(List.of(parent)));
    assertEquals(List.of("父章引言", "第一节正文", "第二节正文"), doc.select("p").eachText());
    assertTrue(doc.select("h1,h2").isEmpty());
    assertEquals(2, doc.select("img").size());
  }

  @Test
  void directoryOrderOverridesSpineAndIncludesNonSpineTarget() throws Exception {
    Path epub = archive(Map.of(
        "META-INF/container.xml", "<container><rootfile full-path='OPS/book.opf'/></container>",
        "OPS/book.opf", """
            <package><manifest><item id="a" href="a.xhtml"/>
            <item id="b" href="b.xhtml"/><item id="n" href="toc.ncx"
            media-type="application/x-dtbncx+xml"/></manifest>
            <spine><itemref idref="a"/></spine></package>
            """,
        "OPS/toc.ncx", """
            <ncx xmlns="urn:ncx"><navMap>
            <navPoint><navLabel><text>乙章</text></navLabel><content src="b.xhtml"/></navPoint>
            <navPoint><navLabel><text>甲章</text></navLabel><content src="a.xhtml"/></navPoint>
            </navMap></ncx>
            """,
        "OPS/a.xhtml", "<html><body><p>甲正文</p></body></html>",
        "OPS/b.xhtml", "<html><body><p>乙正文</p></body></html>"));
    try (var stream = Files.newInputStream(epub)) {
      var doc = Jsoup.parse(TocTestSupport.content(new EpubConverter().convert(stream, r -> "unused")));
      assertEquals(List.of("乙正文", "甲正文"), doc.select("p").eachText());
      assertTrue(doc.select("h1").isEmpty());
    }
  }

  @Test
  void repeatedTargetOwnsContentOnceAndGroupsKeepChildren() throws Exception {
    Path epub = archive(Map.of("a.xhtml", "<html><body><p>唯一正文</p></body></html>"));
    TocItem group = new TocItem(null, 1, "分组");
    TocItem first = new TocItem("a.xhtml", 2, "父节");
    TocItem second = new TocItem("a.xhtml", 3, "子节");
    first.setChildren(List.of(second));
    group.setChildren(List.of(first));
    try (ZipFile zip = new ZipFile(epub.toFile())) {
      new TocContentReader().read(zip, List.of(group), r -> "unused", new LinkedHashMap<>());
    }
    assertFalse(first.getContent().contains("唯一正文"));
    assertTrue(second.getContent().contains("唯一正文"));
    var doc = Jsoup.parse(TocTestSupport.content(List.of(group)));
    assertEquals(1, doc.select("p").size());
    assertTrue(doc.select("h1,h2,h3").isEmpty());
    assertEquals("", group.getContent());
    assertEquals("", first.getContent());
    assertEquals("分组", group.getLabel());
  }

  @Test
  void missingTargetAnchorFailsInsteadOfCopyingWholePage() throws Exception {
    Path epub = archive(Map.of("a.xhtml", "<html><body><p>正文</p></body></html>"));
    try (ZipFile zip = new ZipFile(epub.toFile())) {
      assertThrows(IOException.class, () -> new TocContentReader().read(zip,
          List.of(new TocItem("a.xhtml#missing", 1, "章节")), r -> "unused", new LinkedHashMap<>()));
    }
  }

  private Path archive(Map<String, String> entries) throws IOException {
    Path path = directory.resolve("book.epub");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return path;
  }
}
