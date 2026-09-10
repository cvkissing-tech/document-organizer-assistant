package com.wenxu.app.core.storage

import com.wenxu.app.core.index.DocumentTypePolicy
import java.util.ArrayDeque

/** A small, Android-independent view of a node returned by a document provider. */
interface TreeNode {
    val uri: String
    val name: String
    val isDirectory: Boolean
    val isFile: Boolean

    fun children(): List<TreeNode>
}

/**
 * Breadth-first traversal for an authorized document tree.
 *
 * The walker emits supported document files only. Directories are tracked by URI so a provider
 * exposing the same virtual directory more than once cannot create an endless traversal.
 */
class DocumentTreeWalker(
    private val excludedDirectoryNames: Set<String> = setOf(TRASH_DIRECTORY_NAME),
) {
    fun walk(root: TreeNode): Sequence<TreeNode> = sequence {
        if (root.isFile) {
            if (DocumentTypePolicy.supports(root.name)) yield(root)
            return@sequence
        }
        if (!root.isDirectory || root.name in excludedDirectoryNames) return@sequence

        val directories = ArrayDeque<TreeNode>()
        val visitedDirectories = mutableSetOf<String>()
        directories.addLast(root)

        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            if (!visitedDirectories.add(directory.uri)) continue

            directory.children().forEach { child ->
                when {
                    child.isDirectory && child.name !in excludedDirectoryNames -> {
                        directories.addLast(child)
                    }

                    child.isFile && DocumentTypePolicy.supports(child.name) -> yield(child)
                }
            }
        }
    }

    companion object {
        const val TRASH_DIRECTORY_NAME = ".文序回收站"
    }
}
