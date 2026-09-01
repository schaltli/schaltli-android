package com.screensmith.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screensmith.android.data.Screen
import kotlinx.coroutines.delay

/**
 * The `showScreenMenu` device action: a list of every screen in the project,
 * the current one marked, any of them one tap away.
 *
 * This is the Android counterpart of the firmware's `ScreenNavigatorOverlay`
 * - the same capability, declared under the same id in the DDF's
 * `deviceActions`, reached the same way (a swipe the project has bound to
 * it). It deliberately does not copy that overlay's *appearance*. There,
 * screens are round tablets flying along a stadium-shaped path around a
 * 360x360 circular panel, sized to a knob; here the display is a tall
 * rectangle and a list is what a tall rectangle is for. The firmware's own
 * header says as much about its layout - "a first-pass default, not a spec",
 * with the designer holding no opinion on it either way - so matching its
 * geometry would be matching an accident of the other screen's shape.
 *
 * What is copied is the timing, because that is about the person and not the
 * panel: the menu was opened deliberately and now has to be read and aimed
 * at, so it waits [IDLE_TIMEOUT_MS] with no interaction before closing
 * itself, and any interaction restarts that countdown.
 *
 * Screens are listed by name rather than by icon. The firmware draws each
 * screen's `pageIconPath`, a mask baked at export time for devices whose DDF
 * sets `needsPageIconsInSize`; the Android DDF sets no such size and the
 * Android export bakes no such masks, so a name is the only thing every
 * screen is guaranteed to have. It is also the thing the author typed.
 */
private const val IDLE_TIMEOUT_MS = 4000L

@Composable
fun ScreenMenuOverlay(
    screens: List<Screen>,
    currentScreenId: String?,
    onSelect: (screenId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (screens.isEmpty()) {
        onDismiss()
        return
    }

    val listState = rememberLazyListState()

    // Restarted whenever the list scrolls - the one interaction that happens
    // without choosing anything, and the one most likely to run out the clock
    // on a project with many screens.
    LaunchedEffect(listState.firstVisibleItemIndex, listState.isScrollInProgress) {
        delay(IDLE_TIMEOUT_MS)
        onDismiss()
    }

    // Opens with the current screen in view rather than at the top: on a
    // project long enough to scroll, "where am I" is the first question the
    // menu exists to answer.
    LaunchedEffect(currentScreenId) {
        val index = screens.indexOfFirst { it.id == currentScreenId }
        if (index > 0) listState.scrollToItem(index)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            // A tap on the scrim dismisses. No ripple: this is a backdrop,
            // not a control, and lighting it up on touch would suggest it is
            // the thing being chosen.
            .clickable(indication = null, interactionSource = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(screens, key = { it.id }) { screen ->
                val isCurrent = screen.id == currentScreenId
                Text(
                    text = screen.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) ACTIVE_TINT else INACTIVE_TINT)
                        .then(
                            if (isCurrent) {
                                Modifier.border(2.dp, ACCENT, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(screen.id) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * The same orange the firmware's overlay marks its active tablet with, which
 * it in turn took from the adornment SVG's accent - so a project moved
 * between the two targets keeps the colour that means "you are here".
 */
private val ACCENT = Color(0xFFFF6600)
private val ACTIVE_TINT = Color(0x33FF6600)
private val INACTIVE_TINT = Color(0xFF333333)
