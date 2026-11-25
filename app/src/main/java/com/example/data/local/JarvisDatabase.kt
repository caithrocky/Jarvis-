package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversation_messages")
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val modelUsed: String,
    val isVoice: Boolean,
    val toolSummary: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // "preference", "context", "strategy"
    val summary: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "monitoring_tasks")
data class MonitoringTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val symbol: String,
    val ruleType: String, // "PCT_MOVE", "PRICE_ABOVE", "PRICE_BELOW", "BREAKOUT", "MORNING_BRIEF", "PORTFOLIO_WATCH", "NEWS_EVENT"
    val thresholdValue: Double,
    val isActive: Boolean = true,
    val lastBaselinePrice: Double? = null,
    val lastTriggeredMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "order_audits")
data class OrderAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brokerName: String,
    val environment: String, // "DEMO" or "LIVE"
    val symbol: String,
    val side: String,
    val amountUsd: Double,
    val entryPrice: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val operatingMode: String,
    val status: String, // "EXECUTED", "BLOCKED", "FAILED", "CANCELLED"
    val brokerOrderId: String? = null,
    val resultMessage: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "alert_notifications")
data class AlertNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // "MARKET", "OPPORTUNITY", "NEWS", "TRADE", "BROKER", "SYSTEM"
    val title: String,
    val message: String,
    val severity: String, // "INFO", "WARNING", "CRITICAL"
    val symbol: String? = null,
    val isRead: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_activity_logs")
data class AiActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "configured_symbols")
data class ConfiguredSymbolEntity(
    @PrimaryKey val symbol: String,
    val displayName: String,
    val assetClass: String,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_market_quotes")
data class CachedMarketQuoteEntity(
    @PrimaryKey val symbol: String,
    val displayName: String,
    val assetClass: String,
    val price: Double,
    val previousPrice: Double,
    val change: Double,
    val changePercent: Double,
    val high24h: Double,
    val low24h: Double,
    val volume: Double?,
    val volatilityLabel: String,
    val supportLevel: Double?,
    val resistanceLevel: Double?,
    val marketSession: String,
    val tradingHours: String,
    val freshness: String,
    val sourceName: String,
    val updatedAtMs: Long
)

@Entity(tableName = "device_app_knowledge")
data class DeviceAppKnowledgeEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val category: String,
    val discoveredScreensSummary: String = "",
    val usageCount: Int = 0,
    val lastStudiedMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "learned_workflows")
data class LearnedWorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetPackageOrSite: String,
    val goalPhrase: String,
    val stepsSummary: String,
    val successCount: Int = 1,
    val updatedMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "owner_habits")
data class OwnerHabitEntity(
    @PrimaryKey val habitKey: String,
    val category: String,
    val summary: String,
    val confidenceScore: Float = 0.6f,
    val updatedMs: Long = System.currentTimeMillis()
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_messages ORDER BY timestampMs ASC")
    fun observeMessages(): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ConversationMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessageEntity): Long

    @Query("DELETE FROM conversation_messages")
    suspend fun clearAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_facts ORDER BY createdAtMs DESC")
    fun observeMemories(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY createdAtMs DESC LIMIT 20")
    suspend fun getRecentMemories(): List<MemoryFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryFactEntity): Long

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clearAllMemories()
}

@Dao
interface MonitoringTaskDao {
    @Query("SELECT * FROM monitoring_tasks ORDER BY createdAtMs DESC")
    fun observeTasks(): Flow<List<MonitoringTaskEntity>>

    @Query("SELECT * FROM monitoring_tasks WHERE isActive = 1")
    suspend fun getActiveTasks(): List<MonitoringTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: MonitoringTaskEntity): Long

    @Query("UPDATE monitoring_tasks SET isActive = :active WHERE id = :id")
    suspend fun setTaskActive(id: Long, active: Boolean)

    @Query("UPDATE monitoring_tasks SET lastBaselinePrice = :price, lastTriggeredMs = :triggeredMs WHERE id = :id")
    suspend fun updateTaskState(id: Long, price: Double?, triggeredMs: Long?)

    @Query("DELETE FROM monitoring_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)
}

@Dao
interface OrderAuditDao {
    @Query("SELECT * FROM order_audits ORDER BY timestampMs DESC")
    fun observeAudits(): Flow<List<OrderAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: OrderAuditEntity): Long
}

@Dao
interface AlertNotificationDao {
    @Query("SELECT * FROM alert_notifications ORDER BY timestampMs DESC")
    fun observeAlerts(): Flow<List<AlertNotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: AlertNotificationEntity): Long

    @Query("UPDATE alert_notifications SET isRead = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM alert_notifications")
    suspend fun clearAll()
}

@Dao
interface AiActivityDao {
    @Query("SELECT * FROM ai_activity_logs ORDER BY timestampMs DESC LIMIT 100")
    fun observeLogs(): Flow<List<AiActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AiActivityLogEntity): Long
}

@Dao
interface MarketCacheDao {
    @Query("SELECT * FROM cached_market_quotes ORDER BY symbol ASC")
    fun observeAllCachedQuotes(): Flow<List<CachedMarketQuoteEntity>>

    @Query(
        """
        SELECT q.* FROM cached_market_quotes q
        INNER JOIN configured_symbols c ON q.symbol = c.symbol
        WHERE c.isEnabled = 1
        ORDER BY c.sortOrder ASC, c.symbol ASC
        """
    )
    fun observeConfiguredCachedQuotes(): Flow<List<CachedMarketQuoteEntity>>

    @Query("SELECT * FROM cached_market_quotes")
    suspend fun getAllCachedQuotesSnapshot(): List<CachedMarketQuoteEntity>

    @Query("SELECT * FROM cached_market_quotes WHERE symbol = :symbol LIMIT 1")
    suspend fun getCachedQuoteBySymbol(symbol: String): CachedMarketQuoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuotes(quotes: List<CachedMarketQuoteEntity>)

    @Query("DELETE FROM cached_market_quotes WHERE symbol = :symbol")
    suspend fun deleteQuote(symbol: String)

    @Query("DELETE FROM cached_market_quotes")
    suspend fun clearQuotes()

    @Query("SELECT * FROM configured_symbols ORDER BY sortOrder ASC, symbol ASC")
    fun observeConfiguredSymbols(): Flow<List<ConfiguredSymbolEntity>>

    @Query("SELECT * FROM configured_symbols")
    suspend fun getAllConfiguredSymbolsSnapshot(): List<ConfiguredSymbolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfiguredSymbols(symbols: List<ConfiguredSymbolEntity>)

    @Query("UPDATE configured_symbols SET isEnabled = :enabled WHERE symbol = :symbol")
    suspend fun setSymbolEnabled(symbol: String, enabled: Boolean)

    @Query("DELETE FROM configured_symbols WHERE symbol = :symbol")
    suspend fun deleteConfiguredSymbol(symbol: String)
}

@Dao
interface DeviceKnowledgeDao {
    @Query("SELECT * FROM device_app_knowledge ORDER BY usageCount DESC, appName ASC")
    fun observeLearnedApps(): Flow<List<DeviceAppKnowledgeEntity>>

    @Query("SELECT * FROM device_app_knowledge ORDER BY usageCount DESC, appName ASC")
    suspend fun getAllLearnedAppsSnapshot(): List<DeviceAppKnowledgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApps(apps: List<DeviceAppKnowledgeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApp(app: DeviceAppKnowledgeEntity)

    @Query("SELECT * FROM learned_workflows ORDER BY updatedMs DESC")
    fun observeWorkflows(): Flow<List<LearnedWorkflowEntity>>

    @Query("SELECT * FROM learned_workflows ORDER BY successCount DESC, updatedMs DESC LIMIT 25")
    suspend fun getTopWorkflowsSnapshot(): List<LearnedWorkflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkflow(workflow: LearnedWorkflowEntity): Long

    @Query("SELECT * FROM owner_habits ORDER BY confidenceScore DESC, updatedMs DESC")
    fun observeOwnerHabits(): Flow<List<OwnerHabitEntity>>

    @Query("SELECT * FROM owner_habits ORDER BY confidenceScore DESC, updatedMs DESC LIMIT 20")
    suspend fun getTopHabitsSnapshot(): List<OwnerHabitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHabit(habit: OwnerHabitEntity)

    @Query("DELETE FROM learned_workflows")
    suspend fun clearWorkflows()

    @Query("DELETE FROM owner_habits")
    suspend fun clearHabits()
}

@Database(
    entities = [
        ConversationMessageEntity::class,
        MemoryFactEntity::class,
        MonitoringTaskEntity::class,
        OrderAuditEntity::class,
        AlertNotificationEntity::class,
        AiActivityLogEntity::class,
        ConfiguredSymbolEntity::class,
        CachedMarketQuoteEntity::class,
        DeviceAppKnowledgeEntity::class,
        LearnedWorkflowEntity::class,
        OwnerHabitEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun monitoringTaskDao(): MonitoringTaskDao
    abstract fun orderAuditDao(): OrderAuditDao
    abstract fun alertNotificationDao(): AlertNotificationDao
    abstract fun aiActivityDao(): AiActivityDao
    abstract fun marketCacheDao(): MarketCacheDao
    abstract fun deviceKnowledgeDao(): DeviceKnowledgeDao

    companion object {
        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_command_center.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
