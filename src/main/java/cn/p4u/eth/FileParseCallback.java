package cn.p4u.eth;

/**
 * 转换进度回调：在逐项解析完成、发生异常或整体完成时接收通知。
 *
 * <p>回调由 {@link EpubConverter} 在转换过程中同步触发，用于记录进度、更新界面或审计。
 * 传入 {@code null} 表示不接收任何通知。实现须保持轻量，异常回调后原异常仍会向上传播。</p>
 *
 * @param <T> 内容项类型，本库使用 {@link TocItem}
 */
public interface FileParseCallback<T> {
  /**
   * 在一个顶层内容项解析完成后接收进度通知。
   *
   * @param count 已成功解析的数量，从 1 递增
   * @param total 本次需要解析的总数量
   * @param record 本次解析完成的内容项
   */
  void onLineParsed(int count, int total, CallBackRecord<T> record);

  /**
   * 解析或转换出现异常时调用。
   *
   * @param ex 转换过程中发生的异常
   * @param line 当前内容项序号；解压阶段失败时为 0
   */
  default void onError(Exception ex, int line) {
    System.err.println("第" + line + "项出错：" + ex.getMessage());
  }

  /**
   * 所有内容解析并渲染为 HTML 后调用。
   *
   * @param total 已处理的顶层内容项总数
   * @param message 完成信息
   */
  default void onComplete(int total, String message) {
    System.out.println("文件处理完成");
  }
}
