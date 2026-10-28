package app.securenote.vault

import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

@Composable
internal fun PaperAction(
    label: String,
    glyph: PaperGlyph,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    dark: Boolean = false,
    action: () -> Unit,
) {
    IconButton(
        action,
        enabled = enabled,
        modifier =
            modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = label
                this.selected = selected
                stateDescription =
                    if (!enabled) "Unavailable" else if (selected) "Selected" else "Not selected"
            },
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor = if (selected) Paper.AccentSoft else Color.Transparent,
                contentColor = if (dark) Color.White else if (selected) Paper.Accent else Paper.Ink,
            ),
    ) {
        PaperIcon(glyph)
    }
}

/** Content-sized, scrollable sheet. Secure even when hosted outside the main activity. */
@Composable
internal fun PaperSheet(
    title: String,
    dismiss: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = dismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
                decorFitsSystemWindows = false,
            ),
    ) {
        PaperDialogBars()
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * .92f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = Paper.Background,
            ) {
                Column(Modifier.secureEditing()) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp)
                            .size(36.dp, 4.dp)
                            .background(Paper.Line, RoundedCornerShape(4.dp))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PaperAction("Close", PaperGlyph.Close, action = dismiss)
                    }
                    Column(
                        Modifier.weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        content = content,
                    )
                    if (footer != null) Box(Modifier.fillMaxWidth().padding(16.dp)) { footer() }
                    else Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaperSlider(
    value: Float,
    change: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    finished: (() -> Unit)? = null,
) {
    Slider(
        value,
        change,
        valueRange = range,
        onValueChangeFinished = finished,
        modifier = modifier.heightIn(min = 40.dp),
        thumb = {
            Box(
                Modifier.size(18.dp)
                    .background(Paper.Accent, androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, Paper.Background, androidx.compose.foundation.shape.CircleShape)
            )
        },
        track = { state ->
            SliderDefaults.Track(state, modifier = Modifier.height(3.dp), thumbTrackGapSize = 0.dp)
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.keepInputVisible(): Modifier {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(focused, ime) {
        if (focused) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                kotlinx.coroutines.delay(180)
                requester.bringIntoView()
            }
        }
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}

/** Apply the same readable system bars to every full-screen secure dialog. */
@Composable
internal fun PaperDialogBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        onDispose {}
    }
}
