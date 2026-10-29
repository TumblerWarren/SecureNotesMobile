package app.securenote.vault

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** All form content scrolls below pinned chrome; no credentials enter saved state. */
@Composable
internal fun WelcomePage(
    model: VaultModel,
    busy: Boolean,
    compactInput: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().clipToBounds()) {
        val short = maxHeight < 220.dp
        val artHeight by
            animateDpAsState(
                if (short) 0.dp else if (compactInput) 40.dp else 104.dp,
                label = "welcome-art-height",
            )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = if (short) 4.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(artHeight).clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.height * .42f
                        drawCircle(Paper.Header, radius, center)
                        drawCircle(Paper.Line, radius * 1.18f, center, style = Stroke(1.dp.toPx()))
                        drawCircle(
                            Paper.AccentSoft,
                            radius * .14f,
                            center + Offset(radius * 1.6f, -radius * .5f),
                        )
                        drawCircle(
                            Paper.AccentSoft,
                            radius * .09f,
                            center + Offset(-radius * 1.7f, radius * .6f),
                        )
                        drawLine(
                            Paper.Line,
                            center + Offset(-radius * 2.4f, 0f),
                            center + Offset(-radius * 1.5f, 0f),
                            1.dp.toPx(),
                        )
                        drawLine(
                            Paper.Line,
                            center + Offset(radius * 1.5f, 0f),
                            center + Offset(radius * 2.4f, 0f),
                            1.dp.toPx(),
                        )
                    }
                    Image(
                        painterResource(R.drawable.ic_brand),
                        null,
                        Modifier.size((artHeight * .8f).coerceAtLeast(24.dp)),
                    )
                }
                Surface(
                    Modifier.fillMaxWidth().testTag("welcome-card"),
                    shape = RoundedCornerShape(24.dp),
                    color = Paper.Surface,
                    border = BorderStroke(1.dp, Paper.Line),
                ) {
                    Column(
                        Modifier.padding(if (short) 12.dp else 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LockScreen(model, busy, compactInput)
                    }
                }
            }
        }
    }
}
