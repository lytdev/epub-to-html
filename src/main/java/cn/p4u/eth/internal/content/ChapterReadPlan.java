package cn.p4u.eth.internal.content;

import cn.p4u.eth.internal.archive.EpubPaths;
import cn.p4u.eth.model.TocItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将目录树转换为按文件分组的读取计划，不读取 ZIP 或修改 HTML。
 *
 * <p>目录顺序与文件读取顺序是两件事：同一文件只解析一次，结果仍写回原节点，
 * 因此分组不会改变调用方看到的父子关系或兄弟顺序。
 */
final class ChapterReadPlan {
  private ChapterReadPlan() {}

  /** 重置旧内容并按先序遍历收集目标，返回按首次出现顺序排列的文件分组。 */
  static Map<String, List<TocItem>> collect(List<TocItem> roots) {
    Map<String, List<TocItem>> files = new LinkedHashMap<>();
    collectInto(roots, files);
    return files;
  }

  private static void collectInto(List<TocItem> items, Map<String, List<TocItem>> files) {
    for (TocItem item : items) {
      item.setContent("");
      if (item.getTarget() != null && !item.getTarget().isBlank()) {
        files.computeIfAbsent(EpubPaths.targetFile(item.getTarget()),
            ignored -> new ArrayList<>()).add(item);
      }
      // 组合结构允许对普通章节和无链接分组使用同一套遍历规则。
      collectInto(item.getChildren(), files);
    }
  }
}
