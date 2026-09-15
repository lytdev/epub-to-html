package cn.p4u.eth;

import java.util.List;

/**
 * OPF 解析结果，由 {@link PackageReader} 创建，供流水线和导航读取器使用。
 *
 * <p>record 自动生成构造器与同名访问器，例如 spine()。字段引用不能重新赋值，但列表本身未防御性复制。
 * 此类型仅在包内使用，调用方不应在转换期间修改列表。
 *
 * @param spine 按阅读顺序排列的章节归档路径，不是按文件名排序的结果
 * @param ncx NCX 导航文件归档路径；没有对应清单项时为 null
 * @param nav EPUB 3 导航文件归档路径；没有对应清单项时为 null
 */
record PackageData(List<String> spine, String ncx, String nav) {}
