package com.screensmith.android.ui.objects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.screensmith.android.data.ButtonAction
import com.screensmith.android.data.Project
import com.screensmith.android.data.ScreenObject
import com.screensmith.android.mqtt.actionOf
import com.screensmith.android.ui.LocalBundleInstallation

/**
 * A SoftwareButton: a Material 3 common button, blitted.
 *
 * Nothing about how it looks is decided here, and that is deliberate. A
 * button is a pill whose radius changes under a finger, in one of three
 * styles whose colours are all derived from the one colour its author set and
 * from what it stands on, with an icon trimmed to its own ink standing beside
 * a label measured in the project's font (the designer's
 * docs/2026-09-19-button-look.md). Drawing that here as well would be a
 * second set of pixels to keep in step with the designer - and its
 * anti-aliased edge would be Skia's rather than the browser's, which is a
 * difference no comparison tolerance hides at a corner.
 *
 * So the designer bakes both states into the bundle and this blits the one
 * that applies, with filtering off, exactly as the screen's own background is
 * blitted. It is the same arrangement every firmware already has - a button
 * reaches a device as two bitmaps - and it is why this file is a hundred
 * lines shorter than the hand-drawn version it replaces (2026-09-22).
 *
 * A bundle exported before that carries no bitmaps, so a button from one
 * draws nothing rather than drawing itself the old way: the old way is not a
 * fallback, it is the look that was retired.
 */
@Composable
fun SoftwareButtonView(
    obj: ScreenObject,
    project: Project,
    assetFileOf: (String) -> java.io.File,
    onAction: (ButtonAction) -> Unit,
) {
    var pressed by remember(obj.id) { mutableStateOf(false) }
    val installation = LocalBundleInstallation.current

    // Decoded once per installation, keyed by which installation the files
    // came from: two bundles have the same file names, so the path alone
    // would hand back the previous project's picture.
    val faces: Map<String, Bitmap> = remember(obj.path, obj.pressedPath, installation) {
        listOfNotNull(obj.path, obj.pressedPath).distinct().mapNotNull { path ->
            val file = assetFileOf(path)
            if (!file.exists()) return@mapNotNull null
            BitmapFactory.decodeFile(file.path)?.let { path to it }
        }.toMap()
    }
    val face = (if (pressed) obj.pressedPath ?: obj.path else obj.path)?.let { faces[it] }

    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset(x = obj.x.toInt().dp, y = obj.y.toInt().dp)
            .size(width = obj.width.dp, height = obj.height.dp)
            .pointerInput(obj.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { obj.actionOf()?.let(onAction) },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = face ?: return@Canvas
            val scale = density.density
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
                    Paint().apply { isAntiAlias = false; isFilterBitmap = false },
                )
            }
        }
    }
}
