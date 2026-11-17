package com.morfeythe.glp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class LyricsViewModel : ViewModel() {

    var urlInput by mutableStateOf("")
    var lyricsOutput by mutableStateOf("")
    var statusMessage by mutableStateOf("Парсинг напрямую с genius.com")
    var isLoading by mutableStateOf(false)

    var showWebView by mutableStateOf(false)
        private set

    var webViewUrl by mutableStateOf("")
        private set

    var isSearchMode by mutableStateOf(false)
        private set

    fun startSearchQuery() {
        val input = urlInput.trim()
        if (input.isBlank()) {
            statusMessage = "Введи название или строчку песни!"
            return
        }

        val searchQuery = java.net.URLEncoder.encode("$input site:genius.com", "UTF-8")
        webViewUrl = "https://html.duckduckgo.com/html/?q=$searchQuery"
        showWebView = true
    }

    fun closeWebView() {
        showWebView = false
    }

    fun onGeniusLinkSelected(url: String) {
        val isGenius = url.contains("genius.com", ignoreCase = true)
        val isNotDuckDuckGo = !url.contains("duckduckgo.com", ignoreCase = true)

        if (isGenius && isNotDuckDuckGo) {
            showWebView = false
            urlInput = url
            fetchLyricsDirectly(url)
        } else {
            statusMessage = "Это страница поиска, откройте саму песню!"
        }
    }

    fun fetchLyricsDirectly(targetUrl: String) {
        val input = targetUrl.trim()
        if (input.isBlank()) {
            statusMessage = "Введи ссылку на Genius!"
            return
        }

        viewModelScope.launch {
            isLoading = true
            statusMessage = "Загрузка текста..."
            lyricsOutput = ""

            val downloadResult = withContext(Dispatchers.IO) {
                try {
                    val doc = Jsoup.connect(input)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(10000)
                        .get()

                    var lyricsElements = doc.select("div[data-lyrics-container='true']")
                    if (lyricsElements.isEmpty()) {
                        lyricsElements = doc.select("div.lyrics")
                    }

                    if (lyricsElements.isEmpty()) {
                        throw Exception("Не удалось найти блоки с текстом на этой странице.")
                    }

                    val sb = StringBuilder()
                    for (element in lyricsElements) {
                        element.select("br").append("\\n")
                        val html = element.html()
                            .replace(Regex("(?i)<br[^>]*?>"), "\n")

                        val parsed = Jsoup.parse(html)
                        sb.append(parsed.text()).append("\n")
                    }

                    var rawText = sb.toString().replace("\\n", "\n")

                    val readMoreIndex = rawText.indexOf("Read More", ignoreCase = true)
                    if (readMoreIndex != -1) {
                        rawText = rawText.substring(readMoreIndex + "Read More".length)
                    } else {
                        val lyricsIndex = rawText.indexOf("Lyrics", ignoreCase = true)
                        if (lyricsIndex != -1) {
                            rawText = rawText.substring(lyricsIndex + "Lyrics".length)
                        } else {
                            val firstBracketIndex = rawText.indexOf("[")
                            if (firstBracketIndex != -1) {
                                rawText = rawText.substring(firstBracketIndex)
                            }
                        }
                    }

                    rawText.lineSequence()
                        .joinToString("\n") { it.trimStart() }
                        .replace(Regex("[ \t]+\n"), "\n")
                        .replace(Regex("\n\\s*\n\\s*\n+"), "\n\n")
                        .trim()
                } catch (e: Exception) {
                    null
                }
            }

            isLoading = false

            if (downloadResult != null) {
                lyricsOutput = downloadResult
                statusMessage = "Готово!"
            } else {
                statusMessage = "Ошибка: Не удалось загрузить текст"
            }
        }
    }

    fun copyToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Lyrics", lyricsOutput)
        clipboard.setPrimaryClip(clip)
        statusMessage = "Скопировано в буфер обмена!"
    }

    fun setMode(search: Boolean) {
        isSearchMode = search
        urlInput = ""
        statusMessage = if (search) "Режим поиска текста по названию" else "Парсинг напрямую с genius.com"
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: LyricsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            val darkColors = darkColorScheme(
                background = Color(0xFF1E1E1E),
                surface = Color(0xFF2B2B2B),
                primary = Color(0xFF9CDCFE),
                onBackground = Color(0xFFD4D4D4),
                onSurface = Color(0xFFD4D4D4)
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    val focusManager = LocalFocusManager.current
                    val contentScrollState = rememberScrollState()

                    // Измеряем высоту нижней панели кнопок и высоту клавиатуры в пикселях
                    var bottomButtonsHeightPx by remember { mutableIntStateOf(0) }
                    val imeBottomPx = WindowInsets.ime.getBottom(density)
                    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density)

                    // Вычисляем, насколько клавиатура поднялась ВЫШЕ нижней панели кнопок
                    val extraKeyboardOverlapPx = (imeBottomPx - navBarBottomPx - bottomButtonsHeightPx).coerceAtLeast(0)
                    val extraKeyboardPaddingDp = with(density) { extraKeyboardOverlapPx.toDp() }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                // Снимаем фокус с поля ввода при тапе в любое свободное место
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = {
                                        focusManager.clearFocus()
                                    })
                                },
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (viewModel.showWebView) {
                                // БРАУЗЕР: фиксированная шапка + браузер
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E1E))
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = "Genius Lyrics Parser",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "by @morfeythe",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.offset(y = (-4).dp)
                                    )
                                }

                                MiniBrowserEmbeddedView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .imePadding(),
                                    initialUrl = viewModel.webViewUrl,
                                    onGeniusSelected = { selectedUrl ->
                                        viewModel.onGeniusLinkSelected(selectedUrl)
                                    },
                                    onClose = {
                                        viewModel.closeWebView()
                                    }
                                )
                            } else {
                                // ГЛАВНОЕ МЕНЮ:
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .padding(bottom = extraKeyboardPaddingDp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = maxHeight)
                                            .verticalScroll(contentScrollState),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Шапка (скроллится вместе с контентом)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            Text(
                                                text = "Genius Lyrics Parser",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "by @morfeythe",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.offset(y = (-4).dp)
                                            )
                                        }

                                        // Карточка вывода текста (занимает всю свободную высоту экрана)
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .heightIn(min = 120.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = MaterialTheme.shapes.medium
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(16.dp),
                                                contentAlignment = if (viewModel.lyricsOutput.isEmpty()) Alignment.Center else Alignment.TopStart
                                            ) {
                                                if (viewModel.lyricsOutput.isEmpty()) {
                                                    Text(
                                                        text = "Здесь появится чистый\nтекст песни...",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = Color.Gray,
                                                        textAlign = TextAlign.Center
                                                    )
                                                } else {
                                                    SelectionContainer {
                                                        Text(
                                                            text = viewModel.lyricsOutput,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Статус
                                        Text(
                                            text = viewModel.statusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                        // Переключатель и поле ввода
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .background(Color(0xFF2A2A2A), RoundedCornerShape(28.dp))
                                                    .padding(3.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(24.dp))
                                                        .background(if (!viewModel.isSearchMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                        .clickable { viewModel.setMode(false) }
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "Genius",
                                                        color = if (!viewModel.isSearchMode) Color.Black else Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(24.dp))
                                                        .background(if (viewModel.isSearchMode) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                        .clickable { viewModel.setMode(true) }
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "DuckDuckGo + Genius",
                                                        color = if (viewModel.isSearchMode) Color.Black else Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))

                                            OutlinedTextField(
                                                value = viewModel.urlInput,
                                                onValueChange = { viewModel.urlInput = it },
                                                label = {
                                                    Text(if (viewModel.isSearchMode) "Название, строчка, исполнитель..." else "Вставь ссылку на genius.com")
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedBorderColor = Color.Gray,
                                                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedLabelColor = Color.Gray,
                                                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                                                )
                                            )
                                        }
                                    }
                                }

                                // КНОПКИ ВНИЗУ: зафиксированы внизу экрана
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { size ->
                                            bottomButtonsHeightPx = size.height
                                        }
                                        .padding(bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            if (viewModel.isSearchMode) {
                                                viewModel.startSearchQuery()
                                            } else {
                                                viewModel.fetchLyricsDirectly(viewModel.urlInput)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !viewModel.isLoading,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                viewModel.isLoading -> "Загрузка..."
                                                viewModel.isSearchMode -> "Искать текст"
                                                else -> "Получить текст"
                                            },
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.copyToClipboard(context)
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = viewModel.lyricsOutput.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = "Копировать",
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniBrowserEmbeddedView(
    modifier: Modifier = Modifier,
    initialUrl: String,
    onGeniusSelected: (String) -> Unit,
    onClose: () -> Unit
) {
    val webViewInstance = remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Инструкция: Перейдите по нужному вам тексту на genius, затем нажмите кнопку «Взять ссылку»",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                        .clickable { webViewInstance.value?.goBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "←",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(y = (-2).dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                        .clickable { webViewInstance.value?.goForward() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "→",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.offset(y = (-2).dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                        .clickable { webViewInstance.value?.reload() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "↻",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset(y = (-1).dp)
                    )
                }
            }

            Button(
                onClick = {
                    val activeUrl = webViewInstance.value?.url ?: initialUrl
                    onGeniusSelected(activeUrl)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Взять ссылку", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✕",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.offset(y = (-1).dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF121212),
            shape = MaterialTheme.shapes.medium
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewInstance.value = this
                        setBackgroundColor(0xFF121212.toInt())

                        setLayerType(View.LAYER_TYPE_HARDWARE, null)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }

                        webViewClient = WebViewClient()
                        loadUrl(initialUrl)
                    }
                }
            )
        }
    }
}