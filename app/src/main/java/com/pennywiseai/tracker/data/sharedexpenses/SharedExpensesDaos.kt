package com.pennywiseai.tracker.data.sharedexpenses

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedGroupDao {
    @Query("SELECT * FROM shared_groups WHERE deleted_at IS NULL ORDER BY created_at")
    fun observeAll(): Flow<List<SharedGroupEntity>>

    @Query("SELECT * FROM shared_groups WHERE id = :id") fun observe(id: String): Flow<SharedGroupEntity?>

    @Query("SELECT * FROM shared_groups WHERE id = :id") suspend fun byId(id: String): SharedGroupEntity?

    @Query("SELECT id FROM shared_groups") suspend fun ids(): List<String>

    @Query("SELECT * FROM shared_groups") suspend fun all(): List<SharedGroupEntity>

    @Upsert suspend fun upsert(group: SharedGroupEntity)

    @Query("UPDATE shared_groups SET sync_rev = :rev, my_member_id = :myMemberId WHERE id = :id")
    suspend fun setCursor(id: String, rev: Long, myMemberId: String)

    @Query("DELETE FROM shared_groups WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM shared_groups") suspend fun deleteAll()
}

@Dao
interface SharedMemberDao {
    @Query("SELECT * FROM shared_members WHERE group_id = :groupId ORDER BY display_name COLLATE NOCASE")
    fun observe(groupId: String): Flow<List<SharedMemberEntity>>

    @Query("SELECT * FROM shared_members WHERE group_id = :groupId") suspend fun forGroup(groupId: String): List<SharedMemberEntity>

    @Upsert suspend fun upsert(member: SharedMemberEntity)

    @Query("DELETE FROM shared_members WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_members") suspend fun deleteAll()
}

@Dao
interface SharedExpenseDao {
    @Query("SELECT * FROM shared_expenses WHERE group_id = :groupId ORDER BY date DESC, created_at DESC")
    fun observe(groupId: String): Flow<List<SharedExpenseEntity>>

    @Query("SELECT * FROM shared_expenses WHERE id = :id") fun observeOne(id: String): Flow<SharedExpenseEntity?>

    @Query("SELECT p.* FROM shared_expense_payers p JOIN shared_expenses e ON e.id = p.expense_id WHERE e.group_id = :groupId ORDER BY p.position")
    fun observePayers(groupId: String): Flow<List<SharedExpensePayerEntity>>

    @Query("SELECT s.* FROM shared_expense_shares s JOIN shared_expenses e ON e.id = s.expense_id WHERE e.group_id = :groupId ORDER BY s.position")
    fun observeShares(groupId: String): Flow<List<SharedExpenseShareEntity>>

    @Query("SELECT * FROM shared_expense_payers WHERE expense_id = :id ORDER BY position") fun observePayersOf(id: String): Flow<List<SharedExpensePayerEntity>>

    @Query("SELECT * FROM shared_expense_shares WHERE expense_id = :id ORDER BY position") fun observeSharesOf(id: String): Flow<List<SharedExpenseShareEntity>>

    @Query("SELECT * FROM shared_expenses WHERE id = :id") suspend fun byId(id: String): SharedExpenseEntity?

    @Query("SELECT * FROM shared_expense_payers WHERE expense_id = :id ORDER BY position") suspend fun payers(id: String): List<SharedExpensePayerEntity>

    @Query("SELECT * FROM shared_expense_shares WHERE expense_id = :id ORDER BY position") suspend fun shares(id: String): List<SharedExpenseShareEntity>

    @Query("SELECT * FROM shared_expenses WHERE group_id IN (:groupIds)") suspend fun inGroups(groupIds: List<String>): List<SharedExpenseEntity>

    @Query("SELECT p.* FROM shared_expense_payers p JOIN shared_expenses e ON e.id = p.expense_id WHERE e.group_id IN (:groupIds) ORDER BY p.position")
    suspend fun payersInGroups(groupIds: List<String>): List<SharedExpensePayerEntity>

    @Query("SELECT s.* FROM shared_expense_shares s JOIN shared_expenses e ON e.id = s.expense_id WHERE e.group_id IN (:groupIds) ORDER BY s.position")
    suspend fun sharesInGroups(groupIds: List<String>): List<SharedExpenseShareEntity>

    @Upsert suspend fun upsert(expense: SharedExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPayers(payers: List<SharedExpensePayerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertShares(shares: List<SharedExpenseShareEntity>)

    @Query("DELETE FROM shared_expense_payers WHERE expense_id = :id") suspend fun deletePayers(id: String)

    @Query("DELETE FROM shared_expense_shares WHERE expense_id = :id") suspend fun deleteShares(id: String)

    @Query("DELETE FROM shared_expenses WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM shared_expense_payers WHERE expense_id IN (SELECT id FROM shared_expenses WHERE group_id = :groupId)")
    suspend fun deletePayersForGroup(groupId: String)

    @Query("DELETE FROM shared_expense_shares WHERE expense_id IN (SELECT id FROM shared_expenses WHERE group_id = :groupId)")
    suspend fun deleteSharesForGroup(groupId: String)

    @Query("DELETE FROM shared_expenses WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_expense_payers") suspend fun deleteAllPayers()

    @Query("DELETE FROM shared_expense_shares") suspend fun deleteAllShares()

    @Query("DELETE FROM shared_expenses") suspend fun deleteAll()
}

@Dao
interface SharedSettlementDao {
    @Query("SELECT * FROM shared_settlements WHERE group_id = :groupId ORDER BY date DESC, created_at DESC")
    fun observe(groupId: String): Flow<List<SharedSettlementEntity>>

    @Query("SELECT * FROM shared_settlements WHERE id = :id") suspend fun byId(id: String): SharedSettlementEntity?

    @Upsert suspend fun upsert(settlement: SharedSettlementEntity)

    @Query("DELETE FROM shared_settlements WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM shared_settlements WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_settlements") suspend fun deleteAll()
}

@Dao
interface SharedRecurringDao {
    @Query("SELECT * FROM shared_recurring WHERE group_id = :groupId ORDER BY next_date")
    fun observe(groupId: String): Flow<List<SharedRecurringEntity>>

    @Query("SELECT * FROM shared_recurring WHERE id = :id") suspend fun byId(id: String): SharedRecurringEntity?

    @Upsert suspend fun upsert(recurring: SharedRecurringEntity)

    @Query("DELETE FROM shared_recurring WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM shared_recurring WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_recurring") suspend fun deleteAll()
}

@Dao
interface SharedActivityDao {
    @Query("SELECT * FROM shared_activity WHERE group_id = :groupId ORDER BY seq DESC")
    fun observe(groupId: String): Flow<List<SharedActivityEntity>>

    @Query(
        "SELECT a.* FROM shared_activity a JOIN shared_groups g ON g.id = a.group_id " +
            "WHERE g.deleted_at IS NULL ORDER BY a.created_at DESC, a.seq DESC LIMIT :limit",
    )
    fun observeAll(limit: Int): Flow<List<SharedActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(activity: SharedActivityEntity)

    @Query("DELETE FROM shared_activity WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_activity") suspend fun deleteAll()
}

@Dao
interface SharedOutboxDao {
    @Query("SELECT * FROM shared_outbox ORDER BY id") suspend fun all(): List<SharedOutboxEntity>

    @Query("SELECT * FROM shared_outbox WHERE status != 'PENDING' ORDER BY id") fun observeProblems(): Flow<List<SharedOutboxEntity>>

    @Query("SELECT * FROM shared_outbox WHERE id = :id") suspend fun byId(id: Long): SharedOutboxEntity?

    @Query("SELECT * FROM shared_outbox WHERE entity_type = :type AND entity_id = :entityId")
    suspend fun forEntity(type: String, entityId: String): SharedOutboxEntity?

    @Insert suspend fun insert(item: SharedOutboxEntity): Long

    @Upsert suspend fun update(item: SharedOutboxEntity)

    @Query("DELETE FROM shared_outbox WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM shared_outbox WHERE group_id = :groupId") suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM shared_outbox") suspend fun deleteAll()
}

/** Which local transaction belongs to which Shared expense or Settlement (SharedBudgetLink). */
@Dao
interface SharedLinkDao {
    @Query("SELECT * FROM shared_links") suspend fun all(): List<SharedLinkEntity>

    @Query("SELECT * FROM shared_links") fun observeAll(): Flow<List<SharedLinkEntity>>

    @Query("SELECT * FROM shared_links WHERE entity_type = :type AND entity_id = :entityId")
    suspend fun forEntity(type: String, entityId: String): List<SharedLinkEntity>

    @Query("SELECT * FROM shared_links WHERE transaction_id = :transactionId") suspend fun forTransaction(transactionId: Long): SharedLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(link: SharedLinkEntity): Long

    @Query("SELECT COUNT(*) FROM shared_links") suspend fun count(): Int

    @Query("DELETE FROM shared_links WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM shared_links WHERE transaction_id = :transactionId") suspend fun deleteForTransaction(transactionId: Long)
}

/** The entry this phone made for each Shared expense it did not pay in full (SharedBudgetLink). */
@Dao
interface SharedProjectionDao {
    @Query("SELECT * FROM shared_projections") suspend fun all(): List<SharedProjectionEntity>

    @Query("SELECT COUNT(*) FROM shared_projections") suspend fun count(): Int

    @Query("SELECT * FROM shared_projections WHERE expense_id = :expenseId") suspend fun forExpense(expenseId: String): SharedProjectionEntity?

    @Upsert suspend fun upsert(projection: SharedProjectionEntity)

    @Query("DELETE FROM shared_projections WHERE expense_id = :expenseId") suspend fun delete(expenseId: String)
}
