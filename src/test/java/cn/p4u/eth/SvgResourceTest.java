package cn.p4u.eth;

import cn.p4u.eth.support.TocTestSupport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** 通过公开输入流 API 验证 SVG 封面引用的处理与最终回写。 */
class SvgResourceTest {
  @Test
  void svgAndHtmlReferencesShareHandlerResult() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var roots = new EpubConverter().convert(new ByteArrayInputStream(epub()), resource -> {
      calls.incrementAndGet();
      assertEquals("OEBPS/Images/cover.jpg", resource.archivePath());
      assertArrayEquals(new byte[] {1, 2, 3}, resource.content());
      return "https://cdn.example/cover.jpg";
    });
    var doc = Jsoup.parse(TocTestSupport.content(roots));
    assertEquals(1, calls.get());
    assertEquals("https://cdn.example/cover.jpg", doc.getElementById("legacy").attr("xlink:href"));
    assertEquals("https://cdn.example/cover.jpg", doc.getElementById("modern").attr("href"));
    assertEquals("https://cdn.example/cover.jpg", doc.getElementById("both").attr("href"));
    assertEquals("https://cdn.example/cover.jpg", doc.getElementById("both").attr("xlink:href"));
    assertEquals("https://cdn.example/cover.jpg", doc.getElementById("ordinary").attr("src"));
    assertEquals("1202", doc.getElementById("legacy").attr("height"));
    assertEquals("850", doc.getElementById("legacy").attr("width"));
    assertEquals("https://example.com/external.jpg", doc.getElementById("external").attr("href"));
    assertEquals("data:image/png;base64,AA==", doc.getElementById("embedded").attr("href"));
    assertEquals("../Images/missing.jpg", doc.getElementById("missing").attr("href"));
  }

  @Test
  void base64HandlerResultIsWrittenIntoSvgAttribute() throws Exception {
    var roots = new EpubConverter().convert(new ByteArrayInputStream(epub()), resource ->
        "data:" + resource.mediaType() + ";base64,"
            + Base64.getEncoder().encodeToString(resource.content()));
    var image = Jsoup.parse(TocTestSupport.content(roots)).getElementById("legacy");
    assertEquals("data:image/jpeg;base64,AQID", image.attr("xlink:href"));
  }

  private byte[] epub() throws Exception {
    var entries = Map.of(
        "META-INF/container.xml", "<container><rootfile full-path='OEBPS/book.opf'/></container>",
        "OEBPS/book.opf", """
            <package><manifest><item id="cover" href="Text/cover.xhtml"/></manifest>
            <spine><itemref idref="cover"/></spine></package>
            """,
        "OEBPS/Text/cover.xhtml", """
            <html><head/><body>
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
              <image id="legacy" height="1202" width="850" xlink:href="../Images/cover.jpg"/>
              <image id="modern" href="../Images/cover.jpg"/>
              <image id="both" href="../Images/cover.jpg" xlink:href="../Images/cover.jpg"/>
              <image id="external" href="https://example.com/external.jpg"/>
              <image id="embedded" href="data:image/png;base64,AA=="/>
              <image id="missing" href="../Images/missing.jpg"/>
            </svg><img id="ordinary" src="../Images/cover.jpg"/>
            </body></html>
            """);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("OEBPS/Images/cover.jpg"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
