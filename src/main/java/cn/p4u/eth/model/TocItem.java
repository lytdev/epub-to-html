package cn.p4u.eth.model;

import java.util.List;
import java.util.ArrayList;

/**
 * 组合结构（Composite）：章节与分组使用同一种节点，通过 children 递归组成目录树。
 *
 * <p>根列表只放一级节点，子目录放入 children。叶子 children 为空列表；
 * 无正文链接的分组节点 target 为 null。保留 JavaBean getter/setter 与原访问器别名。
 * 调用方维护树时不得引入环，且应保持 level 与树深度一致。
 */
public class TocItem {
  /**  target 目标章节归档路径，可带 # 后的元素 id，例如 OPS/a.xhtml#intro */
  private String target;
  /**  level 原始导航层级，从 1 开始；标题处理时才限制到 HTML 的 1 至 6 */
  private Integer level;
  /** 目录显示文字，与 content 分离；转换流程不据此生成正文标题。 */
  private String label;
  /** 当前目录项自己的正文 HTML 片段，不插入 label 标题，不包含 children 的内容；原正文标题保留。 */
  private String content = "";
  /** 直接子目录；叶子节点为空列表，不为 null。不得加入自身或祖先形成环。 */
  private List<TocItem> children = new ArrayList<>();

  /**
   * 创建没有子目录的节点。
   *
   * @param target 归档目标路径，可带片段；分组节点可为 null
   * @param level 原始目录层级，根为 1
   * @param label 目录文字
   */
  public TocItem(String target, Integer level, String label) {
    this.target = target;
    this.level = level;
    this.label = label;
  }

  /** @return 当前目标路径；无链接分组节点为 null */
  public String getTarget() {
    return target;
  }

  /** @return 原始目录层级，不截断超过六层的值 */
  public Integer getLevel() {
    return level;
  }

  /** @return 当前节点的目录文字 */
  public String getLabel() {
    return label;
  }

  /** @return 本节点已处理的 HTML 片段，未读取或没有正文时为空字符串 */
  public String getContent() {
    return content;
  }

  /**
   * 保存本节点内容；子节点独立保存，由调用方决定是否递归合并。
   *
   * @param content HTML 片段，不包含 html/body 外壳；null 转为空字符串
   */
  public void setContent(String content) {
    this.content = content == null ? "" : content;
  }

  /** @return 直接子目录的可变列表；叶子为空列表，不为 null */
  public List<TocItem> getChildren() {
    return children;
  }

  /** @param target 新目标路径；无链接时为 null */
  public void setTarget(String target) {
    this.target = target;
  }

  /** @param level 新层级，应与节点在树中的深度一致 */
  public void setLevel(Integer level) {
    this.level = level;
  }

  /** @param label 新目录文字 */
  public void setLabel(String label) {
    this.label = label;
  }

  /**
   * 替换直接子目录，复制列表容器但不复制节点。
   *
   * @param children 子节点列表；null 转为空列表，不得含 null 节点或循环引用
   */
  public void setChildren(List<TocItem> children) {
    this.children = children == null ? new ArrayList<>() : new ArrayList<>(children);
  }

  /** @return 当前节点的目标路径；无链接的分组节点为 null */
  public String target() { return target; }

  /** @return 当前节点的层级，根节点从 1 开始 */
  public Integer level() { return level; }

  /** @return 当前节点的目录文字 */
  public String label() { return label; }
}
