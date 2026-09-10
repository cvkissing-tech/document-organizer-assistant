package com.wenxu.app.core.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentDiscoveryTest {
    @Test
    fun walksNestedDirectoriesAndReturnsOnlySupportedDocuments() {
        val root = FakeTreeNode.directory("content://root", "授权根目录")
        val wechat = FakeTreeNode.directory("content://root/wechat", "微信")
        val course = FakeTreeNode.directory("content://root/wechat/course", "课程")
        val pictures = FakeTreeNode.directory("content://root/pictures", "图片")
        root.add(
            FakeTreeNode.file("content://root/root.pdf", "根目录.pdf"),
            wechat,
            pictures,
        )
        wechat.add(course)
        course.add(FakeTreeNode.file("content://root/wechat/course/slides", "课件.pptx"))
        pictures.add(FakeTreeNode.file("content://root/pictures/cover", "封面.jpg"))

        val discovered = DocumentTreeWalker().walk(root).toList()

        assertThat(discovered.map { it.name }).containsExactly("根目录.pdf", "课件.pptx").inOrder()
    }

    @Test
    fun skipsTrashDirectoryAndDoesNotVisitDirectoryTwice() {
        val root = FakeTreeNode.directory("content://root", "授权根目录")
        val repeated = FakeTreeNode.directory("content://root/repeated", "课程")
        val repeatedAlias = FakeTreeNode.directory("content://root/repeated", "课程别名")
        val trash = FakeTreeNode.directory("content://root/trash", ".文序回收站")
        root.add(repeated, repeatedAlias, trash)
        repeated.add(FakeTreeNode.file("content://root/repeated/a", "讲义.docx"))
        repeatedAlias.add(FakeTreeNode.file("content://root/repeated/b", "不应重复扫描.pdf"))
        trash.add(FakeTreeNode.file("content://root/trash/deleted", "已删除.pdf"))

        val discovered = DocumentTreeWalker().walk(root).toList()

        assertThat(discovered.map { it.name }).containsExactly("讲义.docx")
        assertThat(repeated.childrenCallCount).isEqualTo(1)
        assertThat(repeatedAlias.childrenCallCount).isEqualTo(0)
        assertThat(trash.childrenCallCount).isEqualTo(0)
    }
}

private class FakeTreeNode private constructor(
    override val uri: String,
    override val name: String,
    override val isDirectory: Boolean,
    override val isFile: Boolean,
) : TreeNode {
    private val childNodes = mutableListOf<TreeNode>()
    var childrenCallCount: Int = 0
        private set

    fun add(vararg nodes: TreeNode) {
        childNodes += nodes
    }

    override fun children(): List<TreeNode> {
        childrenCallCount++
        return childNodes.toList()
    }

    companion object {
        fun directory(uri: String, name: String) = FakeTreeNode(uri, name, true, false)
        fun file(uri: String, name: String) = FakeTreeNode(uri, name, false, true)
    }
}
