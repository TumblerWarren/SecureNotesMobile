package app.securenote.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paper's palette uses system fonts and small vector paths; no downloaded assets or blur. */
object Paper {
    val Background = Color(0xFFF6F1E7)
    val Surface = Color(0xFFFFFCF6)
    val Inset = Color(0xFFEDE7DC)
    val Ink = Color(0xFF272B28)
    val Muted = Color(0xFF686359)
    val Line = Color(0xFFDED7CA)
    val Accent = Color(0xFFA9513F)
    val AccentSoft = Color(0xFFF3DFD5)
    val Media = Color(0xFF252826)
}

object PaperSpacing {
    val Screen = 16.dp
    val Block = 8.dp
    val Section = 24.dp
}

@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    val colors =
        lightColorScheme(
            primary = Paper.Accent,
            onPrimary = Color.White,
            primaryContainer = Paper.AccentSoft,
            onPrimaryContainer = Paper.Ink,
            secondary = Paper.Muted,
            onSecondary = Color.White,
            secondaryContainer = Paper.Inset,
            onSecondaryContainer = Paper.Ink,
            background = Paper.Background,
            onBackground = Paper.Ink,
            surface = Paper.Surface,
            onSurface = Paper.Ink,
            surfaceVariant = Paper.Inset,
            onSurfaceVariant = Paper.Muted,
            outline = Paper.Line,
            outlineVariant = Paper.Line,
            error = Color(0xFF9A3024),
            errorContainer = Color(0xFFF9E2DB),
            surfaceContainer = Paper.Background,
            surfaceContainerLow = Paper.Surface,
            surfaceContainerHigh = Paper.Inset,
        )
    val editorial = TextStyle(fontFamily = FontFamily.Serif)
    val sans = TextStyle(fontFamily = FontFamily.SansSerif)
    MaterialTheme(
        colorScheme = colors,
        typography =
            Typography(
                headlineLarge = editorial.copy(fontSize = 34.sp, lineHeight = 40.sp),
                headlineMedium = editorial.copy(fontSize = 28.sp, lineHeight = 35.sp),
                headlineSmall = editorial.copy(fontSize = 24.sp, lineHeight = 31.sp),
                titleLarge = editorial.copy(fontSize = 22.sp, lineHeight = 28.sp),
                titleMedium =
                    sans.copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
                titleSmall =
                    sans.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                bodyLarge = sans.copy(fontSize = 16.sp, lineHeight = 25.sp),
                bodyMedium = sans.copy(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = sans.copy(fontSize = 12.sp, lineHeight = 18.sp),
                labelLarge =
                    sans.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                labelMedium = sans.copy(fontSize = 12.sp, lineHeight = 17.sp),
                labelSmall = sans.copy(fontSize = 11.sp, lineHeight = 16.sp),
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(26.dp),
            ),
        content = content,
    )
}

/** Locally drawn 24-unit icons keep the UI consistent without a large icon dependency. */
enum class PaperGlyph(val path: String) {
    Lock("M7 10 V7 A5 5 0 0 1 17 7 V10 M5 10 H19 V22 H5 Z M12 15 V18"),
    Search("M20 20 L15.5 15.5 M17 10 A7 7 0 1 1 3 10 A7 7 0 1 1 17 10"),
    Add("M12 5 V19 M5 12 H19"),
    Back("M19 12 H5 M11 6 L5 12 L11 18"),
    Next("M9 5 L16 12 L9 19"),
    More("M12 4 V5 M12 11 V12 M12 18 V19"),
    Note("M6 3 H15 L20 8 V21 H6 Z M15 3 V8 H20 M9 12 H17 M9 16 H15"),
    Book(
        "M12 5 C9 3 5 3 2 4 V20 C5 19 9 19 12 21 C15 19 19 19 22 20 V4 C19 3 15 3 12 5 Z M12 5 V21"
    ),
    Document("M5 3 H14 L19 8 V21 H5 Z M14 3 V8 H19 M8 12 H16 M8 16 H13"),
    Vault("M3 6 H10 L12 8 H21 V20 H3 Z M3 6 V4 H10 L12 6 H21 V8"),
    Media("M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M10 8 L16 12 L10 16 Z"),
    Image("M3 4 H21 V20 H3 Z M3 16 L9 10 L14 15 L17 12 L21 16 M17 8 H17.1"),
    Audio("M10 17 V5 L20 3 V15 M10 8 L20 6 M10 17 A3 3 0 1 1 7 14 H10 M20 15 A3 3 0 1 1 17 12 H20"),
    Settings(
        "M12 8 A4 4 0 1 1 12 16 A4 4 0 1 1 12 8 M9 3 H15 L16 6 L19 7 L21 11 V13 L19 17 L16 18 L15 21 H9 L8 18 L5 17 L3 13 V11 L5 7 L8 6 Z"
    ),
    Pin("M8 3 H16 M9 3 V9 L6 13 V15 H18 V13 L15 9 V3 M12 15 V22"),
    Close("M6 6 L18 18 M18 6 L6 18"),
    Import("M12 3 V15 M7 10 L12 15 L17 10 M4 16 V21 H20 V16"),
    Draw("M4 20 L5 14 L16 3 L21 8 L10 19 Z M5 14 L10 19 M14 5 L19 10"),
    Attach("M8 13 L14 7 A3 3 0 0 1 18 11 L10 19 A5 5 0 0 1 3 12 L12 3 A7 7 0 0 1 22 13 L14 21"),
    Table("M3 4 H21 V20 H3 Z M3 10 H21 M3 15 H21 M11 4 V20"),
    List("M8 6 H21 M8 12 H21 M8 18 H21 M3 6 H3.1 M3 12 H3.1 M3 18 H3.1"),
    Link("M10 8 L13 5 A5 5 0 0 1 20 12 L17 15 M14 16 L11 19 A5 5 0 0 1 4 12 L7 9 M8 16 L16 8"),
    Zoom("M20 20 L16 16 M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10 M6 10 H14 M10 6 V14"),
    Check("M5 12 L10 17 L20 6"),
    Grid("M3 3 H10 V10 H3 Z M14 3 H21 V10 H14 Z M3 14 H10 V21 H3 Z M14 14 H21 V21 H14 Z"),
    Sort("M4 6 H20 M7 12 H17 M10 18 H14"),
    FolderAdd("M3 6 H10 L12 8 H21 V20 H3 Z M12 11 V17 M9 14 H15"),
    Move("M3 6 H10 L12 8 H21 V20 H3 Z M8 14 H17 M13 10 L17 14 L13 18"),
    Trash("M4 6 H20 M9 6 V3 H15 V6 M6 6 L7 21 H17 L18 6 M10 10 V17 M14 10 V17"),
    Home("M3 11 L12 3 L21 11 M5 10 V21 H10 V15 H14 V21 H19 V10"),
    Fit("M3 9 V3 H9 M15 3 H21 V9 M21 15 V21 H15 M9 21 H3 V15"),
    Minus("M5 12 H19"),
    Undo("M9 5 L3 10 L9 15 M3 10 H14 A6 6 0 0 1 14 22"),
    Redo("M15 5 L21 10 L15 15 M21 10 H10 A6 6 0 0 0 10 22"),
    Eraser("M3 14 L13 3 L22 12 L12 22 H9 Z M8 9 L17 18 M12 22 H22"),
    Marker("M4 17 L15 3 L21 8 L10 21 H4 Z M4 17 L10 21 M13 6 L18 11"),
    Hand(
        "M7 12 V5 A2 2 0 0 1 11 5 V11 M11 5 V3 A2 2 0 0 1 15 3 V11 M15 6 A2 2 0 0 1 19 6 V13 M19 9 A2 2 0 0 1 23 9 V16 C23 23 11 24 8 19 L2 12 A2 2 0 0 1 5 10 L8 14"
    ),
    Line("M4 20 L20 4 M3 17 V21 H7 M17 3 H21 V7"),
    Sliders("M3 6 H21 M3 12 H21 M3 18 H21 M7 3 V9 M16 9 V15 M9 15 V21"),
    Play("M8 4 L21 12 L8 20 Z"),
    Pause("M7 4 V20 M17 4 V20"),
    Rewind("M5 8 L1 12 L5 16 M2 12 H12 A7 7 0 1 1 5 20"),
    Forward("M19 8 L23 12 L19 16 M22 12 H12 A7 7 0 1 0 19 20"),
    Previous("M4 4 V20 M20 4 L7 12 L20 20 Z"),
    Skip("M20 4 V20 M4 4 L17 12 L4 20 Z"),
    Shuffle(
        "M3 5 H6 C12 5 12 19 18 19 H22 M18 15 L22 19 L18 23 M3 19 H6 C12 19 12 5 18 5 H22 M18 1 L22 5 L18 9"
    ),
    Repeat("M4 8 H20 L16 4 M20 8 L16 12 M20 16 H4 L8 20 M4 16 L8 12"),
    Volume("M3 9 H7 L13 4 V20 L7 15 H3 Z M17 8 C20 10 20 14 17 16 M20 4 C25 9 25 15 20 20"),
    Timer("M8 2 H16 M12 2 V5 M12 9 V14 L16 16 M21 14 A9 9 0 1 1 3 14 A9 9 0 1 1 21 14"),
    Rotate("M4 8 V2 M4 8 H10 M4 8 A9 9 0 1 1 3 16"),
    RowAdd("M3 3 H21 V15 H3 Z M3 9 H21 M12 17 V23 M9 20 H15"),
    ColumnAdd("M3 3 H15 V21 H3 Z M9 3 V21 M17 12 H23 M20 9 V15"),
    Shield("M12 3 L20 6 V12 C20 17 16 20 12 22 C8 20 4 17 4 12 V6 Z M8 12 L11 15 L16 9"),
}

@Composable
fun PaperIcon(
    glyph: PaperGlyph,
    description: String? = null,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(
        modifier
            .size(22.dp)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description }
                else Modifier
            )
    ) {
        scale(
            size.width / 24f,
            size.height / 24f,
            pivot = androidx.compose.ui.geometry.Offset.Zero,
        ) {
            drawPath(
                path,
                color,
                style = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
