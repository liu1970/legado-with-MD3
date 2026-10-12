package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.domain.gateway.BookshelfAutoGroupGateway
import io.legado.app.domain.model.BookshelfAutoGroupApplyResult
import io.legado.app.domain.model.BookshelfAutoGroupBook
import io.legado.app.domain.model.BookshelfAutoGroupErrorReason
import io.legado.app.domain.model.BookshelfAutoGroupException
import io.legado.app.domain.model.BookshelfAutoGroupOptions
import io.legado.app.domain.model.BookshelfAutoGroupPlan
import io.legado.app.domain.model.BookshelfAutoGroupSource
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookshelfAutoGroupRepository(
    private val database: AppDatabase,
) : BookshelfAutoGroupGateway {

    override suspend fun loadSource(): BookshelfAutoGroupSource = withContext(Dispatchers.IO) {
        val groupDao = database.bookGroupDao
        val allGroups = groupDao.all
        val publicGroups = allGroups.filter { it.isUserGroup() && !it.isPrivate }
        val privateGroupIds = allGroups
            .asSequence()
            .filter { it.isUserGroup() && it.isPrivate }
            .map { it.groupId }
            .toSet()
        val books = database.bookDao.getAll()
            .filterNot { it.isNotShelf }
            // Private shelf metadata must not be sent to an external AI provider.
            .filter { book -> book.groupId !in privateGroupIds }
            .sortedWith(compareBy<Book> { it.name }.thenBy { it.author })
        BookshelfAutoGroupSource(
            books = books.map { book ->
                BookshelfAutoGroupBook(
                    bookUrl = book.bookUrl,
                    name = book.name,
                    author = book.author,
                    intro = book.groupingIntro(),
                    kind = book.customTag ?: book.kind.orEmpty(),
                    currentGroupNames = groupDao.getGroupNames(book.groupId),
                )
            },
            existingGroupNames = publicGroups
                .map(BookGroup::groupName)
                .filter(String::isNotBlank),
        )
    }

    override suspend fun applyPlan(
        plan: BookshelfAutoGroupPlan,
        options: BookshelfAutoGroupOptions,
    ): BookshelfAutoGroupApplyResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val groupDao = database.bookGroupDao
            val bookDao = database.bookDao
            val allGroups = groupDao.all
            // groupId 改为单一 ID 后：私密分组改为 Set<groupId>，判断用 contains
            val privateGroupIds = allGroups
                .asSequence()
                .filter { it.isUserGroup() && it.isPrivate }
                .map { it.groupId }
                .toSet()
            val publicGroupIds = allGroups
                .asSequence()
                .filter { it.isUserGroup() && !it.isPrivate }
                .map { it.groupId }
                .toSet()
            val existingGroups = allGroups
                .filter { it.isUserGroup() && !it.isPrivate }
                .associateBy(BookGroup::groupName)
                .toMutableMap()
            var createdGroupCount = 0
            var reusedGroupCount = 0
            var skippedBookCount = 0

            // Resolve current rows before creating groups so stale plans cannot create empty groups.
            val applicableGroups = plan.groups.mapNotNull { group ->
                val books = group.books.mapNotNull { plannedBook ->
                    val entity = bookDao.getBook(plannedBook.bookUrl)
                    // Re-check membership because the user may have grouped the book after analysis.
                    val shouldSkip = entity == null || (
                        options.incrementalOnly && entity.groupId in publicGroupIds
                    )
                    if (shouldSkip) {
                        skippedBookCount++
                        null
                    } else {
                        entity
                    }
                }
                group.takeIf { books.isNotEmpty() }?.let { it to books }
            }

            val newGroupCount = applicableGroups
                .asSequence()
                .map { (group, _) -> group.name }
                .distinct()
                .count { it !in existingGroups }
            val occupiedGroupCount = allGroups.count { it.isUserGroup() }
            val availableGroupSlots = (MAX_USER_GROUPS - occupiedGroupCount).coerceAtLeast(0)
            if (newGroupCount > availableGroupSlots) {
                throw BookshelfAutoGroupException(
                    BookshelfAutoGroupErrorReason.GroupCapacityExceeded
                )
            }

            val targetGroupIds = applicableGroups.associate { (group, _) ->
                val existing = existingGroups[group.name]
                val groupId = if (existing != null) {
                    reusedGroupCount++
                    existing.groupId
                } else {
                    val newGroupId = groupDao.getUnusedId()
                    if (newGroupId == 0L || newGroupId == Long.MIN_VALUE) {
                        throw BookshelfAutoGroupException(
                            BookshelfAutoGroupErrorReason.GroupCapacityExceeded
                        )
                    }
                    val newGroup = BookGroup(
                        groupId = newGroupId,
                        groupName = group.name,
                        order = groupDao.maxOrder.plus(1),
                        enableRefresh = true,
                        show = true,
                        bookSort = -1,
                        isPrivate = false,
                    )
                    // 清除复用历史 groupId 时留在这本书上的旧分配。
                    bookDao.removeGroup(newGroupId)
                    groupDao.insert(newGroup)
                    existingGroups[group.name] = newGroup
                    createdGroupCount++
                    newGroupId
                }
                group.key to groupId
            }

            val updates = applicableGroups.flatMap { (group, books) ->
                val targetGroupId = targetGroupIds[group.key] ?: return@flatMap emptyList()
                books.mapNotNull { entity ->
                    // Auto-grouping replaces public memberships but never removes private ones.
                    // 书的 groupId 已在私密分组中 → 跳过（私密优先）；否则改为目标分组。
                    if (entity.groupId in privateGroupIds) null
                    else entity.takeIf { it.groupId != targetGroupId }
                        ?.copy(groupId = targetGroupId)
                }
            }
            if (updates.isNotEmpty()) {
                bookDao.update(*updates.toTypedArray())
            }

            BookshelfAutoGroupApplyResult(
                createdGroupCount = createdGroupCount,
                reusedGroupCount = reusedGroupCount,
                updatedBookCount = updates.size,
                ignoredBookCount = plan.ignoredBooks.size + skippedBookCount,
            )
        }
    }

    private fun Book.groupingIntro(): String {
        return (customIntro ?: intro ?: listIntro).orEmpty()
            .replace(Regex("\\s+"), " ")
            .take(MAX_INTRO_CHARS)
    }

    private fun BookGroup.isUserGroup(): Boolean {
        return groupId > 0 || groupId == Long.MIN_VALUE
    }

    private companion object {
        const val MAX_INTRO_CHARS = 320
        const val MAX_USER_GROUPS = Long.SIZE_BITS
    }
}
