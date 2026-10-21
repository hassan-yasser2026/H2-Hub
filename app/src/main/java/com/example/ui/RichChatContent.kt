package com.example.ui

import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mikepenz.markdown.m3.Markdown
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RichChatSegment {
    data class MarkdownText(val text: String) : RichChatSegment
    data class Formula(val latex: String, val display: Boolean) : RichChatSegment
    data class MermaidDiagram(val source: String) : RichChatSegment
}

internal const val SMART_CHAT_MATH_INSTRUCTION = """
Present mathematical expressions as valid LaTeX: use $$...$$ for standalone equations and $...$ for inline expressions.
For chemical formulas, use readable LaTeX such as $\mathrm{H_2O}$, $\mathrm{CO_2}$, and $\mathrm{H_2SO_4}$ so element counts render as subscripts.
When a diagram would clarify the answer, provide a valid Mermaid flowchart or xychart-beta block.
"""

private val richContentPattern = Regex(
    pattern = """(?s)```mermaid[ \t]*\r?\n(.*?)```|\$\$(.*?)\$\$|(?<!\$)\$([^$\r\n]+)\$"""
)
private val chemicalFormulaPattern = Regex("""(?<![\p{L}\d])(?:H2O|CO2|H2SO4)(?![\p{L}\d])""")

internal fun formatChemicalNotation(content: String): String =
    chemicalFormulaPattern.replace(content) { match ->
        when (match.value) {
            "H2O" -> "H₂O"
            "CO2" -> "CO₂"
            "H2SO4" -> "H₂SO₄"
            else -> match.value
        }
    }

private fun normalizeChemicalLatex(source: String): String =
    chemicalFormulaPattern.replace(source) { match ->
        when (match.value) {
            "H2O" -> """\mathrm{H_2O}"""
            "CO2" -> """\mathrm{CO_2}"""
            "H2SO4" -> """\mathrm{H_2SO_4}"""
            else -> match.value
        }
    }

internal fun parseRichChatContent(content: String): List<RichChatSegment> {
    val segments = mutableListOf<RichChatSegment>()
    var lastEnd = 0
    richContentPattern.findAll(content).forEach { match ->
        if (match.range.first > lastEnd) {
            segments += RichChatSegment.MarkdownText(content.substring(lastEnd, match.range.first))
        }
        when {
            match.groups[1] != null ->
                segments += RichChatSegment.MermaidDiagram(match.groups[1]!!.value.trim())
            match.groups[2] != null ->
                segments += RichChatSegment.Formula(
                    normalizeChemicalLatex(match.groups[2]!!.value.trim()),
                    display = true
                )
            match.groups[3] != null ->
                segments += RichChatSegment.Formula(
                    normalizeChemicalLatex(match.groups[3]!!.value.trim()),
                    display = false
                )
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < content.length) {
        segments += RichChatSegment.MarkdownText(content.substring(lastEnd))
    }
    if (segments.isEmpty()) segments += RichChatSegment.MarkdownText(content)
    return segments
}

@Composable
fun RichChatContent(content: String, modifier: Modifier = Modifier) {
    val segments = remember(content) { parseRichChatContent(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is RichChatSegment.MarkdownText -> if (segment.text.isNotBlank()) {
                    CoderChatContent(segment.text, Modifier.fillMaxWidth(), ::formatChemicalNotation)
                }
                is RichChatSegment.Formula -> RichContentWebCard(
                    source = segment.latex,
                    isDiagram = false,
                    displayMode = segment.display,
                    modifier = Modifier.fillMaxWidth()
                )
                is RichChatSegment.MermaidDiagram -> RichContentWebCard(
                    source = segment.source,
                    isDiagram = true,
                    displayMode = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class CoderContentSegment(
    val text: String,
    val language: String? = null,
    val isCode: Boolean = false
)

private val fencedCodePattern =
    Regex(pattern = """(?s)```([A-Za-z0-9_+#.-]*)[ \t]*\r?\n(.*?)```""")
private val codeTokenPattern = Regex(
    pattern = """(?m)//.*$|/\*[\s\S]*?\*/|#.*$|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`[^`]*`|\b(?:as|async|await|break|class|const|def|else|export|false|for|from|function|if|import|in|interface|let|new|null|return|self|static|this|throw|true|try|var|while|yield)\b|\b\d+(?:\.\d+)?\b"""
)

private fun parseCoderContent(content: String): List<CoderContentSegment> {
    val segments = mutableListOf<CoderContentSegment>()
    var lastEnd = 0
    fencedCodePattern.findAll(content).forEach { match ->
        if (match.range.first > lastEnd) {
            segments += CoderContentSegment(content.substring(lastEnd, match.range.first))
        }
        segments += CoderContentSegment(
            text = match.groupValues[2].trimEnd(),
            language = match.groupValues[1].ifBlank { null },
            isCode = true
        )
        lastEnd = match.range.last + 1
    }
    if (lastEnd < content.length) segments += CoderContentSegment(content.substring(lastEnd))
    if (segments.isEmpty()) segments += CoderContentSegment(content)
    return segments
}

private fun highlightCode(source: String): AnnotatedString = buildAnnotatedString {
    var position = 0
    codeTokenPattern.findAll(source).forEach { token ->
        append(source.substring(position, token.range.first))
        val color = when {
            token.value.startsWith("//") || token.value.startsWith("#") ||
                token.value.startsWith("/*") -> Color(0xFF94A3B8)
            token.value.startsWith("\"") || token.value.startsWith("'") ||
                token.value.startsWith("`") -> Color(0xFF86EFAC)
            token.value.firstOrNull()?.isDigit() == true -> Color(0xFFFBBF24)
            else -> Color(0xFF67E8F9)
        }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) {
            append(token.value)
        }
        position = token.range.last + 1
    }
    append(source.substring(position))
}

@Composable
fun CoderChatContent(content: String, modifier: Modifier = Modifier, transformText: (String) -> String = { it }) {
    val context = LocalContext.current
    val segments = remember(content) { parseCoderContent(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            if (!segment.isCode) {
                if (segment.text.isNotBlank()) {
                    Markdown(content = transformText(segment.text), modifier = Modifier.fillMaxWidth())
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                segment.language?.uppercase(Locale.ROOT) ?: "CODE",
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF5EEAD4),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(ClipData.newPlainText("code", segment.text))
                                    Toast.makeText(context, "تم نسخ الكود", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "نسخ الكود",
                                    tint = Color(0xFF99F6E4)
                                )
                                Text("نسخ", color = Color(0xFF99F6E4))
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = highlightCode(segment.text),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .horizontalScroll(rememberScrollState())
                                    .verticalScroll(rememberScrollState())
                                    .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichContentWebCard(
    source: String,
    isDiagram: Boolean,
    displayMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var renderReady by remember { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var loadedContentKey by remember { mutableStateOf<String?>(null) }
    val contentKey = remember(source, isDiagram, displayMode) {
        "$isDiagram:$displayMode:$source"
    }
    val latestSource by rememberUpdatedState(source)
    val exportPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            webView?.let { saveRenderedImage(context, it) }
        } else {
            Toast.makeText(context, "اسمح بحفظ الصور لتصدير الرسم.", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isDiagram) {
                    IconButton(
                        onClick = { webView?.evaluateJavascript("window.zoomDiagram(1.2)", null) },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, "تكبير الرسم")
                    }
                    IconButton(
                        onClick = { webView?.evaluateJavascript("window.zoomDiagram(0.8)", null) },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(Icons.Default.ZoomOut, "تصغير الرسم")
                    }
                    IconButton(
                        onClick = { webView?.evaluateJavascript("window.resetDiagram()", null) },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, "إعادة ضبط الرسم")
                    }
                }
                IconButton(
                    onClick = {
                        val view = webView
                        if (view == null || !renderReady) {
                            Toast.makeText(context, "المعادلة أو الرسم لم يجهز بعد.", Toast.LENGTH_SHORT).show()
                        } else if (
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            exportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            saveRenderedImage(context, view)
                        }
                    },
                    enabled = renderReady
                ) {
                    Icon(Icons.Default.Image, "حفظ كصورة")
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isDiagram) 260.dp else 104.dp),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(isDiagram)
                        settings.builtInZoomControls = isDiagram
                        settings.displayZoomControls = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onRendered(success: Boolean, message: String) {
                                    post {
                                        renderReady = success
                                        renderError = message.takeIf { !success }
                                        if (!success) {
                                            Log.w("RichChatContent", "Math/diagram rendering failed: $message")
                                        }
                                    }
                                }
                            },
                            "SmartContentBridge"
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript("window.renderSmartContent()", null)
                            }
                        }
                        webView = this
                        loadedContentKey = contentKey
                        loadDataWithBaseURL(
                            "https://appassets.androidplatform.net/",
                            buildSmartContentHtml(latestSource, isDiagram, displayMode),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { view ->
                    if (webView !== view) webView = view
                    if (loadedContentKey != contentKey) {
                        loadedContentKey = contentKey
                        renderReady = false
                        renderError = null
                        view.evaluateJavascript(
                            "window.setSmartContent(${JSONObject.quote(source)}, $isDiagram, $displayMode)",
                            null
                        )
                    }
                }
            )
            if (!renderReady) {
                Text(
                    renderError ?: if (isDiagram) {
                        "يُحمّل عارض Mermaid؛ يلزم اتصال بالإنترنت للعرض الأول."
                    } else {
                        "يُحمّل عارض المعادلات؛ يلزم اتصال بالإنترنت للعرض الأول."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun buildSmartContentHtml(source: String, isDiagram: Boolean, displayMode: Boolean): String {
    val escapedSource = JSONObject.quote(source)
    val escapedDiagram = isDiagram.toString()
    val escapedDisplayMode = displayMode.toString()
    val libraryStyles = if (isDiagram) "" else
        """<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.css">"""
    val libraryScript = if (isDiagram) {
        """<script defer src="https://cdn.jsdelivr.net/npm/mermaid@11.6.0/dist/mermaid.min.js"></script>"""
    } else {
        """<script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/katex.min.js"></script>"""
    }
    return """
        <!doctype html>
        <html lang="ar">
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=4, user-scalable=yes">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'unsafe-inline' https://cdn.jsdelivr.net; font-src https://cdn.jsdelivr.net data:; img-src data: blob:; connect-src https://cdn.jsdelivr.net;">
          $libraryStyles
          <style>
            html, body { margin:0; width:100%; min-height:100%; background:transparent; color:#202124; overflow:auto; }
            body { font:16px sans-serif; }
            #formula { padding:12px 6px; overflow-x:auto; text-align:center; }
            #diagramViewport { width:100%; height:250px; overflow:hidden; touch-action:none; cursor:grab; }
            #diagramViewport.dragging { cursor:grabbing; }
            #diagram { transform-origin:0 0; width:max-content; min-width:100%; }
            #diagram svg { display:block; max-width:none; height:auto; }
            #error { color:#b3261e; white-space:pre-wrap; font-size:14px; }
          </style>
          $libraryScript
        </head>
        <body>
          <div id="formula"></div><div id="diagramViewport"><div id="diagram"></div></div><div id="error"></div>
          <script>
            let smartSource = $escapedSource;
            let smartDiagram = $escapedDiagram;
            let smartDisplayMode = $escapedDisplayMode;
            let scale = 1, offsetX = 0, offsetY = 0, pointerStart = null;
            const activePointers = new Map();
            let pinchStartDistance = 0, pinchStartScale = 1;
            function clampScale(value) { return Math.max(0.4, Math.min(4, value)); }
            function pointerDistance() {
              const points = Array.from(activePointers.values());
              if (points.length < 2) return 0;
              return Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y);
            }
            function applyTransform() {
              document.getElementById('diagram').style.transform =
                'translate(' + offsetX + 'px,' + offsetY + 'px) scale(' + scale + ')';
            }
            window.zoomDiagram = function(factor) {
              scale = clampScale(scale * factor);
              applyTransform();
            };
            window.resetDiagram = function() { scale = 1; offsetX = 0; offsetY = 0; applyTransform(); };
            const viewport = document.getElementById('diagramViewport');
            viewport.addEventListener('pointerdown', function(event) {
              activePointers.set(event.pointerId, {x:event.clientX, y:event.clientY});
              if (activePointers.size === 2) {
                pointerStart = null;
                pinchStartDistance = pointerDistance();
                pinchStartScale = scale;
                viewport.setPointerCapture(event.pointerId);
                return;
              }
              pointerStart = [event.clientX - offsetX, event.clientY - offsetY];
              viewport.classList.add('dragging');
              viewport.setPointerCapture(event.pointerId);
            });
            viewport.addEventListener('pointermove', function(event) {
              if (activePointers.has(event.pointerId)) {
                activePointers.set(event.pointerId, {x:event.clientX, y:event.clientY});
              }
              if (activePointers.size >= 2 && pinchStartDistance > 0) {
                scale = clampScale(pinchStartScale * pointerDistance() / pinchStartDistance);
                applyTransform();
                return;
              }
              if (!pointerStart) return;
              offsetX = event.clientX - pointerStart[0];
              offsetY = event.clientY - pointerStart[1];
              applyTransform();
            });
            function endPointer(event) {
              activePointers.delete(event.pointerId);
              pointerStart = null;
              pinchStartDistance = 0;
              if (activePointers.size === 1) {
                const point = Array.from(activePointers.values())[0];
                pointerStart = [point.x - offsetX, point.y - offsetY];
              } else if (activePointers.size === 0) {
                viewport.classList.remove('dragging');
              }
            }
            viewport.addEventListener('pointerup', endPointer);
            viewport.addEventListener('pointercancel', endPointer);
            viewport.addEventListener('lostpointercapture', endPointer);
            });
            viewport.addEventListener('wheel', function(event) {
              event.preventDefault();
              window.zoomDiagram(event.deltaY < 0 ? 1.1 : 0.9);
            }, {passive:false});
            window.setSmartContent = function(source, diagram, displayMode) {
              smartSource = source;
              smartDiagram = diagram;
              smartDisplayMode = displayMode;
              window.renderSmartContent();
            };
            window.renderSmartContent = async function() {
              const formula = document.getElementById('formula');
              const diagram = document.getElementById('diagram');
              const error = document.getElementById('error');
              error.textContent = '';
              formula.style.display = smartDiagram ? 'none' : 'block';
              viewport.style.display = smartDiagram ? 'block' : 'none';
              try {
                if (smartDiagram) {
                  if (!window.mermaid) throw new Error('تعذر تحميل مكتبة Mermaid.');
                  mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'neutral'});
                  const rendered = await mermaid.render('smart-diagram-' + Date.now(), smartSource);
                  diagram.innerHTML = rendered.svg;
                  window.resetDiagram();
                } else {
                  if (!window.katex) throw new Error('تعذر تحميل مكتبة KaTeX.');
                  katex.render(smartSource, formula, {
                    displayMode: smartDisplayMode,
                    throwOnError:false,
                    trust:false,
                    strict:'warn'
                  });
                }
                if (window.SmartContentBridge) window.SmartContentBridge.onRendered(true, '');
              } catch (exception) {
                error.textContent = exception.message || 'تعذر عرض المحتوى.';
                if (window.SmartContentBridge) window.SmartContentBridge.onRendered(false, error.textContent);
              }
            };
            window.addEventListener('load', window.renderSmartContent);
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun saveRenderedImage(context: android.content.Context, webView: WebView) {
    val width = webView.width.coerceAtLeast(1)
    val height = webView.height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        webView.draw(Canvas(bitmap))
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "smart_cat_$timestamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Smart Cat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذر إنشاء ملف الصورة.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "تعذر حفظ الصورة."
                }
            } ?: error("تعذر فتح ملف الصورة.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            Toast.makeText(context, "تم حفظ الصورة في مجلد الصور / Smart Cat.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    } catch (error: Exception) {
        Log.e("RichChatContent", "Failed to save rendered content", error)
        Toast.makeText(context, error.localizedMessage ?: "تعذر حفظ الصورة.", Toast.LENGTH_LONG).show()
    } finally {
        bitmap.recycle()
    }
}
