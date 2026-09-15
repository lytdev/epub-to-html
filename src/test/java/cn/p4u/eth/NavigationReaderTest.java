package cn.p4u.eth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 验证真正的父子关系，而不仅是扁平节点的 level 数值。 */
class NavigationReaderTest {
  @Test
  void ncxKeepsNestedChildrenAndSiblingOrder() throws Exception {
    var roots = NavigationReader.readNcx("""
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
          <navPoint><navLabel><text>第一章</text></navLabel><content src="a.xhtml"/>
            <navPoint><navLabel><text>第一节</text></navLabel><content src="a.xhtml#s"/>
              <navPoint><navLabel><text>小节</text></navLabel><content src="a.xhtml#t"/></navPoint>
            </navPoint>
            <navPoint><navLabel><text>第二节</text></navLabel><content src="b.xhtml"/></navPoint>
          </navPoint>
          <navPoint><navLabel><text>第二章</text></navLabel><content src="c.xhtml"/></navPoint>
        </navMap></ncx>
        """, "OPS/toc.ncx");
    assertEquals(2, roots.size());
    assertEquals("第二章", roots.get(1).getLabel());
    var children = roots.get(0).getChildren();
    assertEquals(2, children.size());
    assertEquals("第一节", children.get(0).getLabel());
    assertEquals("第二节", children.get(1).getLabel());
    assertEquals("OPS/a.xhtml#t", children.get(0).getChildren().get(0).getTarget());
    assertEquals(3, children.get(0).getChildren().get(0).getLevel());
    assertTrue(roots.get(1).getChildren().isEmpty());
  }

  @Test
  void groupDoesNotBorrowChildLabelOrTarget() throws Exception {
    var roots = NavigationReader.readNcx("""
        <ncx xmlns="urn:ncx"><navMap><navPoint>
          <navPoint><navLabel><text>子目录</text></navLabel><content src="a.xhtml"/></navPoint>
        </navPoint></navMap></ncx>
        """, "OPS/toc.ncx");
    assertEquals(1, roots.size());
    assertNull(roots.get(0).getTarget());
    assertEquals("", roots.get(0).getLabel());
    assertEquals("子目录", roots.get(0).getChildren().get(0).getLabel());
    assertEquals(2, roots.get(0).getChildren().get(0).getLevel());
  }

  @Test
  void epub3AlsoReturnsTreeIncludingGroups() {
    var roots = NavigationReader.readNav("""
        <html><body>
        <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">封面</a></li></ol></nav>
        <nav epub:type="toc"><ol><li><span>第一篇</span><ol>
          <li><a href="a.xhtml">第一章</a><ol><li><a href="a.xhtml#s">小节</a></li></ol></li>
          <li><a href="b.xhtml">第二章</a></li>
        </ol></li></ol></nav>
        </body></html>
        """, "OPS/nav.xhtml");
    assertEquals(1, roots.size());
    assertEquals("第一篇", roots.get(0).getLabel());
    assertNull(roots.get(0).getTarget());
    assertEquals(2, roots.get(0).getChildren().size());
    assertEquals(3, roots.get(0).getChildren().get(0).getChildren().get(0).getLevel());
  }
}
