package cn.p4u.eth.util;

/** 字符串辅助判断，包括 Unicode 空白和本项目约定的不可见字符。 */
public class StringUtil {
  /**
   * 字符串是否为空白，空白的定义如下：
   *
   * <ol>
   *   <li>{@code null}
   *   <li>空字符串：{@code ""}
   *   <li>空格、全角空格、制表符、换行符，等不可见字符
   * </ol>
   *
   * <p>例：
   *
   * <ul>
   *   <li>{@code StringUtil.isBlank(null) // true}
   *   <li>{@code StringUtil.isBlank("") // true}
   *   <li>{@code StringUtil.isBlank(" \t\n") // true}
   *   <li>{@code StringUtil.isBlank("abc") // false}
   * </ul>
   *
   * @param str 被检测的字符串
   * @return 若为空白，则返回 true
   */
  public static boolean isBlank(CharSequence str) {
    final int length;
    if ((str == null) || ((length = str.length()) == 0)) {
      return true;
    }

    for (int i = 0; i < length; i++) {
      // 只要有一个非空字符即为非空字符串
      if (!isBlankChar(str.charAt(i))) {
        return false;
      }
    }

    return true;
  }
  /**
   * 是否空白符<br>
   * 空白符包括空格、制表符、全角空格和不间断空格<br>
   *
   * @param c 字符
   * @return 是否空白符
   * @see Character#isWhitespace(int)
   * @see Character#isSpaceChar(int)
   */
  public static boolean isBlankChar(int c) {
    return Character.isWhitespace(c)
            || Character.isSpaceChar(c)
            || c == '\ufeff'
            || c == '\u202a'
            || c == '\u0000'
            // issue#I5UGSQ，Hangul Filler
            || c == '\u3164'
            // Braille Pattern Blank
            || c == '\u2800'
            // MONGOLIAN VOWEL SEPARATOR
            || c == '\u180e';
  }
}
