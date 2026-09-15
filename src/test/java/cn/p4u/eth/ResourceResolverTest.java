package cn.p4u.eth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证资源会话的生命周期与失败契约，而不是验证私有实现。 */
class ResourceResolverTest {
  @TempDir Path directory;

  @Test
  void cachesSuccessWithinSessionButNotAcrossSessions() throws Exception {
    try (var zip = new ZipFile(EpubFixture.create(directory).toFile())) {
      var calls = new AtomicInteger();
      EpubResourceHandler handler = resource -> "url-" + calls.incrementAndGet();
      var first = new ResourceResolver(zip, handler, new LinkedHashMap<>());
      assertEquals("url-1", first.resolve(EpubFixture.IMAGE));
      assertEquals("url-1", first.resolve(EpubFixture.IMAGE));
      var second = new ResourceResolver(zip, handler, new LinkedHashMap<>());
      assertEquals("url-2", second.resolve(EpubFixture.IMAGE));
      assertNull(first.resolve("missing.png"));
      assertEquals(2, calls.get());
    }
  }

  @Test
  void failedResultsAreNotCached() throws Exception {
    try (var zip = new ZipFile(EpubFixture.create(directory).toFile())) {
      var urls = new LinkedHashMap<String, String>();
      var calls = new AtomicInteger();
      var resources = new ResourceResolver(zip, resource -> {
        int attempt = calls.incrementAndGet();
        if (attempt == 1) throw new IOException("upload failed");
        if (attempt == 2) return " ";
        if (attempt == 3) return null;
        return "uploaded.png";
      }, urls);
      for (int attempt = 0; attempt < 3; attempt++) {
        assertThrows(IOException.class, () -> resources.resolve(EpubFixture.IMAGE));
        assertTrue(urls.isEmpty());
      }
      assertEquals("uploaded.png", resources.resolve(EpubFixture.IMAGE));
      assertEquals("uploaded.png", resources.resolve(EpubFixture.IMAGE));
      assertEquals(4, calls.get());
    }
  }
}
