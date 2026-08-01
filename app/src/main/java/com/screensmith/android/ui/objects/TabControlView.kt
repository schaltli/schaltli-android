package com.screensmith.android.ui.objects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.resolveTopicValue
import com.screensmith.android.ui.DynamicObjectView

/**
 * Evaluates the tab-control's own topic value against each child panel's
 * `comparisonOperator`/`comparisonValue`, first match wins, and renders
 * that panel's children - mirrors the firmware's `getActivePanel`/
 * `evaluateVisibilityCondition` (`ScreenRenderer.cpp:166,661`) exactly,
 * including numeric vs. string comparison per operator. Recursive nesting
 * (a panel containing another tab-control) works for free: each child goes
 * back through [DynamicObjectView], which handles "tab-control" the same
 * way at any depth.
 */
@Composable
fun TabControlView(
    obj: ScreenObject,
    project: Project,
    topicValues: Map<String, String>,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
) {
    val topicRef = obj.properties.stringOrNull("topic")
    val currentValue = resolveTopicValue(topicRef, project, topicValues)

    val activePanel = obj.children.find { panel ->
        val operator = panel.properties.string("comparisonOperator", "==")
        val comparisonValue = panel.properties.string("comparisonValue", "")
        evaluateCondition(currentValue, operator, comparisonValue)
    } ?: return

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp),
    ) {
        for (child in activePanel.children.sortedBy { it.zIndex }) {
            DynamicObjectView(child, project, topicValues, assetFileOf, onAction)
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
