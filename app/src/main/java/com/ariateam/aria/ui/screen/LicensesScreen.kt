package com.ariateam.aria.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R
import com.ariateam.aria.licenses.LicenseHtmlDocument
import com.ariateam.aria.licenses.LicenseHtmlEntry
import com.ariateam.aria.licenses.LicenseHtmlTheme
import com.ariateam.aria.licenses.LicenseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val htmlTheme = remember(colorScheme) {
        LicenseHtmlTheme(
            background = colorScheme.background.toCssColor(),
            foreground = colorScheme.onBackground.toCssColor(),
            muted = colorScheme.onSurfaceVariant.toCssColor(),
            link = colorScheme.primary.toCssColor(),
        )
    }
    var html by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(context, htmlTheme) {
        html = null
        loadError = null
        try {
            html = withContext(Dispatchers.IO) {
                val entries = LicenseRepository.allLicenses.map { item ->
                    LicenseHtmlEntry(
                        item = item,
                        fullText = LicenseRepository.loadFullLicenseText(context, item),
                    )
                }
                val nativeNotice = LicenseRepository.allLicenses
                    .firstNotNullOfOrNull { it.thirdPartyNoticeAssetPath }
                    ?.let { path ->
                        try {
                            context.assets.open(path).bufferedReader().use { it.readText() }
                        } catch (e: IOException) {
                            "네이티브 고지 파일을 불러오지 못했습니다: ${e.message}"
                        }
                    }
                LicenseHtmlDocument.build(entries, nativeNotice, htmlTheme)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            loadError = e.message ?: "라이선스 정보를 불러오지 못했습니다."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("licensesScreenRoot")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIconButton(
                onClick = onBack,
                testTag = "licensesBackButton",
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.licenses_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
                    .testTag("licensesTitle"),
            )
        }

        when {
            html != null -> LicenseHtmlView(
                html = html!!,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            loadError != null -> Text(
                text = "라이선스 정보를 표시할 수 없습니다: $loadError",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("licensesLoading"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun LicenseHtmlView(
    html: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = openProjectLink(context, request.url)

            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.testTag("licensesWebView"),
        update = { view ->
            if (view.tag !== html) {
                view.tag = html
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
    )
}

private fun openProjectLink(context: Context, uri: Uri): Boolean {
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        return true
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "링크를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        true
    }
}

private fun androidx.compose.ui.graphics.Color.toCssColor(): String =
    "#%06X".format(toArgb() and 0xFFFFFF)
