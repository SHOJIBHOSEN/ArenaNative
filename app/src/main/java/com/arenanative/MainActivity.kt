package com.arenanative

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStream
import java.net.URLDecoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private var uploadCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var blobOutput: OutputStream? = null
    private var blobUri: Uri? = null
    private var blobDisplayName: String? = null
    private val pickFile = 4102
    private val arenaHost = "arena.ai"
    private val downloadFolder = "Download/ArenaNative"

    private fun isTrustedArena(): Boolean {
        val host = Uri.parse(web.url ?: "").host?.lowercase(Locale.ROOT) ?: return false
        return host == arenaHost || host.endsWith(".$arenaHost")
    }

    private inner class ArenaBridge {
        @JavascriptInterface
        fun requestDownload(url: String, name: String, mime: String) {
            runOnUiThread {
                if (!isTrustedArena()) return@runOnUiThread
                val safeName = cleanFileName(name)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download file?")
                    .setMessage("$safeName\n\nSave the original file to Download/ArenaNative?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Download") { _, _ ->
                        if (url.startsWith("blob:", true)) {
                            val js = "window.__arenaNativeFetchBlob && window.__arenaNativeFetchBlob(" +
                                "${JSONObject.quote(url)},${JSONObject.quote(safeName)},${JSONObject.quote(mime)})"
                            web.evaluateJavascript(js, null)
                        } else {
                            enqueueHttpDownload(url, safeName, mime)
                        }
                    }
                    .show()
            }
        }

        @JavascriptInterface
        @Synchronized
        fun beginBlob(name: String, mime: String) {
            if (!isTrustedArena()) return
            try {
                finishBlob(false)
                val safeName = cleanFileName(name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                blobUri = contentResolver.insert(collection, values)
                    ?: throw IllegalStateException("Could not create download file")
                blobOutput = contentResolver.openOutputStream(blobUri!!, "w")
                    ?: throw IllegalStateException("Could not open download file")
                blobDisplayName = safeName
            } catch (e: Exception) {
                finishBlob(false)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun writeBlobChunk(base64: String) {
            if (!isTrustedArena()) return
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                synchronized(this@MainActivity) { blobOutput?.write(bytes) }
            } catch (e: Exception) {
                runOnUiThread { finishBlob(false) }
            }
        }

        @JavascriptInterface
        fun finishBlobTransfer(success: Boolean) {
            if (!isTrustedArena()) return
            runOnUiThread { finishBlob(success) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 18, 18)
        window.navigationBarColor = Color.rgb(18, 18, 18)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.rgb(18, 18, 18))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION") val t = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") val b = insets.systemWindowInsetBottom
                top = t
                bottom = b
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }

        web = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3)))
        setContentView(root)
        root.requestApplyInsets()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION") forceDark = WebSettings.FORCE_DARK_ON
            }
            if (Build.VERSION.SDK_INT >= 33) setAlgorithmicDarkeningAllowed(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(ArenaBridge(), "ArenaNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (isTrustedArena()) installDownloadHook()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                uploadCallback?.onReceiveValue(null)
                uploadCallback = callback
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(android.content.Intent.createChooser(intent, "Choose files"), pickFile)
                    true
                } catch (_: Exception) {
                    uploadCallback = null
                    false
                }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() { leaveFullscreen() }
        }

        web.setDownloadListener(DownloadListener { url, userAgent, disposition, mime, _ ->
            if (isTrustedArena()) showDownloadConfirmation(url, android.webkit.URLUtil.guessFileName(url, disposition, mime), mime)
        })
        if (savedInstanceState == null) web.loadUrl("https://arena.ai")
        else web.restoreState(savedInstanceState)
    }

    private fun installDownloadHook() {
        val script = """
            (function() {
              if (window.__arenaNativeHookInstalled) return;
              window.__arenaNativeHookInstalled = true;
              window.__arenaNativeFetchBlob = async function(url, name, mime) {
                try {
                  const response = await fetch(url);
                  if (!response.ok) throw new Error('Could not read generated file');
                  const blob = await response.blob();
                  const type = mime || blob.type || 'application/octet-stream';
                  ArenaNative.beginBlob(name, type);
                  const reader = blob.stream().getReader();
                  while (true) {
                    const part = await reader.read();
                    if (part.done) break;
                    let binary = '';
                    const bytes = part.value;
                    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                    ArenaNative.writeBlobChunk(btoa(binary));
                  }
                  ArenaNative.finishBlobTransfer(true);
                } catch (e) {
                  ArenaNative.finishBlobTransfer(false);
                }
              };
              document.addEventListener('click', function(event) {
                const anchor = event.target && event.target.closest ? event.target.closest('a[download]') : null;
                if (!anchor) return;
                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !/^https?:/i.test(href)) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                const name = anchor.getAttribute('download') || 'Arena-download';
                const mime = anchor.dataset.mime || '';
                ArenaNative.requestDownload(href, name, mime);
              }, true);
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun showDownloadConfirmation(url: String, name: String, mime: String?) {
        if (!isTrustedArena()) return
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage("${cleanFileName(name)}\n\nSave to Download/ArenaNative?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ -> enqueueHttpDownload(url, cleanFileName(name), mime ?: "") }
            .show()
    }

    private fun enqueueHttpDownload(url: String, name: String, mime: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" && uri.scheme != "http") {
                Toast.makeText(this, "Unsupported download link", Toast.LENGTH_LONG).show()
                return
            }
            val request = DownloadManager.Request(uri)
                .setMimeType(mime)
                .addRequestHeader("User-Agent", web.settings.userAgentString)
                .setTitle(name)
                .setDescription("ArenaNative download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ArenaNative/$name")
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading to Download/ArenaNative", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized
    private fun finishBlob(success: Boolean) {
        try {
            blobOutput?.flush()
            blobOutput?.close()
            blobOutput = null
            val uri = blobUri
            if (uri != null) {
                if (success) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        contentResolver.update(uri, values, null, null)
                    }
                    Toast.makeText(this, "Saved to Download/ArenaNative", Toast.LENGTH_LONG).show()
                } else {
                    contentResolver.delete(uri, null, null)
                    Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not finish download: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            blobOutput = null
            blobUri = null
            blobDisplayName = null
        }
    }

    private fun cleanFileName(value: String): String {
        val decoded = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
        val safe = decoded.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_").trim().take(120)
        return safe.ifBlank { "Arena-download" }
    }

    @Deprecated("Deprecated by Android, retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile) {
            val result = if (resultCode == RESULT_OK) {
                data?.clipData?.let { clip -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri } }
                    ?: data?.data?.let { arrayOf(it) }
            } else null
            uploadCallback?.onReceiveValue(result)
            uploadCallback = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (fullscreenView != null) leaveFullscreen()
        else if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    private fun leaveFullscreen() {
        val view = fullscreenView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.rgb(18, 18, 18)
        window.navigationBarColor = Color.rgb(18, 18, 18)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        uploadCallback?.onReceiveValue(null)
        finishBlob(false)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
