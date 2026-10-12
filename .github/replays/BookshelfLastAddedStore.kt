package io.legado.app.help.config

import io.legado.app.data.entities.BookGroup
import java.util.concurrent.atomic.AtomicLong

/**
 * 详情页"离线缓存"按钮使用的"上一次加入的书架"存储。
 *
 * **进程内**原子长整型，**不持久化**：重启后回落到 [BookGroup.IdAll]。
 * 这是用户预期："第一本书默认加入全部"，新会话本就以 IdAll 起手，所以进程级即可。
 *
 * 写入点：
 * - 用户在任意 BookDetail 应用调用 `addToBookshelf` / 加入书架的 Sheet 成功路径
 *
 * 读取点：
 * - `BookInfoViewModel.onOfflineCacheClick` 在未入架时读取，作为自动入架的目标书架
 */
object BookshelfLastAddedStore {

    private val lastAddedGroupId = AtomicLong(BookGroup.IdAll)

    /**
     * 返回最近一次成功"加入书架"的目标书架；若从未记录过（本次进程或之前从未入架过），
     * 回落到 [BookGroup.IdAll]。
     */
    fun lastAddedGroupIdOrDefault(): Long {
        val recorded = lastAddedGroupId.get()
        return if (recorded == 0L) BookGroup.IdAll else recorded
    }

    /**
     * 记录最近一次成功加入的书架。`groupId == 0` 表示"无默认目标"，调用方应跳到
     * [BookGroup.IdAll]，不要把 0 写入——保留 0 作为"未初始化"哨兵。
     */
    fun recordLastAdded(groupId: Long) {
        if (groupId == 0L) return
        lastAddedGroupId.set(groupId)
    }
}