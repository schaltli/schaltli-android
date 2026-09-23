package com.schaltli.android.ui.objects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schaltli.android.data.Project
import com.schaltli.android.data.ScreenObject
import com.schaltli.android.data.ButtonAction
import com.schaltli.android.data.resolveTopicValue
import com.schaltli.android.ui.DynamicObjectView
import com.schaltli.android.ui.sortedByZIndex

/**
 * Evaluates the tab-control's own topic value against each child panel's
 * `comparisonOperator`/`comparisonValue`, first match wins, and renders
 * that panel's children - mirrors the firmware's `getActivePanel`/
 * `evaluateVisibilityCondition` (`ScreenRenderer.cpp:166,661`) exactly,
 * including numeric vs. string comparison per operator. Recursive nesting
 * (a panel containing another tab-control) works for free: each child goes
 * back through [DynamicObjectView], which handles "switcher" the same
 * way at any depth.
 */
@Composable
fun TabControlView(
    obj: ScreenObject,
    project: Project,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
    screenBackgroundColor: String?,
    // Passed straight through to the children: a settable level inside a
    // panel is settable too.
    askedValues: Map<String, String> = emptyMap(),
    onSetLevel: (markerTopic: String, writeTopic: String, value: String) -> Unit = { _, _, _ -> },
    onAsked: (readTopic: String, readValue: String) -> Unit = { _, _ -> },
) {
    val topicRef = obj.properties.stringOrNull("topic")
    val currentValue = resolveTopicValue(topicRef, project, topicValues)

    // Panels are searched in zIndex order, first match wins - the same walk
    // the firmware's getActivePanel() makes. Declaration order was close
    // enough while nothing overlapped, but "first match" only means the same
    // thing on both sides if both sides agree what first is.
    //
    // No value yet: the first panel, so a screen built from tabs is usable
    // before anything has arrived - and no condition gets to match an empty
    // value by accident. Same as getActivePanel() on the firmware and in the
    // designer.
    val panels = obj.children.sortedByZIndex()
    val activePanel = (
        if (!topicRef.isNullOrEmpty() && currentValue.isBlank()) panels.firstOrNull()
        else panels.find { panel ->
            val operator = panel.properties.string("comparisonOperator", "==")
            val comparisonValue = panel.properties.string("comparisonValue", "")
            evaluateCondition(currentValue, operator, comparisonValue)
        }
    ) ?: return

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp),
    ) {
        for (child in activePanel.children.sortedByZIndex()) {
            DynamicObjectView(
                child,
                project,
                topicValues,
                assetFileOf,
                onAction,
                screenBackgroundColor,
                askedValues,
                onSetLevel,
                onAsked,
            )
        }
    }
}

/**
 * Not private - reused by MqttDataLineView for its own two independent
 * arrow-visibility conditions (same operator set/semantics, see that
 * file's header comment).
 */
fun evaluateCondition(currentValue: String, operator: String, comparisonValue: String): Boolean {
    return when (operator) {
        "==" -> currentValue == comparisonValue
        "!=" -> currentValue != comparisonValue
        ">", ">=", "<", "<=" -> {
            val a = currentValue.toDoubleOrNull() ?: return false
            val b = comparisonValue.toDoubleOrNull() ?: return false
            when (operator) {
                ">" -> a > b
                ">=" -> a >= b
                "<" -> a < b
                else -> a <= b
            }
        }
        else -> false
    }
}
