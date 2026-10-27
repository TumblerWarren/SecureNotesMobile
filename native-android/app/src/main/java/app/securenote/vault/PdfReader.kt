package app.securenote.vault

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SecurePdfReader(model: VaultModel, id: String) {
    val context = LocalContext.current
    var attempt by remember(id) { mutableIntStateOf(0) }
    val client = remember(id, attempt) { PdfClient(context) }
    var count by remember(id, attempt) { mutableIntStateOf(0) }
    var error by remember(id, attempt) { mutableStateOf(false) }
    var restored by remember(id, attempt) { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var thumbnails by remember { mutableStateOf(false) }
    var current by remember(id) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(client) {
        try {
            val state = withContext(Dispatchers.IO) { model.store.dao.readingState(id) }
            count = client.open(model.store, id)
            val position = state?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val page =
                position?.optInt("pdfPage", 0)?.coerceIn(0, (count - 1).coerceAtLeast(0)) ?: 0
            list.scrollToItem(page, position?.optInt("offset", 0)?.coerceAtLeast(0) ?: 0)
            restored = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = true
        }
    }
    LaunchedEffect(list, restored) {
        if (restored)
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (page, offset) ->
                    current = page
                    model.queueReadingState(
                        id,
                        org.json.JSONObject().put("pdfPage", page).put("offset", offset).toString(),
                    )
                }
    }
    DisposableEffect(client) {
        val unregister = model.onLock { client.close() }
        onDispose {
            unregister()
            client.close()
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (error)
            Column(Modifier.padding(16.dp)) {
                Text("PDF could not be opened. The encrypted file is kept.")
                TextButton({ attempt++ }) { Text("Retry") }
            }
        else if (count == 0)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        else
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Paper.Inset),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(count, key = { it }) { page -> PdfPage(model, client, page, false) }
            }
        if (count > 0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    { scope.launch { list.animateScrollToItem((current - 1).coerceAtLeast(0)) } },
                    enabled = current > 0,
                ) {
                    PaperIcon(PaperGlyph.Back, "Previous page")
                }
                TextButton(
                    {
                        jumpText = "${current+1}"
                        jump = true
                    },
                    modifier =
                        Modifier.weight(1f).semantics {
                            contentDescription = "Go to page, ${current+1} of $count"
                        },
                ) {
                    Text("${current+1} / $count", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(
                    {
                        scope.launch {
                            list.animateScrollToItem((current + 1).coerceAtMost(count - 1))
                        }
                    },
                    enabled = current + 1 < count,
                ) {
                    PaperIcon(PaperGlyph.Next, "Next page")
                }
                IconButton({ thumbnails = true }) { PaperIcon(PaperGlyph.List, "Pages") }
            }
    }
    if (jump)
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    jumpText,
                    { jumpText = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    {
                        val page = jumpText.toIntOrNull()
                        if (page != null && page in 1..count) {
                            scope.launch { list.scrollToItem(page - 1) }
                            jump = false
                        }
                    },
                    enabled = (jumpText.toIntOrNull() ?: 0) in 1..count,
                ) {
                    Text("Go")
                }
            },
            dismissButton = { TextButton({ jump = false }) { Text("Cancel") } },
        )
    if (thumbnails)
        AlertDialog(
            onDismissRequest = { thumbnails = false },
            title = { Text("Pages") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(count) { page ->
                        Column(
                            Modifier.clickable {
                                scope.launch { list.scrollToItem(page) }
                                thumbnails = false
                            }
                        ) {
                            PdfPage(model, client, page, true)
                            Text("Page ${page+1}", Modifier.padding(8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ thumbnails = false }) { Text("Close") } },
        )
}

@Composable
private fun PdfPage(model: VaultModel, client: PdfClient, page: Int, thumbnail: Boolean) {
    var image by remember(client, page) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(client, page) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var zooming by remember { mutableStateOf(false) }
    var zoomView by remember { mutableStateOf<PdfZoomView?>(null) }
    var zoomError by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width =
            if (thumbnail) 256 else with(density) { maxWidth.toPx().toInt() }.coerceIn(128, 1024)
        LaunchedEffect(client, page, width, retry) {
            failed = false
            try {
                image = client.render(page, width)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
        DisposableEffect(client, page) {
            val unregister = model.onLock {
                image = null
                zoomView?.close()
            }
            onDispose {
                unregister()
                image = null
                zoomView?.close()
            }
        }
        val bitmap = image
        if (bitmap != null)
            Image(
                bitmap.asImageBitmap(),
                "Page ${page+1}",
                Modifier.fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .then(if (!thumbnail) Modifier.clickable { zooming = true } else Modifier),
            )
        else
            Box(
                Modifier.fillMaxWidth().height(if (thumbnail) 140.dp else 300.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) TextButton({ retry++ }) { Text("Retry page ${page+1}") }
                else CircularProgressIndicator()
            }
        if (zooming && bitmap != null)
            Dialog(
                onDismissRequest = { zooming = false },
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        securePolicy = SecureFlagPolicy.SecureOn,
                    ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            TextButton({ zooming = false }) { Text("Done") }
                            TextButton({ zoomView?.fit() }) { Text("Fit width") }
                            TextButton({ zoomView?.zoomBy(1.5f) }) { Text("Zoom in") }
                            TextButton({ zoomView?.zoomBy(1 / 1.5f) }) { Text("Zoom out") }
                            TextButton({ model.lock() }) { Text("Lock") }
                        }
                        if (zoomError)
                            TextButton({
                                zoomView?.retry()
                                zoomError = false
                            }) {
                                Text("Retry sharper preview")
                            }
                        AndroidView(
                            factory = {
                                PdfZoomView(it, client, page, bitmap) { zoomError = it }
                                    .also { zoomView = it }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onRelease = {
                                it.close()
                                zoomView = null
                            },
                        )
                    }
                }
            }
    }
}
