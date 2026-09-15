package cn.p4u.eth;

import cn.p4u.eth.support.EpubFixture;
import cn.p4u.eth.support.TocTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class EpubConverterTest {
  @TempDir Path directory;

  @Test
  void delegatesResourcesAndUsesHandlerUrl() throws Exception {
    Path epubPath = EpubFixture.create(directory);
    var result =
        new EpubConverter()
            .convert(
                epubPath,
                resource -> {
                  return "data:"
                      + resource.mediaType()
                      + ";base64,"
                      + Base64.getEncoder().encodeToString(resource.content());
                },
                true,
                (count, total, record) -> {
                  System.out.println("进度:" + calcProgress(count, total));
                  System.out.println("type:" + record.label());
                });

    String html = TocTestSupport.content(result);
    var document = org.jsoup.Jsoup.parse(html);
    assertEquals("data:image/png;base64,AQID", document.selectFirst("img").attr("src"));
    assertTrue(document.select("[class], [id]").isEmpty());
  }

  /**
   * 计算处理进度
   *
   * @param current
   * @param total
   * @return
   */
  public static String calcProgress(int current, int total) {
    if (total == 0) {
      return "0.00%";
    }
    BigDecimal c = BigDecimal.valueOf(current);
    BigDecimal t = BigDecimal.valueOf(total);
    // 乘以100再除以总数，保留2位小数，四舍五入
    BigDecimal percent = c.multiply(BigDecimal.valueOf(100)).divide(t, 2, RoundingMode.HALF_UP);
    return percent + "%";
  }
}
