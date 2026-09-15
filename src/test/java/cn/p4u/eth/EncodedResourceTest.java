package cn.p4u.eth;

import cn.p4u.eth.internal.archive.EpubPaths;
import cn.p4u.eth.support.EpubFixture;
import cn.p4u.eth.support.TocTestSupport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/** 验证带空格的章节路径与百分号编码媒体引用可以匹配实际 ZIP 条目。 */
class EncodedResourceTest {
  @Test
  void resolvesEncodedReferenceAgainstDecodedArchivePath() {
    assertEquals("OEBPS/image/徽韵悠长：探索徽文化_fmt.png",
        EpubPaths.resolve("OEBPS/中华优秀传统文化概论 - 副本.xhtml",
            "image/%e5%be%bd%e9%9f%b5%e6%82%a0%e9%95%bf%ef%bc%9a"
                + "%e6%8e%a2%e7%b4%a2%e5%be%bd%e6%96%87%e5%8c%96_fmt.png"));
    // 基准路径中的空格和字面百分号是文件名；引用中的 + 不能按表单规则解码。
    assertEquals("OPS/100% 资料/a+b c.png",
        EpubPaths.resolve("OPS/100% 资料/正文.xhtml", "a+b%20c.png"));
    assertEquals("OPS/a.xhtml#章节",
        EpubPaths.resolve("OPS/正文 副本.xhtml", "a.xhtml#%E7%AB%A0%E8%8A%82"));
  }

  @Test
  void encodedImageIsPassedToHandlerAndRewrittenInContent(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    String expected = "OEBPS/image/1.徽韵悠长：探索徽文化_fmt.png";
    var calls = new LinkedHashMap<String, Integer>();
    var roots = new EpubConverter().convert(EpubFixture.create(directory), resource -> {
      calls.merge(resource.archivePath(), 1, Integer::sum);
      assertTrue(resource.content().length > 0);
      return resource.archivePath().equals(expected)
          ? "https://cdn.example/cover.png" : "https://cdn.example/other.png";
    });
    assertEquals(1, calls.get(expected));
    var doc = Jsoup.parse(TocTestSupport.content(roots));
    assertFalse(doc.select("img.frame-8[src='https://cdn.example/cover.png']").isEmpty());
    assertTrue(doc.select("img.frame-8[src^='image/1.']").isEmpty());
  }
}
