package dev.local.record.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A bounded reveal, never swipe-to-delete; horizontal dragging leaves vertical list scrolling intact. */
@Composable
internal fun SwipeRecordingRow(id: String, enabled: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val distance = with(LocalDensity.current) { 88.dp.toPx() }
    var offset by remember(id) { mutableFloatStateOf(0f) }
    var dragging by remember(id) { mutableStateOf(false) }
    val animated by animateFloatAsState(offset, if (dragging) snap() else spring(), label = "delete-reveal")
    Box(Modifier.clip(RoundedCornerShape(20.dp)).testTag("swipe-recording-$id")) {
        if (enabled && animated < 0f) {
            Box(Modifier.matchParentSize(), contentAlignment = BiasAbsoluteAlignment(1f, 0f)) {
                Surface(onClick = onDelete, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.width(88.dp).fillMaxHeight().testTag("delete-recording-$id")) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        Icon(RecordIcons.Delete, null, Modifier.size(22.dp))
                        Text("删除", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Box(
            Modifier.offset { IntOffset(if (enabled) animated.roundToInt() else 0, 0) }
                .draggable(
                    state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(-distance, 0f) },
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStarted = { dragging = true },
                    onDragStopped = { velocity ->
                        offset = when {
                            velocity < -distance * 4 -> -distance
                            velocity > distance * 4 -> 0f
                            offset < -distance / 2 -> -distance
                            else -> 0f
                        }
                        dragging = false
                    }
                ).semantics {
                    if (enabled) {
                        customActions = listOf(
                            CustomAccessibilityAction("删除录音") {
                                onDelete()
                                true
                            }
                        )
                    }
                }
        ) { content() }
    }
}
