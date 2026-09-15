package cn.p4u.eth.callback;

/**
 * 单个顶层内容项解析完成时的回调记录，携带内容项本体及拆出的关键字段。
 *
 * @param item 本次解析完成的内容项
 * @param target 该内容项的目标路径，可带片段；无链接分组节点为 null
 * @param label 目录文字
 * @param content 已处理的 HTML 片段
 */
public record CallBackRecord<T>(T item, String target, String label, String content) {}
