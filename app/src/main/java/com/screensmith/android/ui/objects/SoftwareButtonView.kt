package com.screensmith.android.ui.objects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.FontLoader
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.mqtt.actionOf

@Composable
fun SoftwareButtonView(
    obj: ScreenObject,
    project: Project,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
) {
    val props = obj.properties
    val backgroundColor = props.colorOrDefault("backgroundColor", Color.White)
    val borderColor = props.colorOrDefault("borderColor", Color(0xFFCCCCCC))
    val borderWidth = props.double("borderWidth", 1.0)
    val cornerRadius = props.double("cornerRadius", 4.0)
    val textColor = props.colorOrDefault("textColor", Color.Black)
    val text = props.string("text", "Button")

    val fontId = props.stringOrNull("fontId")
    val fontMeta = project.fonts.find { it.id == fontId }
    val fontFamily: FontFamily = fontMeta?.path?.let { assetFileOf(it) }?.let { FontLoader.load(it) } ?: FontFamily.Default
    val fontSizeSp = (fontMeta?.size ?: 14).sp

    val shape = RoundedCornerShape(cornerRadius.dp)

    Box(
        modifier = Modifier
            .offset(x = obj.x.dp, y = obj.y.dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .background(backgroundColor, shape)
            .border(width = borderWidth.dp, color = borderColor, shape = shape)
            .clickable {
                obj.actionOf()?.let(onAction)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp,
            fontFamily = fontFamily,
            modifier = Modifier.fillMaxSize(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
