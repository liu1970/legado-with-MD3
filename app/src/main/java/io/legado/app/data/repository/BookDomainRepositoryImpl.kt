package io.legado.app.data.repository

import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.domain.model.BookGroupAssignment
import io.legado.app.domain.model.CacheableBook
import io.legado.app.domain.model.DeletableBook
import io.legado.app.domain.repository.BookDomainRepository
import io.legado.app.help.book.isLocal

class BookDomainRepositoryImpl(
    private val bookDao: BookDao,
    private val bookChapterDao: BookChapterDao
) : BookDomainRepository {

    private suspend fun getBooks(bookUrls: Set<String>): List<Book> {
        if (bookUrls.isEmpty()) return emptyList()
        return bookUrls.mapNotNull { bookDao.getBook(it) }
    }

    override suspend fun getCacheableBooks(bookUrls: Set<String>): List<CacheableBook> {
        if (bookUrls.isEmpty()) return emptyList()
        return bookDao.getCacheableBooks(bookUrls)
    }

    override suspend fun getDeletableBooks(bookUrls: Set<String>): List<DeletableBook> {
        return getBooks(bookUrls).map { book ->
            DeletableBook(
                bookUrl = book.bookUrl,
                origin = book.origin,
                isLocal = book.isLocal
            )
        }
    }

    override suspend fun getBookGroupAssignments(bookUrls: Set<String>): List<BookGroupAssignment> {
        return getBooks(bookUrls).map { book ->
            BookGroupAssignment(
                bookUrl = book.bookUrl,
                groupId = book.groupId
            )
        }
    }

    override suspend fun updateBookGroups(assignments: List<BookGroupAssignment>) {
        if (assignments.isEmpty()) return
        val groups = assignments.associateBy { it.bookUrl }
        val books = getBooks(groups.keys).mapNotNull { book ->
            groups[book.bookUrl]?.let { assignment ->
                if (book.groupId == assignment.groupId) null
                else book.copy(groupId = assignment.groupId)
            }
        }
        if (books.isNotEmpty()) {
            bookDao.update(*books.toTypedArray())
        }
    }

    override suspend fun removeGroupFromBooks(groupId: Long) {
        bookDao.removeGroup(groupId)
    }

    override suspend fun deleteBooks(bookUrls: Set<String>) {
        val books = getBooks(bookUrls)
        if (books.isNotEmpty()) {
            bookDao.delete(*books.toTypedArray())
        }
    }

    override suspend fun deleteChaptersByBook(bookUrl: String) {
        bookChapterDao.delByBook(bookUrl)
    }
}
