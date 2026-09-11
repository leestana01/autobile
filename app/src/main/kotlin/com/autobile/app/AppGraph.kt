package com.autobile.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.autobile.ai.cloud.CloudAiProvider
import com.autobile.ai.cloud.CloudConfig
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.local.LocalModelProvider
import com.autobile.ai.mlkit.MLKitGeminiNanoProvider
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.common.Ids
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.HistoryStore
import com.autobile.core.data.Metric
import com.autobile.core.data.MetricsStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.data.SkillStore
import com.autobile.core.data.TraceStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.RuntimeRequirements
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.AutobileServices
import com.autobile.runtime.agent.AgentOrchestrator
import com.autobile.runtime.agent.ConfirmationMode
import com.autobile.runtime.background.ExecutabilityEvaluator
import com.autobile.runtime.capability.CapabilityDetector
import com.autobile.runtime.compiler.SkillCompiler
import com.autobile.runtime.control.ScreenController
import com.autobile.runtime.edit.SkillEditor
import com.autobile.runtime.executor.SkillExecutor
import com.autobile.runtime.overlay.AgentVisibilityCoordinator
import com.autobile.runtime.perception.PerceptionEngine
import com.autobile.runtime.recovery.SelfHealingEngine
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.teach.DemonstrationRecorder
import com.autobile.runtime.teach.TraceSegmenter
import com.autobile.runtime.trigger.TriggerScheduler
import com.autobile.runtime.validation.ValidationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable

/** Process-wide dependency graph shared by UI, workers, and Android services. */
class AppGraph(val appContext: Context) : AutobileServices, Closeable {
    private val context = appContext.applicationContext
    private val graphJob: Job = SupervisorJob()
    private val scope = CoroutineScope(graphJob + Dispatchers.Default)
    private val database = AutobileDatabase(context)

    override val settings = SettingsStore(context)
    override val skillStore = SkillStore(database)
    override val historyStore = HistoryStore(database)
    val traceStore = TraceStore(database)
    val policyStore = AppPolicyStore(database)
    val metricsStore = MetricsStore(database)

    val deviceAi = MLKitGeminiNanoProvider()
    val localModel = LocalModelProvider(
        modelDirectory = context.getDir("models", Context.MODE_PRIVATE),
        enabled = { settings.localModelEnabled },
    )

    private fun cloudConfig(advanced: Boolean = false): CloudConfig {
        val privacy = settings.privacy()
        return CloudConfig(
            enabled = privacy.cloudEnabled,
            endpoint = privacy.cloudEndpoint.ifBlank { CloudConfig.DEFAULT_ENDPOINT },
            apiKey = settings.cloudApiKey(),
            lightModel = privacy.cloudModel.ifBlank { CloudConfig.DEFAULT_LIGHT_MODEL },
            advancedModel = privacy.cloudVisionModel.ifBlank { CloudConfig.DEFAULT_ADVANCED_MODEL },
            allowImages = privacy.allowScreenshotToCloud,
            maxInputTokens = if (advanced) 32_000 else 8_000,
        )
    }

    override val aiRouter = AiRuntimeRouter(
        listOf(
            deviceAi,
            localModel,
            CloudAiProvider(com.autobile.core.model.RuntimeTier.CLOUD_LIGHT) { cloudConfig() },
            CloudAiProvider(com.autobile.core.model.RuntimeTier.CLOUD_ADVANCED) { cloudConfig(advanced = true) },
        ),
    )

    private val minimizer = ContextMinimizer()
    private val perception = PerceptionEngine(context)
    private val controller = ScreenController(context)
    private val resolver = ExecutionResolver(aiRouter, minimizer)
    private val riskEngine = RiskEngine(policyStore, settings)
    private val validation = ValidationEngine(aiRouter, minimizer)
    private val healing = SelfHealingEngine(aiRouter, riskEngine, minimizer)
    private val executor = SkillExecutor(
        perception = perception,
        controller = controller,
        resolver = resolver,
        validation = validation,
        healing = healing,
        riskEngine = riskEngine,
        router = aiRouter,
        skillStore = skillStore,
        minimizer = minimizer,
    )
    val capabilityDetector = CapabilityDetector(
        context = context,
        deviceAi = deviceAi,
        localModel = localModel,
        cloudConfig = { cloudConfig() },
    )
    private val executability = ExecutabilityEvaluator(context, policyStore, settings)
    override val orchestrator = AgentOrchestrator(
        executor = executor,
        perception = perception,
        router = aiRouter,
        skillStore = skillStore,
        historyStore = historyStore,
        metrics = metricsStore,
        settings = settings,
        executability = executability,
        capabilityDetector = capabilityDetector,
        minimizer = minimizer,
    )
    override val triggerScheduler = TriggerScheduler(context)
    private val segmenter = TraceSegmenter(aiRouter)
    val compiler = SkillCompiler(aiRouter, segmenter, riskEngine)
    val recorder = DemonstrationRecorder(perception, scope)
    val skillEditor = SkillEditor(aiRouter, skillStore, triggerScheduler)
    private val visibility = AgentVisibilityCoordinator(context, orchestrator, settings)

    fun start() {
        AutobileRuntime.install(this)
        visibility.start(scope)
        scope.launch {
            seedProtectivePolicies()
            triggerScheduler.rescheduleAll(skillStore.listEnabledSkills())
            traceStore.prune(System.currentTimeMillis() - TRACE_RETENTION_MS)
            capabilityDetector.detect()
        }
    }

    suspend fun starterSkill(): SemanticSkill {
        skillStore.get(STARTER_SKILL_ID)?.let { return it }
        val now = System.currentTimeMillis()
        return skillStore.save(
            SemanticSkill(
                id = STARTER_SKILL_ID,
                version = 1,
                name = "Open phone settings",
                goal = "Open Android settings",
                description = "A safe first automation you can replay any time.",
                trigger = TriggerSpec.Manual,
                steps = listOf(
                    SkillStep(
                        id = Ids.step(),
                        intent = StepIntent.LAUNCH_APP,
                        target = TargetSemantics("Android settings", "the Android settings app"),
                        preferredResolver = ResolverKind.DIRECT_API,
                        action = ActionSpec.LaunchApp("com.android.settings"),
                        expectedState = ExpectedState(requiredPackage = "com.android.settings"),
                        validation = ValidationSpec(mode = ValidationMode.STRUCTURAL, goalCritical = true),
                        description = "Open Android settings",
                    ),
                ),
                riskPolicy = RiskPolicy(requireConfirmation = false),
                autonomyLevel = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK,
                confidence = SkillConfidence(score = 0.7f),
                runtimeRequirements = RuntimeRequirements(requiredPackages = listOf("com.android.settings")),
                history = listOf(
                    SkillVersionRecord(1, now, PatchAuthor.COMPILER, "Created as a starter automation"),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun runStarterTask() = orchestrator.runSkill(
        starterSkill().id,
        TaskOrigin.MANUAL,
        confirmation = ConfirmationMode.AskUser(),
    )

    suspend fun acceptPatch(candidate: SkillPatchCandidate): Result<SemanticSkill> = runCatching {
        val current = skillStore.get(candidate.skillId) ?: error("Automation not found")
        check(current.version == candidate.baseVersion) { "Automation changed since this repair was proposed" }
        val version = current.version + 1
        val record = SkillVersionRecord(
            version = version,
            createdAt = System.currentTimeMillis(),
            author = PatchAuthor.SELF_HEAL,
            reason = candidate.summary,
            summary = candidate.summary,
            changedStepIds = candidate.patchedSkill.steps.map { it.id },
        )
        val saved = skillStore.save(
            candidate.patchedSkill.copy(version = version, history = current.history),
            record,
        )
        skillStore.updatePatchStatus(candidate.id, com.autobile.core.data.PatchStatus.ACCEPTED)
        triggerScheduler.cancel(saved.id)
        triggerScheduler.schedule(saved)
        saved
    }

    suspend fun rejectPatch(candidate: SkillPatchCandidate) {
        skillStore.updatePatchStatus(candidate.id, com.autobile.core.data.PatchStatus.REJECTED)
    }

    private suspend fun seedProtectivePolicies() {
        val packages = runCatching {
            context.packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }
                .toList()
        }.getOrDefault(emptyList())
        policyStore.seedDefaults(packages)
    }

    override fun close() {
        visibility.stop()
        recorder.cancel()
        runBlocking { graphJob.cancelAndJoin() }
        aiRouter.close()
        database.close()
    }

    private companion object {
        const val STARTER_SKILL_ID = "starter-open-settings"
        const val TRACE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
