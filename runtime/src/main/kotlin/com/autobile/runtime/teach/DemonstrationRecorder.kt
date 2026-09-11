package com.autobile.runtime.teach

import com.autobile.core.common.Ids
import com.autobile.core.common.TimeSource
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.Direction
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.Point
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.UiNode
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.accessibility.ObservedEvent
import com.autobile.runtime.perception.ScreenObserver
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records what the user does during a teaching session.
 *
 * The recorder captures the screen before and after each interaction, plus the element
 * that was touched, so the compiler later has enough evidence to work out *why* a step
 * happened rather than only that it did.
 *
 * It records generously and judges nothing: mis-taps and dead ends are kept, because
 * deciding what was incidental is a separate job done afterwards with the whole session
 * visible, not a guess made in the moment.
 */
class DemonstrationRecorder(
    private val perception: ScreenObserver,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource.System,
) {

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val events = mutableListOf<TraceEvent>()
    private var collector: Job? = null
    private var lastSnapshot: ScreenSnapshot? = null
    private var traceId: String? = null
    private var startedAt: Long = 0

    fun start(label: String) {
        if (_state.value.recording) return
        traceId = Ids.trace()
        startedAt = time.nowMillis()
        events.clear()
        lastSnapshot = null
        _state.value = RecordingState(recording = true, label = label, eventCount = 0)

        collector = scope.launch {
            // Capture the starting screen so the first interaction has a "before".
            lastSnapshot = (perception.observe() as? PerceptionResult.Success)?.snapshot
            AccessibilityBridge.events.collect { event -> record(event) }
        }
    }

    suspend fun stop(): DemonstrationTrace? = mutex.withLock {
        if (!_state.value.recording) return null
        collector?.cancel()
        collector = null
        val trace = DemonstrationTrace(
            id = traceId ?: Ids.trace(),
            label = _state.value.label,
            startedAt = startedAt,
            endedAt = time.nowMillis(),
            events = events.toList(),
        )
        _state.value = RecordingState()
        trace
    }

    fun cancel() {
        collector?.cancel()
        collector = null
        events.clear()
        _state.value = RecordingState()
    }

    private suspend fun record(event: ObservedEvent): Unit = mutex.withLock {
        if (!_state.value.recording) return@withLock
        // Events from Autobile's own interface are not part of what is being taught.
        if (event.packageName.startsWith(OWN_PACKAGE_PREFIX)) return@withLock

        val action = event.toObservedAction() ?: return@withLock
        val before = lastSnapshot
        val after = (perception.observe(SETTLE_MS) as? PerceptionResult.Success)?.snapshot
        val target = after?.let { findLikelyTarget(it, event) } ?: before?.let { findLikelyTarget(it, event) }

        events += TraceEvent(
            id = Ids.event(),
            timestamp = event.timestamp,
            packageName = event.packageName,
            windowContext = event.className,
            before = before,
            after = after,
            action = action,
            targetNode = target,
            coordinates = target?.let { Point(it.bounds.centerX, it.bounds.centerY) },
            inputValue = (action as? ObservedAction.TextInput)?.value,
            stateTransition = StateTransition(
                fromPackage = before?.packageName.orEmpty(),
                toPackage = after?.packageName.orEmpty(),
                fromWindow = before?.windowTitle.orEmpty(),
                toWindow = after?.windowTitle.orEmpty(),
            ),
        )
        lastSnapshot = after ?: before
        _state.value = _state.value.copy(
            eventCount = events.size,
            lastAction = action.describe(),
            currentApp = event.packageName,
        )
    }

    /**
     * Locates the element the event refers to.
     *
     * Accessibility events carry the element's text but not a handle to it, so the node
     * is matched back by label. Focused and selected nodes are preferred, since those
     * are what the framework itself considers current.
     */
    private fun findLikelyTarget(snapshot: ScreenSnapshot, event: ObservedEvent): UiNode? {
        val text = event.text.trim()
        val description = event.contentDescription?.trim()

        if (text.isNotEmpty() || !description.isNullOrEmpty()) {
            snapshot.nodes.firstOrNull { node ->
                val label = node.label()
                label.isNotBlank() && (label.equals(text, true) || label.equals(description, true))
            }?.let { return it }
        }
        return snapshot.nodes.firstOrNull { it.focused && it.isActionable() }
            ?: snapshot.nodes.firstOrNull { it.selected && it.isActionable() }
    }

    private fun ObservedEvent.toObservedAction(): ObservedAction? = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> ObservedAction.Click
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> ObservedAction.LongClick
        AccessibilityEvent.TYPE_VIEW_SELECTED -> ObservedAction.Select
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> ObservedAction.TextInput(text)
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> ObservedAction.Scroll(Direction.DOWN)
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
            if (packageName != lastSnapshot?.packageName) {
                ObservedAction.AppOpen(packageName)
            } else {
                ObservedAction.WindowChange(className)
            }
        // Content changes and focus moves are ambient noise, not user intent.
        else -> null
    }

    private companion object {
        const val SETTLE_MS = 200L
        const val OWN_PACKAGE_PREFIX = "com.autobile"
    }
}

data class RecordingState(
    val recording: Boolean = false,
    val label: String = "",
    val eventCount: Int = 0,
    val lastAction: String = "",
    val currentApp: String = "",
)

fun ObservedAction.describe(): String = when (this) {
    is ObservedAction.Click -> "Tapped"
    is ObservedAction.LongClick -> "Held"
    is ObservedAction.Select -> "Selected"
    is ObservedAction.TextInput -> "Typed"
    is ObservedAction.Scroll -> "Scrolled"
    is ObservedAction.Back -> "Went back"
    is ObservedAction.Home -> "Home"
    is ObservedAction.AppOpen -> "Opened ${packageName.substringAfterLast('.')}"
    is ObservedAction.WindowChange -> "Moved to a new screen"
}
