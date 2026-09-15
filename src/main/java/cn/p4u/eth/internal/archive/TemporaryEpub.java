package cn.p4u.eth.internal.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 输入流到 ZIP 文件的内部适配器，集中管理临时文件生命周期，不关闭调用方输入流。 */
public final class TemporaryEpub implements AutoCloseable {
  private final Path path;

  /** @param path 当前实例负责删除的临时文件 */
  private TemporaryEpub(Path path) {
    this.path = path;
  }

  /**
   * 暂存输入内容，供 ZipFile 按归档路径随机读取。
   *
   * @param input 从当前位置读取的 EPUB 流，调用方负责关闭
   * @return 拥有临时文件清理责任的适配器
   * @throws IOException 文件创建、流复制或失败清理发生错误时抛出
   */
  public static TemporaryEpub copyOf(InputStream input) throws IOException {
    Path path = Files.createTempFile("epub2html-", ".epub");
    TemporaryEpub temporary = new TemporaryEpub(path);
    try {
      Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
      return temporary;
    } catch (IOException | RuntimeException | Error failure) {
      // 复制失败时还未返回对象，必须在这里清理；清理异常不覆盖原始错误。
      try {
        temporary.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /** @return 当前临时 EPUB 文件路径，仅在 close 前有效 */
  public Path path() {
    return path;
  }

  /**
   * 删除当前临时文件；重复关闭时不报文件不存在错误。
   *
   * @throws IOException 文件无法删除时抛出
   */
  @Override
  public void close() throws IOException {
    Files.deleteIfExists(path);
  }
}
