package org.pagel.ai.ui.tasks

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog

private const val HOME = "https://pagel.uz/today"
private const val HOST = "pagel.uz"
private val Paper = Color(0xFFF4FBF8)
private val Mint = Color(0xFF1C7E69)
private val Ink = Color(0xFF0B2A22)

/**
 * pagel.uz as the app's home (Boss 2026-10-27: "fast sign-in, session saved, not frozen, like a real mobile app").
 * One WebView lives for the whole app, so moving between pages never reloads it. The page marks itself "in-app"
 * from the user agent and hides web-only bits. Links to other sites open in the phone's browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TasksScreen(onOpenPhone: () -> Unit) {
    val context = LocalContext.current
    val signIn = remember { SavedSignIn(context) }
    var progress by remember { mutableFloatStateOf(0f) }
    var offline by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<HttpAuthHandler?>(null) }
    var askWrong by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var pickFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pickFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        pickFiles = null
    }

    val web = remember(generation) {
        // A saved password is tried once per page load; a second ask means it is wrong, so Boss is asked again.
        var triedSaved = false
        WebView(context).apply {
            WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            setBackgroundColor(android.graphics.Color.rgb(0xF4, 0xFB, 0xF8))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
                setSupportZoom(false)
                allowFileAccess = false
                mediaPlaybackRequiresUserGesture = true
                userAgentString = "$userAgentString PagelApp/${versionOf(context)}"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.scheme == "pagel-app") { onOpenPhone(); return true }
                    if (url.scheme in listOf("http", "https") && url.host?.removePrefix("www.") == HOST) return false
                    openOutside(context, url)
                    return true
                }
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                    val saved = signIn.get()
                    if (saved != null && !triedSaved) { triedSaved = true; handler.proceed(saved.first, saved.second); return }
                    askWrong = saved != null
                    asking = handler
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { offline = false }
                override fun onPageFinished(view: WebView, url: String) {
                    triedSaved = false
                    CookieManager.getInstance().flush()
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { canGoBack = view.canGoBack() }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) offline = true
                }
                // The page's renderer died (low memory): build a fresh WebView instead of crashing the app.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    generation++
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    pickFiles?.onReceiveValue(null)
                    pickFiles = callback
                    return try { picker.launch(params.createIntent()); true } catch (e: ActivityNotFoundException) { pickFiles = null; false }
                }
            }
            loadUrl(HOME)
        }
    }
    DisposableEffect(web) { onDispose { CookieManager.getInstance().flush() } }

    BackHandler(enabled = canGoBack) { web.goBack() }

    Box(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AndroidView(factory = { (web.parent as? ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(visible = progress < 1f, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Mint, trackColor = Color.Transparent)
        }
        if (offline) Offline(onRetry = { offline = false; web.reload() })
    }

    asking?.let { handler ->
        SignInDialog(
            wrong = askWrong,
            onSignIn = { user, pass -> signIn.save(user, pass); asking = null; handler.proceed(user, pass) },
            onCancel = { asking = null; handler.cancel(); offline = true },
        )
    }
}

private fun versionOf(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

private fun openOutside(context: Context, url: Uri) {
    val intent = if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, url)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun Offline(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Paper).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Can't reach pagel.uz", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Check the internet, then try again.", color = Ink.copy(alpha = .6f), fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Mint), shape = RoundedCornerShape(28.dp)) { Text("Try again") }
    }
}

@Composable
private fun SignInDialog(wrong: Boolean, onSignIn: (String, String) -> Unit, onCancel: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.background(Color.White, RoundedCornerShape(28.dp)).padding(24.dp)) {
            Text("Sign in to PAGEL", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (wrong) "That password did not work. Type it again." else "Once. The phone keeps it encrypted and signs you in from now on.",
                color = if (wrong) Color(0xFFB3261E) else Ink.copy(alpha = .6f), fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onSignIn(user.trim(), pass) }, enabled = user.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) { Text("Sign in") }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now", color = Ink.copy(alpha = .6f)) }
        }
    }
}
