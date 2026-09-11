package com.autobile.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.data.PatchStatus
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.DeviceCapabilityProfile
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.TaskState
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.edit.SkillEditPreview
import com.autobile.app.ui.AppScreen
import com.autobile.app.ui.AppUiState
import com.autobile.app.ui.AppViewModel
import com.autobile.app.ui.AutobileTheme
import com.autobile.app.ui.OnboardingStep
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory((application as AutobileApplication).graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Recent Android versions lay every app out edge to edge whether or not it asks.
        // Declaring it explicitly is what makes the system bar insets reach Compose, so
        // the first screen is not drawn underneath the status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AutobileTheme {
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { viewModel.refreshCapabilities() }
                AutobileRoot(
                    viewModel = viewModel,
                    requestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCapabilities()
    }
}

private class AppViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutobileRoot(
    viewModel: AppViewModel,
    requestNotificationPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    state.pendingConfirmation?.let { pending ->
        RiskConfirmationDialog(
            title = pending.step.description.ifBlank { pending.step.target.intentLabel },
            reason = pending.decision.reason,
            categories = pending.decision.categories.joinToString { it.name.humanize() },
            onAnswer = viewModel::approvePending,
        )
    }

    when (val preview = state.editPreview) {
        is SkillEditPreview.Ready -> EditConfirmationDialog(preview, viewModel)
        is SkillEditPreview.Rejected -> AlertDialog(
            onDismissRequest = viewModel::dismissEditPreview,
            title = { Text("Change not applied") },
            text = { Text(preview.reason) },
            confirmButton = { TextButton(onClick = viewModel::dismissEditPreview) { Text("OK") } },
        )
        null -> Unit
    }

    val teaching = state.screen == AppScreen.TEACH || state.screen == AppScreen.TEACH_REVIEW
    if (!state.onboardingComplete && !teaching) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            OnboardingScreen(
                state = state,
                viewModel = viewModel,
                context = context,
                requestNotificationPermission = requestNotificationPermission,
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    val rootScreen = state.screen in listOf(AppScreen.HOME, AppScreen.HISTORY, AppScreen.SETTINGS)
    if (!rootScreen) {
        BackHandler {
            if (state.recording.recording) viewModel.cancelTeaching() else viewModel.navigate(AppScreen.HOME)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle(state)) },
                navigationIcon = {
                    if (!rootScreen) {
                        IconButton(onClick = {
                            if (state.recording.recording) viewModel.cancelTeaching() else viewModel.navigate(AppScreen.HOME)
                        }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = {
                    if (state.screen == AppScreen.SETTINGS) {
                        IconButton(onClick = viewModel::refreshCapabilities) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh device status")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (rootScreen) {
                NavigationBar {
                    BottomDestination(AppScreen.HOME, state.screen, "Home", Icons.Outlined.Home, viewModel)
                    BottomDestination(AppScreen.HISTORY, state.screen, "History", Icons.Outlined.History, viewModel)
                    BottomDestination(AppScreen.SETTINGS, state.screen, "Settings", Icons.Outlined.Settings, viewModel)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                AppScreen.HOME -> HomeScreen(state, viewModel)
                AppScreen.HISTORY -> HistoryScreen(state, viewModel)
                AppScreen.SETTINGS -> SettingsScreen(state, viewModel, context)
                AppScreen.SKILL_DETAIL -> SkillDetailScreen(state, viewModel)
                AppScreen.HISTORY_DETAIL -> HistoryDetailScreen(state, viewModel)
                AppScreen.TEACH -> TeachScreen(state, viewModel, context)
                AppScreen.TEACH_REVIEW -> TeachReviewScreen(state, viewModel)
            }
            if (state.loading) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun RowScope.BottomDestination(
    target: AppScreen,
    current: AppScreen,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    viewModel: AppViewModel,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { viewModel.navigate(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun OnboardingScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    context: Context,
    requestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Show it once. Your phone learns the rest.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Step ${state.onboardingStep.ordinal + 1} of 5", color = MaterialTheme.colorScheme.secondary)
        LinearProgressIndicator(
            progress = { (state.onboardingStep.ordinal.coerceAtMost(4) + 1) / 5f },
            modifier = Modifier.fillMaxWidth(),
        )

        when (state.onboardingStep) {
            OnboardingStep.CAPABILITY -> {
                SectionTitle("What this phone can do")
                CapabilityRows(state.capability)
                Text(state.capability.runtimeProfile.explanation)
                if (state.capability.deviceAi.isDownloadable) {
                    DeviceModelDownload(state, viewModel)
                }
                Button(onClick = viewModel::nextOnboardingStep, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
            OnboardingStep.PERMISSIONS -> {
                SectionTitle("Give access when you are ready")
                Text("Autobile needs screen control to learn and repeat tasks. Notification and overlay access enable triggers and visible progress.")
                PermissionRow("Screen control", state.capability.accessibilityConnected) {
                    context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermissionRow("Notification triggers", state.capability.notificationAccessGranted) {
                    requestNotificationPermission()
                    context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionRow("Progress over other apps", state.capability.overlayGranted) {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                }
                Button(
                    onClick = viewModel::nextOnboardingStep,
                    enabled = state.capability.accessibilityConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
                Text("Only screen control is required. The other permissions stay optional.", style = MaterialTheme.typography.bodySmall)
            }
            OnboardingStep.INSTANT_TASK -> {
                SectionTitle("See a task run")
                Text("Your first automation opens Android settings and checks that the expected app appeared.")
                Button(onClick = viewModel::runStarterTask, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run first automation")
                }
                TextButton(onClick = viewModel::nextOnboardingStep) { Text("Skip this example") }
            }
            OnboardingStep.TEACH -> {
                SectionTitle("Teach a short routine")
                Text("Choose a task with three to five steps. Autobile records the meaning of the controls, not fixed screen coordinates.")
                Button(onClick = { viewModel.startTeaching() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start teaching")
                }
                TextButton(onClick = viewModel::nextOnboardingStep) { Text("I will teach one later") }
            }
            OnboardingStep.REPLAY -> {
                SectionTitle("Replay what your phone learned")
                val learned = state.skills.firstOrNull()
                if (learned == null) {
                    Text("Teach an automation first, or finish setup and create one from Home.")
                } else {
                    SkillSummaryCard(learned, onClick = {})
                    Button(onClick = { viewModel.runSkill(learned.id, replay = true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Replay ${learned.name}")
                    }
                }
                OutlinedButton(onClick = viewModel::finishOnboarding, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
            }
            OnboardingStep.COMPLETE -> Unit
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, viewModel: AppViewModel) {
    var command by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { AgentActivityCard(state.activity, viewModel) }
        item {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("What should your phone do?") },
                placeholder = { Text("Run daily sales, or teach a new task") },
                trailingIcon = {
                    IconButton(onClick = { viewModel.submitCommand(command); command = "" }) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Run command")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Automations")
                FilledTonalButton(onClick = { viewModel.navigate(AppScreen.TEACH) }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Teach")
                }
            }
        }
        if (state.skills.isEmpty()) {
            item { EmptyCard("No automations yet", "Show Autobile one routine and it will appear here.") }
        } else {
            items(state.skills, key = { it.id }) { skill ->
                SkillSummaryCard(skill) { viewModel.selectSkill(skill.id) }
            }
        }
    }
}

@Composable
private fun AgentActivityCard(activity: AgentActivity, viewModel: AppViewModel) {
    when (activity) {
        AgentActivity.Idle -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Color(0xFF6B7C74))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Agent ready", fontWeight = FontWeight.SemiBold)
                    Text("Runs stay visible and can be stopped at any time", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is AgentActivity.Running -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Agent working · ${activity.skillName}", fontWeight = FontWeight.Bold)
                    Text(activity.stepDescription)
                    Text("Step ${activity.stepIndex + 1} of ${activity.totalSteps}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = viewModel::stopAgent) { Icon(Icons.Outlined.Stop, contentDescription = "Stop agent") }
            }
        }
    }
}

@Composable
private fun SkillSummaryCard(skill: SemanticSkill, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            StatusDot(if (skill.enabled) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(skill.goal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(if (skill.enabled) skill.trigger.describe() else "Paused", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Text("${(skill.confidence.score * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SkillDetailScreen(state: AppUiState, viewModel: AppViewModel) {
    val skill = state.selectedSkill ?: return EmptyCard("Automation unavailable", "It may have been deleted.")
    var editText by remember(skill.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(skill.goal)
            }
            Switch(checked = skill.enabled, onCheckedChange = { viewModel.setSkillEnabled(skill, it) })
        }
        Button(onClick = { viewModel.runSkill(skill.id) }, enabled = skill.enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text("Run now")
        }
        DetailCard("Trigger", skill.trigger.describe())
        DetailCard("Autonomy", skill.effectiveAutonomy().label)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutonomyLevel.entries.forEach { level ->
                FilterChip(
                    selected = skill.autonomyLevel == level,
                    onClick = { viewModel.setSkillAutonomy(skill, level) },
                    label = { Text(level.label) },
                )
            }
        }
        DetailCard("Runtime requirements", skill.runtimeRequirements.describe())
        DetailCard(
            "Last execution",
            state.selectedSkillLastTask?.let { "${it.state.displayName} · ${formatTime(it.finishedAt ?: it.createdAt)}" } ?: "Never",
        )
        DetailCard("Success rate", "${(skill.confidence.successRate * 100).toInt()}% across ${skill.confidence.executionCount} runs")
        SectionTitle("Confidence")
        LinearProgressIndicator(progress = { skill.confidence.score }, modifier = Modifier.fillMaxWidth())
        Text("${skill.confidence.band.name.humanize()} · ${(skill.confidence.score * 100).toInt()}%")

        SectionTitle("Change with plain language")
        OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            label = { Text("For example: Run at 8:30 instead") },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { viewModel.previewEdit(skill, editText) },
            enabled = editText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Review change") }

        val patches = state.patches.filter { it.skillId == skill.id && it.status == PatchStatus.PENDING }
        if (patches.isNotEmpty()) {
            SectionTitle("Repairs to review")
            patches.forEach { PatchCard(it, viewModel) }
        }

        SectionTitle("Version history")
        state.skillVersions.forEach { version ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Version ${version.version}", fontWeight = FontWeight.SemiBold)
                        Text(version.history.lastOrNull()?.reason ?: "Saved version", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.rollbackSkill(skill.id, version.version) }) { Text("Restore") }
                }
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete automation") }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${skill.name}?") },
            text = { Text("Its saved versions and repair proposals will also be removed. Execution history stays available.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteSkill(skill) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PatchCard(candidate: SkillPatchCandidate, viewModel: AppViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(candidate.summary, fontWeight = FontWeight.SemiBold)
            if (candidate.requiresUserConfirmation) Text("This repair may change meaning. Review carefully.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.reviewPatch(candidate, true) }) { Text("Apply") }
                TextButton(onClick = { viewModel.reviewPatch(candidate, false) }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: AppUiState, viewModel: AppViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.tasks.isEmpty()) item { EmptyCard("No runs yet", "Completed, stopped, and deferred automations will appear here.") }
        items(state.tasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth().clickable { viewModel.selectTask(task.id) }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(task.state.color())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.goal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${formatTime(task.createdAt)} · ${task.origin.name.humanize()}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(task.state.displayName, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun HistoryDetailScreen(state: AppUiState, viewModel: AppViewModel) {
    val task = state.selectedTask ?: return EmptyCard("Run unavailable", "Its history could not be loaded.")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(task.goal, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        DetailCard("Result", state.selectedTaskOutcome?.status?.name?.humanize() ?: task.state.displayName)
        DetailCard("Runtime", "${task.deterministicStepCount} deterministic · ${task.deviceAiCallCount} on-device · ${task.cloudCallCount} cloud")
        task.failureReason?.let { DetailCard("What happened", it) }
        task.skillId?.let { skillId ->
            Button(onClick = { viewModel.runSkill(skillId, replay = true) }, modifier = Modifier.fillMaxWidth()) { Text("Replay") }
        }
        SectionTitle("Timeline")
        state.selectedTaskEvents.forEach { EventRow(it) }
    }
}

@Composable
private fun EventRow(event: ExecutionEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StatusDot(if (event.success == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(event.type.name.humanize(), fontWeight = FontWeight.SemiBold)
            if (event.message.isNotBlank()) Text(event.message)
            Text(formatTime(event.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun TeachScreen(state: AppUiState, viewModel: AppViewModel, context: Context) {
    var label by remember { mutableStateOf(state.teachLabel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (!state.recording.recording) {
            SectionTitle("Teach by doing")
            Text("Start recording, switch to another app, and complete the routine once. Return to Autobile from the persistent notification when you are done.")
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Automation name") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.startTeaching(label) }, modifier = Modifier.fillMaxWidth()) { Text("Start recording") }
        } else {
            SectionTitle("Learning ${state.recording.label}")
            Text("${state.recording.eventCount} actions captured", style = MaterialTheme.typography.headlineSmall)
            state.recording.currentApp.takeIf { it.isNotBlank() }?.let { Text("Current app: ${it.substringAfterLast('.')}") }
            state.recording.lastAction.takeIf { it.isNotBlank() }?.let { DetailCard("Last action", it) }
            Button(onClick = { openHome(context) }, modifier = Modifier.fillMaxWidth()) { Text("Continue demonstration") }
            FilledTonalButton(onClick = viewModel::finishTeaching, modifier = Modifier.fillMaxWidth()) { Text("Finish and understand") }
            TextButton(onClick = viewModel::cancelTeaching, modifier = Modifier.fillMaxWidth()) { Text("Cancel recording") }
        }
    }
}

@Composable
private fun TeachReviewScreen(state: AppUiState, viewModel: AppViewModel) {
    val draft = state.compilation ?: return EmptyCard("Nothing to review", "Finish a teaching session first.")
    val skill = draft.skill
    var correction by remember(skill.id) { mutableStateOf("") }
    var scheduled by remember(skill.id) { mutableStateOf(skill.trigger is TriggerSpec.Time) }
    var hour by remember(skill.id) { mutableStateOf((skill.trigger as? TriggerSpec.Time)?.hour?.toString() ?: "09") }
    var minute by remember(skill.id) { mutableStateOf((skill.trigger as? TriggerSpec.Time)?.minute?.toString() ?: "00") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("This is what I understood")
        Text(draft.summary, style = MaterialTheme.typography.titleMedium)
        Text("Review the goal and anything that can change before saving. ${draft.discardedSteps} incidental actions were left out.")
        OutlinedTextField(value = skill.name, onValueChange = viewModel::updateTeachName, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = skill.goal, onValueChange = viewModel::updateTeachGoal, label = { Text("Goal") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        SectionTitle("What changes each run")
        if (skill.variables.isEmpty()) Text("No changing values detected") else skill.variables.forEach { Text("• ${it.name}: ${it.description.ifBlank { it.binding::class.simpleName.orEmpty() }}") }
        SectionTitle("What stays fixed")
        if (skill.constants.isEmpty()) Text("No fixed values detected") else skill.constants.forEach { Text("• ${it.name}: ${it.value}") }
        SectionTitle("Steps")
        skill.steps.forEachIndexed { index, step -> Text("${index + 1}. ${step.description.ifBlank { step.target.intentLabel }}") }
        SectionTitle("Something not right?")
        Text("Describe the correction and the steps will be rewritten, not just the wording.")
        OutlinedTextField(
            value = correction,
            onValueChange = { correction = it },
            label = { Text("For example: send net sales, not gross") },
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = { viewModel.correctUnderstanding(correction); correction = "" },
            enabled = correction.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Correct my understanding") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Run on a schedule", modifier = Modifier.weight(1f))
            Switch(checked = scheduled, onCheckedChange = {
                scheduled = it
                viewModel.updateTeachSchedule(if (it) hour.toIntOrNull() else null, if (it) minute.toIntOrNull() else null)
            })
        }
        if (scheduled) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hour, { hour = it; viewModel.updateTeachSchedule(it.toIntOrNull(), minute.toIntOrNull()) }, label = { Text("Hour") }, modifier = Modifier.weight(1f))
                OutlinedTextField(minute, { minute = it; viewModel.updateTeachSchedule(hour.toIntOrNull(), it.toIntOrNull()) }, label = { Text("Minute") }, modifier = Modifier.weight(1f))
            }
        }
        SectionTitle("Autonomy")
        AutonomyLevel.entries.forEach { level ->
            FilterChip(selected = skill.autonomyLevel == level, onClick = { viewModel.updateTeachAutonomy(level) }, label = { Text(level.label) })
        }
        if (draft.usedCloud) Text("Cloud assistance was used to understand this demonstration.", color = MaterialTheme.colorScheme.secondary)
        Button(onClick = viewModel::saveTeaching, modifier = Modifier.fillMaxWidth()) { Text("Save automation") }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, viewModel: AppViewModel, context: Context) {
    var apiKey by remember { mutableStateOf("") }
    var endpoint by remember(state.privacy.cloudEndpoint) { mutableStateOf(state.privacy.cloudEndpoint) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Device")
        CapabilityRows(state.capability)
        Text(state.capability.runtimeProfile.explanation)
        state.capability.restrictions.forEach { Text("• ${it.detail}", style = MaterialTheme.typography.bodySmall) }
        if (state.capability.deviceAi.isDownloadable) DeviceModelDownload(state, viewModel)

        SectionTitle("Safety")
        SettingToggle("Stop all automations", "Immediately stops the current run and blocks every trigger.", state.killSwitchEngaged, viewModel::setKillSwitch)
        SettingToggle("Touch indicator", "Highlight the control the agent is about to use.", state.touchIndicatorEnabled, viewModel::setTouchIndicator)

        SectionTitle("Cloud assistance")
        SettingToggle("Allow cloud escalation", "Used only when on-device options cannot complete a bounded decision.", state.privacy.cloudEnabled, viewModel::updateCloudEnabled)
        SettingToggle("Allow cropped screenshots", "Screen images stay blocked unless this separate permission is on.", state.privacy.allowScreenshotToCloud, viewModel::updateCloudScreenshots)
        SettingToggle("Mask sensitive text", "Redact email, payment, and one-time-code patterns before inference.", state.privacy.maskSensitiveFields, viewModel::updateMasking)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Cloud endpoint") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { viewModel.updateCloudEndpoint(endpoint) }, modifier = Modifier.fillMaxWidth()) { Text("Save endpoint") }
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.privacy.cloudApiKeyPresent) "Replace cloud credential" else "Cloud credential") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.setCloudApiKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Store credential on device") }

        SectionTitle("App policies")
        if (state.policies.isEmpty()) Text("No sensitive installed apps were detected. Unknown apps ask before acting by default.")
        state.policies.forEach { PolicyRow(it, viewModel) }
        TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Review screen-control access") }

        SectionTitle("Metrics")
        MetricRow("Autonomous tasks this week", state.metrics.weeklyAutonomousTasksCompleted.toString())
        MetricRow("Task success", state.metrics.taskSuccessRate.percent())
        MetricRow("On-device resolution", state.metrics.localResolutionRate.percent())
        MetricRow("Zero-cloud completion", state.metrics.noCloudCompletionRate.percent())
        MetricRow("Recovery success", state.metrics.recoverySuccessRate.percent())
        MetricRow("Cloud efficiency", state.metrics.escalationEfficiency.percent())
        MetricRow("False success", state.metrics.falseSuccessRate.percent())
    }
}

@Composable
private fun PolicyRow(policy: AppPolicy, viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(policy.label.ifBlank { policy.packageName.substringAfterLast('.') }, fontWeight = FontWeight.SemiBold)
                Text(policy.category.name.humanize(), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                AssistChip(onClick = { expanded = true }, label = { Text(policy.mode.displayName) })
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppPolicyMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.displayName) }, onClick = { expanded = false; viewModel.savePolicy(policy, mode) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceModelDownload(state: AppUiState, viewModel: AppViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On-device model available to download", fontWeight = FontWeight.SemiBold)
            Text("The model can be large and may use mobile data. Downloading starts only after you agree.")
            state.nanoDownload?.let { progress ->
                if (progress.totalBytes > 0) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                Text(progress.state.name.humanize())
                progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Button(
                onClick = viewModel::consentAndDownloadDeviceModel,
                enabled = state.nanoDownload == null || state.nanoDownload.state == com.autobile.ai.mlkit.DownloadState.FAILED,
            ) {
                Text(if (state.nanoDownload?.state == com.autobile.ai.mlkit.DownloadState.FAILED) "Try download again" else "Agree and download")
            }
        }
    }
}

@Composable
private fun CapabilityRows(profile: DeviceCapabilityProfile) {
    CapabilityRow("Phone control", profile.canControlScreen)
    CapabilityRow("Screen understanding", profile.canUnderstandScreenVisually || profile.accessibilityConnected)
    CapabilityRow("On-device AI", profile.deviceAi.isUsable)
    CapabilityRow("Cloud AI", profile.cloud.isUsable)
    DetailCard("Runtime profile", profile.runtimeProfile.displayName)
}

@Composable
private fun CapabilityRow(label: String, available: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        // An unavailable capability is muted rather than coloured like an available one.
        // This is the first screen anyone sees, and a green "Not available" reads as
        // approval at a glance, which is the opposite of what it means.
        Text(
            text = if (available) "Available" else "Not available",
            color = if (available) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (available) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, open: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (granted) Text("Granted", color = MaterialTheme.colorScheme.primary) else OutlinedButton(onClick = open) { Text("Open settings") }
        }
    }
}

@Composable
private fun RiskConfirmationDialog(title: String, reason: String, categories: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Allow this action?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(reason)
                if (categories.isNotBlank()) Text("Impact: $categories", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Allow once") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Do not allow") } },
    )
}

@Composable
private fun EditConfirmationDialog(preview: SkillEditPreview.Ready, viewModel: AppViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissEditPreview,
        title = { Text("Review this change") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(preview.summary, fontWeight = FontWeight.SemiBold)
                DetailCard("Before", preview.original.describeForDiff())
                DetailCard("After", preview.updated.describeForDiff())
                if (preview.meaningChanged) {
                    Text("This changes what the automation means. Apply only if the new result is exactly what you intend.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { viewModel.applyEdit(preview) }) { Text("Apply change") } },
        dismissButton = { TextButton(onClick = viewModel::dismissEditPreview) { Text("Cancel") } },
    )
}

@Composable
private fun SettingToggle(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(value)
        }
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun screenTitle(state: AppUiState): String = when (state.screen) {
    AppScreen.HOME -> "Autobile"
    AppScreen.HISTORY -> "Execution history"
    AppScreen.SETTINGS -> "Settings"
    AppScreen.SKILL_DETAIL -> state.selectedSkill?.name ?: "Automation"
    AppScreen.HISTORY_DETAIL -> "Replay"
    AppScreen.TEACH -> "Teach"
    AppScreen.TEACH_REVIEW -> "Review understanding"
}

private fun TriggerSpec.describe(): String = when (this) {
    TriggerSpec.Manual -> "Manual"
    is TriggerSpec.Time -> describe()
    is TriggerSpec.Notification -> describe()
}

private fun com.autobile.core.model.RuntimeRequirements.describe(): String = buildList {
    if (requiresUnlockedDevice) add("Unlocked device")
    if (requiresScreenshot) add("Screenshot")
    if (requiresDeviceAi) add("On-device AI")
    if (requiresCloud) add("Cloud")
    if (requiresNetwork) add("Network")
    if (requiredPackages.isNotEmpty()) add(requiredPackages.joinToString { it.substringAfterLast('.') })
}.joinToString(" · ").ifBlank { "No special requirements" }

private fun SemanticSkill.describeForDiff(): String = "$name · ${trigger.describe()} · $goal"

private fun TaskState.color(): Color = when (this) {
    TaskState.COMPLETED -> Color(0xFF0B6B57)
    TaskState.RUNNING -> Color(0xFF1565C0)
    TaskState.FAILED, TaskState.BLOCKED -> Color(0xFFBA1A1A)
    TaskState.CANCELLED -> Color(0xFF8A5D00)
    TaskState.WAITING_FOR_REASONING, TaskState.DEFERRED -> Color(0xFF8A5D00)
    else -> Color.Gray
}

private fun String.humanize(): String = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun Float.percent(): String = "${(this * 100).toInt()}%"
private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun openHome(context: Context) {
    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
