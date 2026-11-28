package com.example.data.local

import kotlinx.coroutines.flow.Flow

class JarvisLocalRepository(private val db: JarvisDatabase) {
    val messages: Flow<List<ConversationMessageEntity>> = db.conversationDao().observeMessages()
    val memories: Flow<List<MemoryFactEntity>> = db.memoryDao().observeMemories()
    val monitoringTasks: Flow<List<MonitoringTaskEntity>> = db.monitoringTaskDao().observeTasks()
    val orderAudits: Flow<List<OrderAuditEntity>> = db.orderAuditDao().observeAudits()
    val alerts: Flow<List<AlertNotificationEntity>> = db.alertNotificationDao().observeAlerts()
    val aiLogs: Flow<List<AiActivityLogEntity>> = db.aiActivityDao().observeLogs()
    val learnedApps: Flow<List<DeviceAppKnowledgeEntity>> = db.deviceKnowledgeDao().observeLearnedApps()
    val learnedWorkflows: Flow<List<LearnedWorkflowEntity>> = db.deviceKnowledgeDao().observeWorkflows()
    val ownerHabits: Flow<List<OwnerHabitEntity>> = db.deviceKnowledgeDao().observeOwnerHabits()
    val scheduledAutomations: Flow<List<ScheduledAutomationEntity>> = db.scheduledAutomationDao().observeAutomations()

    suspend fun getRecentMessages(limit: Int = 16): List<ConversationMessageEntity> =
        db.conversationDao().getRecentMessages(limit).reversed()

    suspend fun saveMessage(
        role: String,
        content: String,
        modelUsed: String,
        isVoice: Boolean,
        toolSummary: String? = null
    ) {
        db.conversationDao().insertMessage(
            ConversationMessageEntity(
                role = role,
                content = content,
                modelUsed = modelUsed,
                isVoice = isVoice,
                toolSummary = toolSummary
            )
        )
    }

    suspend fun clearConversationHistory() = db.conversationDao().clearAll()

    suspend fun getRecentMemories(): List<MemoryFactEntity> = db.memoryDao().getRecentMemories()

    suspend fun saveMemory(category: String, summary: String) {
        db.memoryDao().insertMemory(MemoryFactEntity(category = category, summary = summary))
    }

    suspend fun updateMemory(id: Long, summary: String) {
        if (summary.isNotBlank()) {
            db.memoryDao().updateMemoryById(id, summary.trim())
        }
    }

    suspend fun deleteMemory(id: Long) = db.memoryDao().deleteMemoryById(id)

    suspend fun clearAllMemories() = db.memoryDao().clearAllMemories()

    suspend fun addMonitoringTask(task: MonitoringTaskEntity): Long =
        db.monitoringTaskDao().upsertTask(task)

    suspend fun getActiveMonitoringTasks(): List<MonitoringTaskEntity> =
        db.monitoringTaskDao().getActiveTasks()

    suspend fun toggleTaskActive(id: Long, active: Boolean) =
        db.monitoringTaskDao().setTaskActive(id, active)

    suspend fun updateTaskState(id: Long, price: Double?, triggeredMs: Long?) =
        db.monitoringTaskDao().updateTaskState(id, price, triggeredMs)

    suspend fun deleteMonitoringTask(id: Long) = db.monitoringTaskDao().deleteTask(id)

    suspend fun logOrderAudit(audit: OrderAuditEntity) = db.orderAuditDao().insertAudit(audit)

    suspend fun logAlert(
        category: String,
        title: String,
        message: String,
        severity: String,
        symbol: String? = null
    ) {
        db.alertNotificationDao().insertAlert(
            AlertNotificationEntity(
                category = category,
                title = title,
                message = message,
                severity = severity,
                symbol = symbol
            )
        )
    }

    suspend fun markAllAlertsRead() = db.alertNotificationDao().markAllRead()

    suspend fun clearAlerts() = db.alertNotificationDao().clearAll()

    suspend fun logAiActivity(actionType: String, detail: String) {
        db.aiActivityDao().insertLog(AiActivityLogEntity(actionType = actionType, detail = detail))
    }

    suspend fun upsertDeviceApps(apps: List<DeviceAppKnowledgeEntity>) =
        db.deviceKnowledgeDao().upsertApps(apps)

    suspend fun upsertDeviceApp(app: DeviceAppKnowledgeEntity) =
        db.deviceKnowledgeDao().upsertApp(app)

    suspend fun deleteDeviceApp(packageName: String) =
        db.deviceKnowledgeDao().deleteApp(packageName)

    suspend fun getAllDeviceAppsSnapshot(): List<DeviceAppKnowledgeEntity> =
        db.deviceKnowledgeDao().getAllLearnedAppsSnapshot()

    suspend fun getTopWorkflows(): List<LearnedWorkflowEntity> =
        db.deviceKnowledgeDao().getTopWorkflowsSnapshot()

    suspend fun saveLearnedWorkflow(targetPackageOrSite: String, goalPhrase: String, stepsSummary: String) {
        db.deviceKnowledgeDao().insertWorkflow(
            LearnedWorkflowEntity(
                targetPackageOrSite = targetPackageOrSite,
                goalPhrase = goalPhrase,
                stepsSummary = stepsSummary
            )
        )
    }

    suspend fun upsertOwnerHabit(habit: OwnerHabitEntity) =
        db.deviceKnowledgeDao().upsertHabit(habit)

    suspend fun getTopHabits(): List<OwnerHabitEntity> =
        db.deviceKnowledgeDao().getTopHabitsSnapshot()

    suspend fun clearLearnedHabitsAndWorkflows() {
        db.deviceKnowledgeDao().clearWorkflows()
        db.deviceKnowledgeDao().clearHabits()
    }

    suspend fun getActiveScheduledAutomations(): List<ScheduledAutomationEntity> =
        db.scheduledAutomationDao().getActiveAutomationsSnapshot()

    suspend fun getScheduledAutomationById(id: Long): ScheduledAutomationEntity? =
        db.scheduledAutomationDao().getById(id)

    suspend fun upsertScheduledAutomation(task: ScheduledAutomationEntity): Long =
        db.scheduledAutomationDao().upsertAutomation(task)

    suspend fun setScheduledAutomationEnabled(id: Long, enabled: Boolean) =
        db.scheduledAutomationDao().setEnabled(id, enabled)

    suspend fun recordScheduledAutomationRun(
        id: Long,
        nextTriggerMs: Long,
        enabled: Boolean,
        runAtMs: Long,
        outcome: String
    ) = db.scheduledAutomationDao().recordExecution(id, nextTriggerMs, enabled, runAtMs, outcome)

    suspend fun deleteScheduledAutomation(id: Long) =
        db.scheduledAutomationDao().deleteAutomation(id)
}
