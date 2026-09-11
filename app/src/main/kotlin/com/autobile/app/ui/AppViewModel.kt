package com.autobile.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autobile.ai.mlkit.DownloadState
import com.autobile.ai.mlkit.ModelDownloadProgress
import com.autobile.core.data.Metric
import com.autobile.core.data.PatchStatus
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.DeviceCapabilityProfile
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.MetricsSnapshot
import com.autobile.core.model.PrivacySettings
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.agent.CommandResolution
import com.autobile.runtime.agent.ConfirmationMode
import com.autobile.runtime.agent.PendingConfirmation
import com.autobile.runtime.agent.RunResult
import com.autobile.runtime.compiler.CompilationResult
import com.autobile.runtime.edit.SkillEditApplyResult
import com.autobile.runtime.edit.SkillEditPreview
import com.autobile.runtime.teach.RecordingState
import com.autobile.app.AppGraph
import com.autobile.app.TeachingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = graph.settings.onboardingComplete,
            privacy = graph.settings.privacy(),
            killSwitchEngaged = graph.settings.killSwitch().engaged,
            touchIndicatorEnabled = graph.settings.showTouchIndicator,
            nanoDownloadConsented = graph.settings.deviceAiDownloadConsented,
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            graph.skillStore.observeSkills().collectLatest { skills ->
                val selectedId = _state.value.selectedSkillId
                _state.update {
                    it.copy(
                        skills = skills,
                        patches = graph.skillStore.pendingPatches(),
                        skillVersions = selectedId?.let { id -> graph.skillStore.versions(id) }.orEmpty(),
                    )
                }
            }
        }
        viewModelScope.launch {
            graph.historyStore.observeTasks().collectLatest { tasks ->
                val selectedId = _state.value.selectedSkillId
                _state.update {
                    it.copy(
                        tasks = tasks,
                        selectedSkillLastTask = tasks.firstOrNull { task -> task.skillId == selectedId },
                    )
                }
            }
        }
        viewModelScope.launch {
            graph.metricsStore.observe().collectLatest { metrics -> _state.update { it.copy(metrics = metrics) } }
        }
        viewModelScope.launch {
            graph.policyStore.observe().collectLatest { policies -> _state.update { it.copy(policies = policies) } }
        }
        viewModelScope.launch {
            graph.orchestrator.activity.collectLatest { activity -> _state.update { it.copy(activity = activity) } }
        }
        viewModelScope.launch {
            graph.orchestrator.pendingConfirmation.collectLatest { pending ->
                _state.update { it.copy(pendingConfirmation = pending) }
            }
        }
        viewModelScope.launch {
            graph.recorder.state.collectLatest { recording -> _state.update { it.copy(recording = recording) } }
        }
        refreshCapabilities()
    }

    fun navigate(screen: AppScreen) {
        _state.update { it.copy(screen = screen, message = null) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refreshCapabilities() = launchAction {
        val profile = graph.capabilityDetector.detect()
        _state.update { it.copy(capability = profile, loading = false) }
    }

    fun nextOnboardingStep() {
        val next = OnboardingStep.entries.getOrElse(_state.value.onboardingStep.ordinal + 1) {
            OnboardingStep.COMPLETE
        }
        if (next == OnboardingStep.COMPLETE) finishOnboarding() else {
            _state.update { it.copy(onboardingStep = next) }
        }
    }

    fun runStarterTask() = launchAction {
        when (val result = graph.runStarterTask()) {
            is RunResult.Completed -> {
                _state.update {
                    it.copy(
                        message = if (result.outcome.goalValidated) "First automation completed" else result.outcome.message,
                        onboardingStep = OnboardingStep.TEACH,
                    )
                }
            }
            is RunResult.Deferred -> showMessage(result.state.message)
            is RunResult.Rejected -> showMessage(result.reason)
        }
    }

    fun startTeaching(label: String = "My automation") = launchAction {
        if (!_state.value.capability.accessibilityConnected) {
            showMessage("Enable accessibility access before teaching")
            return@launchAction
        }
        graph.metricsStore.increment(Metric.TEACH_SESSIONS_STARTED)
        graph.recorder.start(label.ifBlank { "My automation" })
        TeachingForegroundService.start(graph.appContext)
        _state.update { it.copy(screen = AppScreen.TEACH, message = "Recording started. Demonstrate the task in another app.") }
    }

    fun cancelTeaching() {
        graph.recorder.cancel()
        TeachingForegroundService.stop(graph.appContext)
        _state.update { it.copy(screen = AppScreen.HOME, compilation = null) }
    }

    fun finishTeaching() = launchAction {
        val trace = graph.recorder.stop()
        TeachingForegroundService.stop(graph.appContext)
        if (trace == null || trace.events.isEmpty()) {
            showMessage("No actions were recorded. Try again and perform the task in another app.")
            return@launchAction
        }
        graph.traceStore.save(trace)
        _state.update { it.copy(loading = true, message = "Understanding your demonstration…") }
        when (val result = graph.compiler.compile(trace, localOnly = !graph.settings.privacy().cloudEnabled)) {
            is CompilationResult.Failed -> _state.update {
                it.copy(loading = false, message = result.reason, screen = AppScreen.TEACH)
            }
            is CompilationResult.Success -> _state.update {
                it.copy(
                    loading = false,
                    compilation = TeachDraft(
                        skill = result.skill,
                        summary = result.summary,
                        discardedSteps = result.discardedSteps,
                        usedCloud = result.usedCloud,
                    ),
                    screen = AppScreen.TEACH_REVIEW,
                    message = null,
                )
            }
        }
    }

    fun updateTeachName(value: String) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(name = value)))
        }
    }

    fun updateTeachGoal(value: String) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(goal = value)))
        }
    }

    fun updateTeachAutonomy(value: AutonomyLevel) {
        _state.update { state ->
            state.copy(compilation = state.compilation?.copy(skill = state.compilation.skill.copy(autonomyLevel = value)))
        }
    }

    /**
     * Applies a plain-language correction to the understanding just presented.
     *
     * Editing the goal text alone would leave the compiled steps untouched, so a
     * correction such as "send net sales, not gross" has to run through the same editor
     * that changes a saved automation and rewrite the step semantics.
     */
    fun correctUnderstanding(request: String) = launchAction {
        val draft = _state.value.compilation ?: return@launchAction
        if (request.isBlank()) return@launchAction
        val preview = graph.skillEditor.preview(
            skill = draft.skill,
            request = request,
            localOnly = !graph.settings.privacy().cloudEnabled,
        )
        when (preview) {
            is SkillEditPreview.Rejected -> showMessage(preview.reason)
            is SkillEditPreview.Ready -> {
                graph.metricsStore.increment(Metric.USER_INTERVENTIONS)
                _state.update { state ->
                    state.copy(
                        compilation = state.compilation?.copy(
                            // The draft is still unsaved, so it stays at version 1
                            // rather than inheriting the editor's incremented version.
                            skill = preview.updated.copy(version = draft.skill.version),
                            summary = preview.summary,
                        ),
                        message = "Updated: ${preview.summary}",
                    )
                }
            }
        }
    }

    fun updateTeachSchedule(hour: Int?, minute: Int?) {
        _state.update { state ->
            val draft = state.compilation ?: return@update state
            val trigger = if (hour == null || minute == null) TriggerSpec.Manual else TriggerSpec.Time(hour, minute)
            state.copy(compilation = draft.copy(skill = draft.skill.copy(trigger = trigger)))
        }
    }

    fun saveTeaching() = launchAction {
        val draft = _state.value.compilation ?: return@launchAction
        if (draft.skill.name.isBlank() || draft.skill.goal.isBlank()) {
            showMessage("Name and goal are required")
            return@launchAction
        }
        val timeTrigger = draft.skill.trigger as? TriggerSpec.Time
        if (timeTrigger != null && (timeTrigger.hour !in 0..23 || timeTrigger.minute !in 0..59)) {
            showMessage("Schedule time must be between 00:00 and 23:59")
            return@launchAction
        }
        val saved = graph.skillStore.save(draft.skill)
        graph.triggerScheduler.schedule(saved)
        graph.metricsStore.increment(Metric.TEACH_SESSIONS_COMPLETED)
        graph.metricsStore.increment(Metric.SKILLS_CREATED)
        _state.update {
            it.copy(
                compilation = null,
                selectedSkillId = saved.id,
                screen = if (it.onboardingComplete) AppScreen.SKILL_DETAIL else AppScreen.HOME,
                onboardingStep = if (it.onboardingComplete) it.onboardingStep else OnboardingStep.REPLAY,
                message = "Automation saved",
            )
        }
    }

    fun finishOnboarding() {
        graph.settings.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, onboardingStep = OnboardingStep.COMPLETE, screen = AppScreen.HOME) }
    }

    fun selectSkill(id: String) = launchAction {
        val versions = graph.skillStore.versions(id)
        val lastTask = graph.historyStore.recentTasks().firstOrNull { it.skillId == id }
        _state.update {
            it.copy(
                selectedSkillId = id,
                skillVersions = versions,
                selectedSkillLastTask = lastTask,
                screen = AppScreen.SKILL_DETAIL,
            )
        }
    }

    fun setSkillEnabled(skill: SemanticSkill, enabled: Boolean) = launchAction {
        graph.skillStore.setEnabled(skill.id, enabled)
        if (enabled) graph.triggerScheduler.schedule(skill.copy(enabled = true)) else graph.triggerScheduler.cancel(skill.id)
    }

    fun setSkillAutonomy(skill: SemanticSkill, autonomy: AutonomyLevel) = launchAction {
        graph.skillStore.save(
            skill.copy(version = skill.version + 1, autonomyLevel = autonomy),
            SkillVersionRecord(
                version = skill.version + 1,
                createdAt = System.currentTimeMillis(),
                author = com.autobile.core.model.PatchAuthor.USER,
                reason = "Changed autonomy to ${autonomy.label}",
            ),
        )
    }

    fun rollbackSkill(skillId: String, version: Int) = launchAction {
        val restored = graph.skillStore.rollbackTo(skillId, version)
        if (restored == null) showMessage("That version is no longer available") else {
            graph.triggerScheduler.cancel(skillId)
            graph.triggerScheduler.schedule(restored)
            selectSkill(skillId)
            showMessage("Restored as version ${restored.version}")
        }
    }

    fun deleteSkill(skill: SemanticSkill) = launchAction {
        graph.triggerScheduler.cancel(skill.id)
        graph.skillStore.delete(skill.id)
        _state.update { it.copy(screen = AppScreen.HOME, selectedSkillId = null, message = "Automation deleted") }
    }

    fun runSkill(skillId: String, replay: Boolean = false) = launchAction {
        when (val result = graph.orchestrator.runSkill(
            skillId = skillId,
            origin = if (replay) TaskOrigin.REPLAY else TaskOrigin.MANUAL,
            confirmation = ConfirmationMode.AskUser(),
        )) {
            is RunResult.Completed -> {
                _state.update { it.copy(message = result.outcome.message.ifBlank { "Automation finished" }) }
                if (!itIsOnboarded()) {
                    _state.update { it.copy(onboardingStep = OnboardingStep.COMPLETE) }
                    finishOnboarding()
                }
            }
            is RunResult.Deferred -> showMessage(result.state.message)
            is RunResult.Rejected -> showMessage(result.reason)
        }
    }

    fun submitCommand(command: String) = launchAction {
        if (command.isBlank()) return@launchAction
        _state.update { it.copy(loading = true) }
        when (val resolution = graph.orchestrator.interpretCommand(command)) {
            is CommandResolution.MatchedSkill -> {
                _state.update { it.copy(loading = false) }
                runSkill(resolution.skill.id)
            }
            is CommandResolution.NeedsTeaching -> _state.update {
                it.copy(
                    loading = false,
                    message = "Show me how to ${resolution.goal}",
                    teachLabel = resolution.goal,
                    screen = AppScreen.TEACH,
                )
            }
            is CommandResolution.NotUnderstood -> _state.update {
                it.copy(loading = false, message = resolution.reason)
            }
        }
    }

    fun previewEdit(skill: SemanticSkill, request: String) = launchAction {
        _state.update { it.copy(loading = true) }
        val preview = graph.skillEditor.preview(
            skill.id,
            request,
            localOnly = !graph.settings.privacy().cloudEnabled,
        )
        _state.update { it.copy(loading = false, editPreview = preview) }
    }

    fun dismissEditPreview() {
        _state.update { it.copy(editPreview = null) }
    }

    fun applyEdit(preview: SkillEditPreview.Ready) = launchAction {
        when (val result = graph.skillEditor.apply(preview)) {
            is SkillEditApplyResult.Applied -> {
                _state.update { it.copy(editPreview = null, message = "Change applied") }
                selectSkill(result.skill.id)
            }
            is SkillEditApplyResult.Rejected -> _state.update {
                it.copy(editPreview = null, message = result.reason)
            }
        }
    }

    fun reviewPatch(candidate: SkillPatchCandidate, accept: Boolean) = launchAction {
        if (accept) {
            graph.acceptPatch(candidate).fold(
                onSuccess = { showMessage("Repair applied as version ${it.version}") },
                onFailure = { showMessage(it.message ?: "Could not apply repair") },
            )
        } else {
            graph.rejectPatch(candidate)
            showMessage("Repair dismissed")
        }
        _state.update { it.copy(patches = graph.skillStore.pendingPatches()) }
    }

    fun selectTask(taskId: String) = launchAction {
        val task = graph.historyStore.getTask(taskId) ?: return@launchAction
        val events = graph.historyStore.events(taskId)
        val outcome = graph.historyStore.outcome(taskId)
        _state.update {
            it.copy(selectedTask = task, selectedTaskEvents = events, selectedTaskOutcome = outcome, screen = AppScreen.HISTORY_DETAIL)
        }
    }

    fun approvePending(approved: Boolean) {
        graph.orchestrator.resolveConfirmation(approved)
    }

    fun stopAgent() {
        graph.orchestrator.cancelCurrentRun()
    }

    fun updateCloudEnabled(enabled: Boolean) = updatePrivacy { it.copy(cloudEnabled = enabled) }
    fun updateCloudScreenshots(enabled: Boolean) = updatePrivacy { it.copy(allowScreenshotToCloud = enabled) }
    fun updateMasking(enabled: Boolean) = updatePrivacy { it.copy(maskSensitiveFields = enabled) }
    fun updateCloudEndpoint(value: String) = updatePrivacy { it.copy(cloudEndpoint = value.trim()) }

    fun setCloudApiKey(value: String) {
        graph.settings.setCloudApiKey(value.trim())
        _state.update { it.copy(privacy = graph.settings.privacy(), message = "Cloud credential updated") }
        refreshCapabilities()
    }

    fun setKillSwitch(engaged: Boolean) {
        graph.settings.setKillSwitch(engaged, if (engaged) "Stopped from settings" else "")
        if (engaged) graph.orchestrator.cancelCurrentRun()
        _state.update { it.copy(killSwitchEngaged = engaged) }
    }

    fun setTouchIndicator(enabled: Boolean) {
        graph.settings.showTouchIndicator = enabled
        _state.update { it.copy(touchIndicatorEnabled = enabled) }
    }

    fun savePolicy(policy: AppPolicy, mode: AppPolicyMode) = launchAction {
        graph.policyStore.save(policy.copy(mode = mode, userSet = true))
    }

    fun consentAndDownloadDeviceModel() {
        graph.settings.deviceAiDownloadConsented = true
        _state.update { it.copy(nanoDownloadConsented = true) }
        viewModelScope.launch {
            graph.deviceAi.download().collectLatest { progress ->
                _state.update { it.copy(nanoDownload = progress) }
                if (progress.state == DownloadState.COMPLETED) refreshCapabilities()
            }
        }
    }

    private fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) {
        val updated = transform(graph.settings.privacy())
        graph.settings.updatePrivacy(updated)
        _state.update { it.copy(privacy = graph.settings.privacy()) }
        refreshCapabilities()
    }

    private fun showMessage(message: String) {
        _state.update { it.copy(message = message, loading = false) }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { showMessage(it.message ?: "Something went wrong") }
        }
    }

    private fun itIsOnboarded(): Boolean = _state.value.onboardingComplete
}

enum class AppScreen { HOME, HISTORY, SETTINGS, SKILL_DETAIL, HISTORY_DETAIL, TEACH, TEACH_REVIEW }

enum class OnboardingStep { CAPABILITY, PERMISSIONS, INSTANT_TASK, TEACH, REPLAY, COMPLETE }

data class TeachDraft(
    val skill: SemanticSkill,
    val summary: String,
    val discardedSteps: Int,
    val usedCloud: Boolean,
)

data class AppUiState(
    val onboardingComplete: Boolean = false,
    val onboardingStep: OnboardingStep = OnboardingStep.CAPABILITY,
    val screen: AppScreen = AppScreen.HOME,
    val capability: DeviceCapabilityProfile = DeviceCapabilityProfile(),
    val skills: List<SemanticSkill> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val metrics: MetricsSnapshot = MetricsSnapshot(),
    val policies: List<AppPolicy> = emptyList(),
    val patches: List<SkillPatchCandidate> = emptyList(),
    val activity: AgentActivity = AgentActivity.Idle,
    val pendingConfirmation: PendingConfirmation? = null,
    val recording: RecordingState = RecordingState(),
    val compilation: TeachDraft? = null,
    val selectedSkillId: String? = null,
    val skillVersions: List<SemanticSkill> = emptyList(),
    val selectedSkillLastTask: AgentTask? = null,
    val selectedTask: AgentTask? = null,
    val selectedTaskEvents: List<ExecutionEvent> = emptyList(),
    val selectedTaskOutcome: TaskOutcome? = null,
    val editPreview: SkillEditPreview? = null,
    val privacy: PrivacySettings = PrivacySettings(),
    val killSwitchEngaged: Boolean = false,
    val touchIndicatorEnabled: Boolean = true,
    val nanoDownloadConsented: Boolean = false,
    val nanoDownload: ModelDownloadProgress? = null,
    val teachLabel: String = "My automation",
    val loading: Boolean = true,
    val message: String? = null,
) {
    val selectedSkill: SemanticSkill? get() = skills.firstOrNull { it.id == selectedSkillId }
}
