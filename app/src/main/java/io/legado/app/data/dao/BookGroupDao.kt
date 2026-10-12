package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Query("select * from book_groups where groupId = :id")
    fun getByID(id: Long): BookGroup?

    @Query("select * from book_groups where groupName = :groupName")
    fun getByName(groupName: String): BookGroup?

    @Query("SELECT * FROM book_groups ORDER BY `order`")
    fun flowAll(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups WHERE show > 0 ORDER BY `order`")
    fun flowShow(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups where groupId >= 0 ORDER BY `order`")
    fun flowSelect(): Flow<List<BookGroup>>

    @get:Query("SELECT sum(groupId) FROM book_groups where groupId >= 0")
    val idsSum: Long

    @get:Query("SELECT MAX(groupId) FROM book_groups where groupId > 0")
    val maxGroupId: Long

    @get:Query("SELECT MAX(`order`) FROM book_groups where groupId >= 0")
    val maxOrder: Int

    @get:Query("SELECT * FROM book_groups ORDER BY `order`")
    val all: List<BookGroup>

    @get:Query("select count(*) < 64 from book_groups where groupId >= 0 or groupId == ${Long.MIN_VALUE}")
    val canAddGroup: Boolean

    @Query("update book_groups set show = 1 where groupId = :groupId")
    fun enableGroup(groupId: Long)

    @Query("select groupName from book_groups where groupId = :id")
    fun getGroupNames(id: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookGroup: BookGroup)

    @androidx.room.Transaction
    fun replaceAll(bookGroups: List<BookGroup>) {
        deleteAll()
        if (bookGroups.isNotEmpty()) {
            insert(*bookGroups.toTypedArray())
        }
    }

    @Query("DELETE FROM book_groups")
    fun deleteAll()

    @Update
    fun update(vararg bookGroup: BookGroup)

    @Delete
    fun delete(vararg bookGroup: BookGroup)

    @Query("UPDATE book_groups SET cover = NULL WHERE groupId = :groupId")
    fun clearCover(groupId: Long)

    /**
     * groupId 是 single-id 后，新分支用 [maxGroupId] + 1 取下一个未用 ID；
     * `canAddGroup` 仍以"总数 < 64"为上限，保证个人书架数量不会无限扩张。
     */
    fun getUnusedId(): Long {
        val max = maxGroupId
        return max + 1L
    }
}
