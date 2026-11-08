package com.arenanative

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
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
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.JavaScriptReplyProxy
import java.util.concurrent.Executors
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
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
    private val legacyStoragePermissionRequest = 4103
    private val folderSetupPermissionRequest = 4104
    private data class PendingDownload(val url: String, val name: String, val mime: String)
    private var pendingDownload: PendingDownload? = null
    private val activeDownloadIds = mutableSetOf<Long>()
    private var downloadReceiverRegistered = false
    private var folderSetupView: View? = null
    private var lastFolderCheckError: String = ""
    private val bridgeExecutor = Executors.newSingleThreadExecutor()
    private var bridgeAvailable = false
    @Volatile private var confirmedMenuDownload = false
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (!activeDownloadIds.remove(completedId)) return
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(completedId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    val message = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        "Download complete: Download/ArenaNative"
                    } else {
                        "Download failed (status=$status, reason=$reason)"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Download failed: no result from DownloadManager", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val arenaHost = "arena.ai"
    @Volatile private var trustedArenaPage = false
    private val downloadFolder = "Download/ArenaNative"

    private fun isTrustedArena(): Boolean = trustedArenaPage

    private fun updateTrustedArenaPage(url: String?) {
        val host = Uri.parse(url ?: "").host?.lowercase(Locale.ROOT)
        trustedArenaPage = host == arenaHost || (host?.endsWith(".$arenaHost") == true)
    }

    private fun isAllowedArenaOrigin(origin: Uri): Boolean {
        val host = origin.host?.lowercase(Locale.ROOT) ?: return false
        return origin.scheme.equals("https", ignoreCase = true) &&
            (host == arenaHost || host.endsWith(".$arenaHost"))
    }

    private fun replyToBridge(reply: JavaScriptReplyProxy, id: String, ok: Boolean) {
        try {
            reply.postMessage(JSONObject().put("id", id).put("ok", ok).toString())
        } catch (_: Exception) {
            // The WebView may have navigated away or been destroyed during a transfer.
        }
    }

    private fun handleBridgeMessage(
        rawMessage: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy
    ) {
        if (!isMainFrame || !isAllowedArenaOrigin(sourceOrigin)) return
        // Bound bridge payloads so a page cannot force large temporary allocations.
        if (rawMessage.length > 100 * 1024) return
        val message = try { JSONObject(rawMessage) } catch (_: Exception) { return }
        val action = message.optString("action")
        val id = message.optString("id")
        when (action) {
            "requestDownload" -> {
                val url = message.optString("url")
                val name = cleanFileName(message.optString("name"))
                val mime = message.optString("mime")
                runOnUiThread {
                    if (confirmedMenuDownload) {
                        confirmedMenuDownload = false
                        if (url.isNotBlank()) startDownload(url, name, mime)
                        else Toast.makeText(this, "Download link was not provided by the page.", Toast.LENGTH_LONG).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Download file?")
                            .setMessage("$name\\n\\nSave the original file to Download/ArenaNative?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Download") { _, _ -> startDownload(url, name, mime) }
                            .show()
                    }
                }
            }
            "requestMenuDownload" -> {
                val name = cleanFileName(message.optString("name").ifBlank { "Arena-download" })
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Download file?")
                        .setMessage("$name\\n\\nContinue with the download from Arena.ai?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download") { _, _ ->
                            confirmedMenuDownload = true
                            web.evaluateJavascript("window.__arenaNativeReplayDownloadClick && window.__arenaNativeReplayDownloadClick()", null)
                        }
                        .show()
                }
            }
            "beginBlob" -> bridgeExecutor.execute {
                val ok = beginBlobFile(message.optString("name"), message.optString("mime"))
                replyToBridge(reply, id, ok)
            }
            "writeBlobChunk" -> bridgeExecutor.execute {
                val ok = try {
                    val encoded = message.optString("data")
                    if (encoded.length > 70 * 1024) throw IllegalArgumentException("Download chunk is too large")
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    if (bytes.size > 48 * 1024) throw IllegalArgumentException("Download chunk is too large")
                    synchronized(this) {
                        val output = blobOutput ?: throw IllegalStateException("Download output is not open")
                        output.write(bytes)
                    }
                    true
                } catch (_: Exception) { false }
                replyToBridge(reply, id, ok)
                if (!ok) runOnUiThread { finishBlob(false) }
            }
            "finishBlobTransfer" -> bridgeExecutor.execute {
                val success = message.optBoolean("success", false)
                runOnUiThread { finishBlob(success) }
                replyToBridge(reply, id, true)
            }
            "reportDownloadError" -> bridgeExecutor.execute {
                runOnUiThread { finishBlob(false) }
                replyToBridge(reply, id, true)
            }
        }
    }

    private fun beginBlobFile(name: String, mime: String): Boolean {
        return synchronized(this) {
            try {
                finishBlob(false)
                val safeName = cleanFileName(name)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, downloadFolder)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val directory = java.io.File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "ArenaNative"
                        )
                        if (!directory.exists() && !directory.mkdirs()) {
                            throw IllegalStateException("Could not create Downloads/ArenaNative")
                        }
                        @Suppress("DEPRECATION")
                        put(MediaStore.MediaColumns.DATA, java.io.File(directory, safeName).absolutePath)
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
                true
            } catch (e: Exception) {
                finishBlob(false)
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                false
            }
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
        web.visibility = View.GONE
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3)))
        setContentView(root)
        val downloadFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(downloadReceiver, downloadFilter)
        }
        downloadReceiverRegistered = true
        root.requestApplyInsets()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            // Respect the website's viewport metadata on phones, tablets and resizable windows.
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION") forceDark = WebSettings.FORCE_DARK_ON
            }
            if (Build.VERSION.SDK_INT >= 33) setAlgorithmicDarkeningAllowed(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // Keep third-party cookies enabled for compatibility with Arena.ai sign-in flows.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.startSafeBrowsing(applicationContext, null)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web,
                "ArenaNative",
                setOf("https://arena.ai", "https://*.arena.ai")
            ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                message.data?.let { data ->
                    handleBridgeMessage(data, sourceOrigin, isMainFrame, replyProxy)
                }
            }
            bridgeAvailable = true
        } else {
            bridgeAvailable = false
            Toast.makeText(this, "এই Android System WebView সংস্করণে নিরাপদ ডাউনলোড ব্রিজ সমর্থিত নয়। WebView আপডেট করুন।", Toast.LENGTH_LONG).show()
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) updateTrustedArenaPage(request.url.toString())
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                updateTrustedArenaPage(url)
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                updateTrustedArenaPage(url)
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
            if (isTrustedArena()) {
                val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                if (confirmedMenuDownload) {
                    confirmedMenuDownload = false
                    startDownload(url, name, mime ?: "")
                } else {
                    showDownloadConfirmation(url, name, mime)
                }
            }
        })
        showDuroodWelcome(root, savedInstanceState)
    }

    private fun showDuroodWelcome(root: FrameLayout, savedInstanceState: Bundle?) {
        // A scrollable, centered welcome panel prevents clipping on short screens
        // and keeps a comfortable reading width on tablets and desktop-sized windows.
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val heading = TextView(this).apply {
            text = "Arena.ai"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "অ্যাপ ব্যবহারের আগে প্রিয় নবী হজরত মুহাম্মদ (সা.)-এর ওপর ৩ বার দরুদ পাঠ করুন।"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, dp(18))
        }
        val arabic = TextView(this).apply {
            text = "اللَّهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ وَعَلَىٰ آلِ مُحَمَّدٍ"
            textSize = 24f
            setTextColor(Color.rgb(190, 180, 255))
            gravity = android.view.Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, dp(8), 0, dp(22))
        }
        val instruction = TextView(this).apply {
            text = "দরুদ পাঠ শেষ হলে নিচের ‘পরবর্তী’ বাটনে চাপ দিন।"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        val next = Button(this).apply {
            text = "পরবর্তী"
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener {
                root.removeView(scroll)
                // Recheck the real Downloads/ArenaNative destination on every app launch.
                showDownloadFolderSetup(root, savedInstanceState)
            }
        }
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2))
        panel.addView(message, LinearLayout.LayoutParams(-1, -2))
        panel.addView(arabic, LinearLayout.LayoutParams(-1, -2))
        panel.addView(instruction, LinearLayout.LayoutParams(-1, -2))
        panel.addView(next, LinearLayout.LayoutParams(-1, dp(52)))
        val screenWidth = resources.configuration.screenWidthDp
        val panelWidth = if (screenWidth > 0) minOf(dp(560), dp(screenWidth - 32).coerceAtLeast(dp(280))) else -1
        val contentFrame = FrameLayout(this)
        contentFrame.addView(panel, FrameLayout.LayoutParams(panelWidth, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        scroll.addView(contentFrame, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }


    private fun showDownloadFolderSetup(root: FrameLayout, savedInstanceState: Bundle?) {
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        folderSetupView = scroll
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val heading = TextView(this).apply {
            text = "ডাউনলোড ফোল্ডার সেটআপ"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        val message = TextView(this).apply {
            text = "ডাউনলোড করা ফাইল গুছিয়ে রাখার জন্য Download/ArenaNative ফোল্ডারটি পরীক্ষা করা হবে। ফোল্ডার না থাকলে তৈরি করা হবে। অ্যাপ চালুর সময় প্রতিবার ১ KB পরীক্ষামূলক ফাইল লিখে ও পড়ে যাচাই করা হবে।"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(20), 0, dp(24))
        }
        val status = TextView(this).apply {
            text = "ফোল্ডার প্রস্তুত করতে নিচের বাটনে চাপ দিন।"
            textSize = 14f
            setTextColor(Color.rgb(190, 180, 255))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        val setup = Button(this).apply {
            text = "ফোল্ডার চেক ও তৈরি করুন"
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener {
                status.text = "ফোল্ডার পরীক্ষা করা হচ্ছে..."
                if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        folderSetupPermissionRequest
                    )
                } else {
                    if (verifyDownloadFolderWrite()) {
                        getSharedPreferences("arena_native_setup", Context.MODE_PRIVATE)
                            .edit().putBoolean("download_folder_ready", true).apply()
                        Toast.makeText(this, "ফোল্ডার যাচাই সফল। ১ KB test file: Download/ArenaNative", Toast.LENGTH_LONG).show()
                        completeFolderSetup()
                        enterArena(savedInstanceState)
                    } else {
                        status.text = "ফোল্ডার পরীক্ষা ব্যর্থ: $lastFolderCheckError"
                    }
                }
            }
        }
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2))
        panel.addView(message, LinearLayout.LayoutParams(-1, -2))
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))
        panel.addView(setup, LinearLayout.LayoutParams(-1, dp(52)))
        val screenWidth = resources.configuration.screenWidthDp
        val panelWidth = if (screenWidth > 0) minOf(dp(560), dp(screenWidth - 32).coerceAtLeast(dp(280))) else -1
        val contentFrame = FrameLayout(this)
        contentFrame.addView(panel, FrameLayout.LayoutParams(panelWidth, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        scroll.addView(contentFrame, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun completeFolderSetup() {
        folderSetupView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        folderSetupView = null
    }

    private fun verifyDownloadFolderWrite(): Boolean {
        lastFolderCheckError = ""
        // Keep one visible 1 KB probe file so the user can verify the exact destination.
        val testName = "ArenaNative_folder_test_1KB.txt"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/ArenaNative/"
        val testBytes = ByteArray(1024) { index -> (index % 251).toByte() }
        var testUri: Uri? = null
        var testFile: java.io.File? = null
        var passed = false
        try {
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val folder = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ArenaNative"
                )
                if ((!folder.isDirectory && !folder.mkdirs()) || !folder.isDirectory) {
                    throw IllegalStateException("Download/ArenaNative ফোল্ডার তৈরি করা যায়নি")
                }
                val file = java.io.File(folder, testName)
                testFile = file
                java.io.FileOutputStream(file, false).use { it.write(testBytes) }
                val verified = file.inputStream().use { it.readBytes() }
                passed = verified.contentEquals(testBytes) && file.length() == 1024L
            } else {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // Remove only our previous probe file; never touch the user's other files.
                val existing = contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(testName, relativePath),
                    null
                )
                existing?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (cursor.moveToNext()) {
                        val oldUri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                        contentResolver.delete(oldUri, null, null)
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, testName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                testUri = contentResolver.insert(collection, values)
                    ?: throw IllegalStateException("Download/ArenaNative ফোল্ডারে পরীক্ষামূলক ফাইল তৈরি করা যায়নি")
                val uri = testUri!!
                contentResolver.openOutputStream(uri, "w")?.use { it.write(testBytes) }
                    ?: throw IllegalStateException("১ KB পরীক্ষামূলক ফাইলে লেখা যাচ্ছে না")
                val verified = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("১ KB পরীক্ষামূলক ফাইল পড়ে যাচাই করা যাচ্ছে না")
                passed = verified.contentEquals(testBytes) && verified.size == 1024
                if (passed) {
                    val ready = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    if (contentResolver.update(uri, ready, null, null) <= 0) {
                        throw IllegalStateException("পরীক্ষামূলক ফাইল Downloads-এ প্রকাশ করা যায়নি")
                    }
                    val check = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), null, null, null)
                        ?: throw IllegalStateException("ফোল্ডারের ফাইল তালিকা পড়া যাচ্ছে না")
                    check.use { cursor ->
                        if (!cursor.moveToFirst()) throw IllegalStateException("পরীক্ষার ফাইলটি ফোল্ডারে পাওয়া যায়নি")
                        val actualName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        val actualPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                        val actualSize = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                        val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
                        if (actualName != testName || actualPath != relativePath || actualSize != 1024L || pending != 0) {
                            throw IllegalStateException("ফোল্ডার/ফাইল যাচাই মেলেনি: path=$actualPath, size=$actualSize")
                        }
                    }
                }
            }
            if (!passed) throw IllegalStateException("১ KB ফাইলের লেখা-পড়া যাচাই মেলেনি")
            android.util.Log.i("ArenaNative", "Download folder verified; 1 KB probe saved at Download/ArenaNative/$testName")
            return true
        } catch (e: Exception) {
            android.util.Log.e("ArenaNative", "Download folder write test failed", e)
            return false
        } finally {
            if (!passed) {
                testFile?.let { file -> if (file.exists()) file.delete() }
                testUri?.let { uri ->
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun enterArena(savedInstanceState: Bundle?) {
        web.visibility = View.VISIBLE
        if (savedInstanceState == null) web.loadUrl("https://arena.ai")
        else web.restoreState(savedInstanceState)
    }

    private fun installDownloadHook() {
        if (!bridgeAvailable) return
        val script = """
            (function() {
              if (window.__arenaNativeHookInstalled || !window.ArenaNative) return;
              window.__arenaNativeHookInstalled = true;
              let nativeRequestId = 0;
              const nativePending = new Map();
              window.ArenaNative.onmessage = function(event) {
                try {
                  const response = JSON.parse(event.data);
                  const pending = nativePending.get(response.id);
                  if (!pending) return;
                  nativePending.delete(response.id);
                  pending(response.ok === true);
                } catch (_) {}
              };
              function nativeCall(action, fields) {
                return new Promise(resolve => {
                  const id = String(++nativeRequestId);
                  nativePending.set(id, resolve);
                  window.ArenaNative.postMessage(JSON.stringify(Object.assign({id:id, action:action}, fields || {})));
                  setTimeout(() => {
                    if (nativePending.has(id)) {
                      nativePending.delete(id);
                      resolve(false);
                    }
                  }, 15000);
                });
              }
              function nativeSend(action, fields) {
                window.ArenaNative.postMessage(JSON.stringify(Object.assign({action:action}, fields || {})));
              }
              window.__arenaNativeFetchBlob = async function(url, name, mime) {
                try {
                  const response = await fetch(url);
                  if (!response.ok) throw new Error('Could not read generated file');
                  const type = mime || response.headers.get('content-type') || 'application/octet-stream';
                  if (!response.body) throw new Error('Streaming is not supported for this download');
                  const ready = await nativeCall('beginBlob', {name:name, mime:type});
                  if (!ready) throw new Error('Could not create destination file');
                  const reader = response.body.getReader();
                  while (true) {
                    const part = await reader.read();
                    if (part.done) break;
                    const bytes = part.value;
                    for (let offset = 0; offset < bytes.length; offset += 48 * 1024) {
                      const slice = bytes.subarray(offset, Math.min(offset + 48 * 1024, bytes.length));
                      let binary = '';
                      for (let i = 0; i < slice.length; i++) binary += String.fromCharCode(slice[i]);
                      if (!await nativeCall('writeBlobChunk', {data:btoa(binary)})) throw new Error('Android could not write the file');
                    }
                  }
                  await nativeCall('finishBlobTransfer', {success:true});
                } catch (e) {
                  nativeSend('reportDownloadError');
                }
              };
              let pendingMenuDownloadElement = null;
              window.__arenaNativeReplayDownloadClick = function() {
                const element = pendingMenuDownloadElement;
                pendingMenuDownloadElement = null;
                if (!element || !document.contains(element)) return;
                window.__arenaNativeReplayingDownloadClick = true;
                try {
                  element.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window}));
                } finally {
                  window.__arenaNativeReplayingDownloadClick = false;
                }
              };
              function handleDownloadClick(event) {
                if (window.__arenaNativeReplayingDownloadClick) return;
                const target = event.target;
                if (!target) return;
                const anchor = target.closest ? target.closest('a') : null;
                const actionElement = target.closest ? target.closest('a,button,[role="menuitem"],[role="button"]') : null;
                const label = actionElement ? [
                  actionElement.innerText, actionElement.textContent,
                  actionElement.getAttribute('aria-label'),
                  actionElement.getAttribute('title')
                ].filter(Boolean).join(' ').trim() : '';
                const looksLikeDownloadAction = /\\bdownload(?:\\s+(?:file|image|video|audio))?\\b/i.test(label);
                if (looksLikeDownloadAction && actionElement) {
                  const href = actionElement.href || actionElement.getAttribute('href') || '';
                  const directFile = /^(https?:|blob:|data:)/i.test(href);
                  if (!directFile || !anchor) {
                    event.preventDefault();
                    event.stopPropagation();
                    pendingMenuDownloadElement = actionElement;
                    nativeSend('requestMenuDownload', {
                      name: actionElement.getAttribute('download') ||
                        actionElement.getAttribute('aria-label') || 'Arena-download'
                    });
                    return;
                  }
                }
                if (!anchor) return;
                const href = anchor.href || anchor.getAttribute('href') || '';
                const downloadName = anchor.getAttribute('download') || anchor.download || '';
                const isBlobOrData = /^(blob:|data:)/i.test(href);
                const isFileUrl = /^https?:/i.test(href) &&
                  (/\\.(pdf|zip|rar|7z|docx?|xlsx?|pptx?|csv|txt|png|jpe?g|webp|mp[34]|json)(?:$|[?#])/i.test(href) ||
                   /(?:download|export|attachment|file)=?/i.test(href));
                if (!downloadName && !isBlobOrData && !isFileUrl) return;
                if (!isBlobOrData && !isFileUrl && !/^https?:/i.test(href)) return;
                event.preventDefault();
                const name = downloadName || anchor.getAttribute('aria-label') || 'Arena-download';
                const mime = anchor.dataset ? (anchor.dataset.mime || '') : '';
                nativeSend('requestDownload', {url:href, name:name, mime:mime});
              }
              document.addEventListener('click', handleDownloadClick, true);
              document.addEventListener('auxclick', handleDownloadClick, true);
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
            .setPositiveButton("Download") { _, _ -> startDownload(url, cleanFileName(name), mime ?: "") }
            .show()
    }

    private fun startDownload(url: String, name: String, mime: String) {
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https", "blob", "data")) {
            Toast.makeText(this, "Unsupported download link type: ${scheme ?: "unknown"}", Toast.LENGTH_LONG).show()
            return
        }

        // Android 6–9 require WRITE_EXTERNAL_STORAGE for public Downloads.
        // Android 10+ use scoped storage / MediaStore and must not request it.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = PendingDownload(url, name, mime)
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                legacyStoragePermissionRequest
            )
            return
        }
        continueDownload(url, name, mime)
    }

    private fun continueDownload(url: String, name: String, mime: String) {
        when (Uri.parse(url).scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> enqueueHttpDownload(url, name, mime)
            "blob", "data" -> {
                // These URLs are scoped to this WebView; they must be saved by the native bridge.
                val js = "if (window.__arenaNativeFetchBlob) {" +
                    "window.__arenaNativeFetchBlob(${JSONObject.quote(url)},${JSONObject.quote(name)},${JSONObject.quote(mime)});" +
                    "} else if (window.ArenaNative && typeof window.ArenaNative.postMessage === 'function') {" +
                    "window.ArenaNative.postMessage(JSON.stringify({action:'reportDownloadError'}));" +
                    "} else { console.error('ArenaNative download bridge is not ready'); }"
                web.evaluateJavascript(js, null)
            }
            else -> Toast.makeText(this, "Unsupported download link type", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInExternalBrowser(url: String) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") {
                Toast.makeText(this, "Only web links can open in the browser", Toast.LENGTH_LONG).show()
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
            Toast.makeText(this, "Opened download link in your browser", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == folderSetupPermissionRequest) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                verifyDownloadFolderWrite()) {
                getSharedPreferences("arena_native_setup", Context.MODE_PRIVATE)
                    .edit().putBoolean("download_folder_ready", true).apply()
                completeFolderSetup()
                enterArena(null)
            } else {
                Toast.makeText(this, "ফোল্ডার পরীক্ষা ব্যর্থ: $lastFolderCheckError", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode != legacyStoragePermissionRequest) return
        val pending = pendingDownload
        pendingDownload = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && pending != null) {
            continueDownload(pending.url, pending.name, pending.mime)
        } else {
            Toast.makeText(this, "Storage permission is required to save downloads on Android 9 and older.", Toast.LENGTH_LONG).show()
        }
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
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val isArenaHost = host == arenaHost || host.endsWith(".$arenaHost")
            // Never forward Arena.ai cookies to a third-party download host.
            val cookie = if (isArenaHost) CookieManager.getInstance().getCookie(url) else null
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            val downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            activeDownloadIds.add(downloadId)
            Toast.makeText(this, "Download started. Check notification for progress.", Toast.LENGTH_LONG).show()
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
                    runOnUiThread { Toast.makeText(this, "Saved to Download/ArenaNative", Toast.LENGTH_LONG).show() }
                } else {
                    contentResolver.delete(uri, null, null)
                    runOnUiThread { Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show() }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Could not finish download: ${e.message}", Toast.LENGTH_LONG).show() }
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
        bridgeExecutor.shutdown()
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) { }
            downloadReceiverRegistered = false
        }
        super.onDestroy()
    }
}
