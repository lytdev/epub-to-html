package cn.p4u.eth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipFile;

/** 目录驱动的解析流水线：读取目录、填充 content、关闭归档并返回树。 不承担展示或持久化职责，资源缓存仅在本次调用内有效。 */
final class ConversionPipeline {
  /**
   * 在归档打开期间完成章节和资源读取，返回不持有文件句柄的内容树，并按顶层内容项触发进度回调。
   *
   * @param epub 由门面校验非空的 EPUB 文件路径
   * @param handler 调用方资源策略，由媒体处理器同步调用
   * @param removeClasses 是否在内容处理结束后同时删除 class 和 id
   * @param callback 进度回调，可为 null；解压与目录阶段失败序号记为 0，内容阶段由读取器逐项通知
   * @return 已填充内容的根节点列表，无目录时返回按 spine 顺序创建的节点
   * @throws IOException 归档、章节读取或资源处理失败时抛出；回调 onError 之后仍向上传播
   */
  List<TocItem> convert(
      Path epub,
      EpubResourceHandler handler,
      boolean removeClasses,
      FileParseCallback<TocItem> callback)
      throws IOException {
    List<TocItem> resultList;
    // 解压（打开 ZIP）失败属于内容项之前，序号记为 0。
    ZipFile zip;
    try {
      zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      if (callback != null) callback.onError(ex, 0);
      throw ex;
    }
    try (zip) {
      try {
        PackageData pkg = PackageReader.readPackage(zip, PackageReader.packagePath(zip));
        resultList = NavigationReader.readToc(zip, pkg);
        // 无目录时仍使用相同内容模型，调用方无需区分两种读取流程。
        if (resultList.isEmpty()) {
          resultList = new ArrayList<>();
          for (String path : pkg.spine()) resultList.add(new TocItem(path, 1, ""));
        }
      } catch (IOException | RuntimeException ex) {
        // 包描述与目录解析仍未进入内容项，序号同样记为 0。
        if (callback != null) callback.onError(ex, 0);
        throw ex;
      }
      // 如果没有读取到内容结构，则直接返回空数据。
      if (resultList.isEmpty()) {
        if (callback != null) callback.onComplete(0, "文件处理完成");
        return resultList;
      }
      // 各章节共享本次资源缓存，避免重复上传；不跨调用保留缓存。
      // 内容阶段逐项触发 onLineParsed / onError，读取器返回顶层内容项总数。
      int total =
          new TocContentReader(removeClasses)
              .read(zip, resultList, handler, new LinkedHashMap<>(), callback);
      if (callback != null) callback.onComplete(total, "文件处理完成");
      // 正文已保存在 content 中，关闭 ZIP 不影响调用方使用。
      return resultList;
    }
  }
}
