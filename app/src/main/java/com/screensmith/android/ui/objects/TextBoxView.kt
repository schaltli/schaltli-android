package com.screensmith.android.ui.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screensmith.android.data.FontEntry
import com.screensmith.android.data.FontLoader
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared background/border/text rendering for anything that's fundamentally
 * a text box: static labels and MQTT data fields, same as the designer's
 * own `render-text-box.ts` is shared by `render-label.ts`/
 * `render-mqtt-field.ts` - the same reasoning applies here: the actual
 * pixels must come from one implementation, not two that can drift apart.
 */
@Composable
fun TextBoxView(obj: ScreenObject, project: Project, text: String, assetFileOf: (String) -> java.io.File) {
    val props = obj.properties
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val textColor = (props.stringOrNull("color") ?: props.stringOrNull("textColor"))
        ?.let(::parseHexColor) ?: Color.Black
    val textAlign = when (props.string("textAlign", "left")) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val fontId = (props["fontId"] as? JsonPrimitive)?.contentOrNull
    val fontMeta: FontEntry? = project.fonts.find { it.id == fontId }
    val fontSizeSp = (fontMeta?.size ?: 14).sp
    val fontFamily: FontFamily = fontMeta?.path
        ?.let { assetFileOf(it) }
        ?.let { FontLoader.load(it) }
        ?: FontFamily.Default

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .background(backgroundColor)
            .border(width = 1.dp, color = borderColor),
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp,
            fontFamily = fontFamily,
            textAlign = textAlign,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
