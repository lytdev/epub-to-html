package cn.p4u.eth.internal.content;

import static org.junit.jupiter.api.Assertions.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/** 验证图注相邻关系、结构合法性和重复调用安全性。 */
class FigureProcessorTest {
  private Document convert(String body) {
    Document doc = Jsoup.parse(body);
    FigureProcessor.wrapCaptions(doc);
    return doc;
  }

  @Test
  void wrapsAdjacentCaptionAndKeepsAttributesButOnlyTextInside() {
    var doc = convert("<img src='cdn.png' id='image' style='width:20px'>\n<!-- gap -->"
        + "<p id='caption' class='note' style='color:red'>图6　<em>《神仙赴会图》</em>东壁后部</p>");
    assertEquals(1, doc.select("figure > img + figcaption").size());
    assertEquals("cdn.png", doc.selectFirst("figure > img").attr("src"));
    assertEquals("width:20px", doc.getElementById("image").attr("style"));
    assertEquals("color:red", doc.getElementById("caption").attr("style"));
    assertEquals("note", doc.getElementById("caption").className());
    assertTrue(doc.selectFirst("figcaption").children().isEmpty());
    assertEquals("图6　《神仙赴会图》东壁后部", doc.selectFirst("figcaption").text());
    String html = doc.body().html();
    FigureProcessor.wrapCaptions(doc);
    assertEquals(html, doc.body().html());
  }

  @Test
  void handlesSingleImageParagraphAndInlineCaptionWithoutFigureInsideP() {
    var doc = convert("<p id='frame' style='text-align:center'><img src='a.png'></p>"
        + "<p>图 ６ 图注</p><p><img src='b.png'><span>图7 第二张</span></p>");
    assertEquals(2, doc.select("figure > img + figcaption").size());
    assertTrue(doc.select("p figure").isEmpty());
    assertEquals("text-align:center", doc.getElementById("frame").attr("style"));
  }

  @Test
  void doesNotSkipInterveningContentOrConvertHeadingsAndComplexContainers() {
    var doc = convert("<img>正文<p>图1 不能跨文本</p>"
        + "<img><p>说明</p><p>图2 不能跨元素</p>"
        + "<img><h2>图3 章节标题</h2>"
        + "<img><div>图4<p>另一段正文</p></div>"
        + "<img><p>图片说明</p>");
    assertTrue(doc.select("figure").isEmpty());
  }

  @Test
  void flattensExistingCaptionWithoutWrappingFigureAgain() {
    var doc = convert("<figure><img><figcaption style='color:red'>"
        + "<p>图1 <span>原图注</span> &lt;文字&gt; &amp; 说明</p></figcaption></figure>");
    assertEquals(1, doc.select("figure").size());
    assertEquals(1, doc.select("figcaption").size());
    var caption = doc.selectFirst("figcaption");
    assertTrue(caption.children().isEmpty());
    assertEquals("图1 原图注 <文字> & 说明", caption.text());
    assertEquals("color:red", caption.attr("style"));
    assertTrue(caption.html().contains("&lt;文字&gt;"));
    String html = doc.body().html();
    FigureProcessor.wrapCaptions(doc);
    assertEquals(html, doc.body().html());
  }
}
