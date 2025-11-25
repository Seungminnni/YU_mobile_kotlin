package com.example.a1

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.Surface
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.example.a1.BuildConfig
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var webView: WebView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var openGalleryButton: ImageButton
    private lateinit var cameraControls: View
    private lateinit var cameraHintText: TextView
    private lateinit var urlSuggestionCard: View
    private lateinit var urlPreviewText: TextView
    private lateinit var openUrlButton: Button
    private lateinit var dismissUrlButton: ImageButton
    private lateinit var sandboxInfoPanel: View
    private lateinit var exitSandboxButton: Button

    private var currentUrl: String? = null
    // dynamic, runtime counters to capture actual WebView redirect behaviour
    private var dynamicTotalRedirects: Int = 0
    private var dynamicExternalRedirects: Int = 0
    // dynamic error counters to capture resource/http/js/runtime errors
    private var dynamicTotalErrors: Int = 0
    private var dynamicExternalErrors: Int = 0
    private var lastNavigationUrlForDynamicCounters: String? = null
    private var pendingDetectedUrl: String? = null
    private var lastDisplayedUrl: String? = null
    private var imageCapture: ImageCapture? = null
    private var isWebViewVisible = false
    private var lastAnalyzedPageKey: String? = null
    private var isAnalyzingFeatures = false
    private var lastWarningShownForUrl: String? = null
    private lateinit var phishingDetector: PhishingDetector

    private val requiredPermissions: Array<String> by lazy {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list.toTypedArray()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = requiredPermissions.all { perm ->
            permissions[perm] == true || ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한과 저장소 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        previewView = findViewById(R.id.previewView)
        resultTextView = findViewById(R.id.resultTextView)
        webView = findViewById(R.id.webView)
        captureButton = findViewById(R.id.captureButton)
        openGalleryButton = findViewById(R.id.openGalleryButton)
        cameraControls = findViewById(R.id.cameraControls)
        cameraHintText = findViewById(R.id.cameraHintText)
        urlSuggestionCard = findViewById(R.id.urlSuggestionCard)
        urlPreviewText = findViewById(R.id.urlPreviewText)
        openUrlButton = findViewById(R.id.openUrlButton)
        dismissUrlButton = findViewById(R.id.dismissUrlButton)
        sandboxInfoPanel = findViewById(R.id.sandboxInfoPanel)
        exitSandboxButton = findViewById(R.id.exitSandboxButton)

        setupWebView()

        // 피싱 탐지 모듈 초기화
        phishingDetector = PhishingDetector(this)

        captureButton.setOnClickListener { takePhoto() }
        openGalleryButton.setOnClickListener { openDefaultGallery() }
        openUrlButton.setOnClickListener { pendingDetectedUrl?.let { url -> launchSandbox(url) } }
        dismissUrlButton.setOnClickListener { clearPendingUrl() }
        exitSandboxButton.setOnClickListener { returnToCameraView() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ML Kit 바코드 스캐너 초기화
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 카메라 권한 확인 및 요청
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
        maybeLaunchDebugUrl()

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isWebViewVisible -> returnToCameraView()
                    urlSuggestionCard.visibility == View.VISIBLE -> clearPendingUrl()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun setupWebView() {
        // 가상환경 보안 설정 - 기본적으로 제한적
        webView.settings.javaScriptEnabled = false  // 기본적으로 JavaScript 비활성화
        with(webView.settings) {
            javaScriptEnabled = false  // 기본적으로 JavaScript 비활성화
            domStorageEnabled = false   // DOM 스토리지 비활성화
            databaseEnabled = false     // 데이터베이스 비활성화
            cacheMode = WebSettings.LOAD_NO_CACHE  // 캐시 비활성화
            setGeolocationEnabled(false)  // 위치 정보 비활성화
            allowFileAccess = false      // 파일 시스템 접근 비활성화
            allowContentAccess = false   // 콘텐츠 접근 비활성화
            allowFileAccessFromFileURLs = false  // 파일 URL 접근 비활성화
            allowUniversalAccessFromFileURLs = false  // 범용 파일 URL 접근 비활성화
            setSupportMultipleWindows(false)  // 다중 창 지원 비활성화
            setSupportZoom(true)         // 줌만 허용
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        WebView.setWebContentsDebuggingEnabled(false)

        // JavaScript 인터페이스 추가 (피처 추출용)
        webView.addJavascriptInterface(WebFeatureExtractor { features ->
            runOnUiThread {
                analyzeAndDisplayPhishingResult(features)
            }
        }, "Android")

        // WebViewClient 설정 - 가상환경 내에서만 동작하도록 제한
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                resultTextView.text = "가상환경에서 웹페이지를 로드하는 중...\n⚠️ 이 페이지는 격리된 환경에서 실행됩니다"

                // --- dynamic redirect counting ---
                try {
                    if (!url.isNullOrBlank()) {
                        val prev = lastNavigationUrlForDynamicCounters
                        if (prev != null && prev != url) {
                            dynamicTotalRedirects += 1
                            val prevHost = runCatching { URI(prev).host }.getOrNull()?.lowercase(Locale.ROOT)
                            val curHost = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT)
                            if (!prevHost.isNullOrBlank() && !curHost.isNullOrBlank() && prevHost != curHost) {
                                dynamicExternalRedirects += 1
                            }
                        }
                        lastNavigationUrlForDynamicCounters = url
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "dynamic-redirect-counter error", e)
                }
                // reset per-navigation errors as we start a new page
                try {
                    dynamicTotalErrors = 0
                    dynamicExternalErrors = 0
                } catch (e: Exception) {
                    Log.d(TAG, "dynamic-error-counter reset failed", e)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) {
                    currentUrl = url
                }

                // 피처 추출 실행 (JavaScript 활성화된 경우에만)
                if (webView.settings.javaScriptEnabled && url != null && shouldAnalyzeUrl(url)) {
                    resultTextView.text = "🔍 가상환경에서 피처 분석 중..."
                    extractWebFeatures()
                } else if (!webView.settings.javaScriptEnabled) {
                    resultTextView.text = "🔒 보안 모드: 피처 분석을 위해 JavaScript가 필요합니다"
                }
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                try {
                    dynamicTotalErrors += 1
                    val reqUrl = request?.url?.toString()
                    val reqHost = runCatching { reqUrl?.let { URI(it).host } }.getOrNull()?.lowercase(Locale.ROOT)
                    val curHost = runCatching { currentUrl?.let { URI(it).host } }.getOrNull()?.lowercase(Locale.ROOT)
                    if (!reqHost.isNullOrBlank() && !curHost.isNullOrBlank() && reqHost != curHost) {
                        dynamicExternalErrors += 1
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "onReceivedError counter failed", e)
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                try {
                    dynamicTotalErrors += 1
                    val reqUrl = request?.url?.toString()
                    val reqHost = runCatching { reqUrl?.let { URI(it).host } }.getOrNull()?.lowercase(Locale.ROOT)
                    val curHost = runCatching { currentUrl?.let { URI(it).host } }.getOrNull()?.lowercase(Locale.ROOT)
                    if (!reqHost.isNullOrBlank() && !curHost.isNullOrBlank() && reqHost != curHost) {
                        dynamicExternalErrors += 1
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "onReceivedHttpError counter failed", e)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // 가상환경 내에서만 URL 로딩 허용
                if (url != null && isValidUrl(url)) {
                    return false  // WebView에서 직접 처리
                }
                Toast.makeText(this@MainActivity, "가상환경에서 허용되지 않는 URL입니다", Toast.LENGTH_SHORT).show()
                return true  // 차단
            }
        }

        // WebChromeClient 설정 - 팝업 및 다이얼로그 제한
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                Toast.makeText(this@MainActivity, "가상환경에서 JavaScript 알림이 차단되었습니다", Toast.LENGTH_SHORT).show()
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                Toast.makeText(this@MainActivity, "가상환경에서 JavaScript 확인이 차단되었습니다", Toast.LENGTH_SHORT).show()
                result?.cancel()
                return true
            }
        }
    }

    private fun launchSandbox(url: String) {
        pendingDetectedUrl = null
        isWebViewVisible = true
        currentUrl = url
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
        urlSuggestionCard.visibility = View.GONE
        cameraControls.visibility = View.GONE
        cameraHintText.visibility = View.GONE
        previewView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        sandboxInfoPanel.visibility = View.VISIBLE

        // reset dynamic counters for this session so we accurately capture redirects/errors
        dynamicTotalRedirects = 0
        dynamicExternalRedirects = 0
        lastNavigationUrlForDynamicCounters = null

        enableSandboxScripts()
        resultTextView.text = "⚠️ JavaScript가 활성화된 가상환경에서 로드 중..."
        webView.loadUrl(url)
    }

    private fun returnToCameraView() {
        if (!isWebViewVisible) {
            return
        }
        isWebViewVisible = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
        disableSandboxScripts()
        previewView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        sandboxInfoPanel.visibility = View.GONE
        cameraControls.visibility = View.VISIBLE
        cameraHintText.visibility = View.VISIBLE
        clearPendingUrl(true)
        lastAnalyzedPageKey = null
        isAnalyzingFeatures = false
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                imageCapture = capture

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, BarcodeAnalyzer())
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, capture, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "카메라 시작 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            if (pendingDetectedUrl != null || isWebViewVisible) {
                imageProxy.close()
                return
            }
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (pendingDetectedUrl != null || isWebViewVisible) return@addOnSuccessListener
                        for (barcode in barcodes) {
                            val rawValue = barcode.rawValue
                            if (rawValue != null && isValidUrl(rawValue)) {
                                if (rawValue != lastDisplayedUrl) {
                                    runOnUiThread {
                                        currentUrl = rawValue
                                        showUrlSuggestion(rawValue)
                                    }
                                }
                            } else if (!rawValue.isNullOrBlank()) {
                                runOnUiThread {
                                    cameraHintText.text = "📄 QR 코드 내용: $rawValue"
                                }
                            }
                        }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "바코드 스캔 실패", it)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    private fun takePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "카메라 초기화 중입니다", Toast.LENGTH_SHORT).show()
            return
        }

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "QR_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YUQR")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    cameraHintText.text = "사진이 갤러리에 저장되었습니다"
                    Toast.makeText(this@MainActivity, "갤러리에 저장 완료", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "사진 저장 실패", exception)
                    Toast.makeText(this@MainActivity, "사진 저장 실패: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun openDefaultGallery() {
        val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "갤러리를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showUrlSuggestion(url: String) {
        pendingDetectedUrl = url
        lastDisplayedUrl = url
        urlPreviewText.text = formatUrlPreview(url)
        urlSuggestionCard.visibility = View.VISIBLE
        cameraHintText.text = "감지된 URL을 분석하려면 \'가상분석\'을 누르세요"
    }

    private fun clearPendingUrl(allowSameUrlAgain: Boolean = false) {
        pendingDetectedUrl = null
        urlSuggestionCard.visibility = View.GONE
        if (allowSameUrlAgain) {
            lastDisplayedUrl = null
        }
        if (!isWebViewVisible) {
            cameraHintText.text = DEFAULT_CAMERA_HINT
        }
    }

    private fun enableSandboxScripts() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
    }

    private fun disableSandboxScripts() {
        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
    }

    private fun formatUrlPreview(url: String): String {
        return if (url.length <= 60) url else "${url.take(57)}..."
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun extractWebFeatures() {
        isAnalyzingFeatures = true
        val extractor = WebFeatureExtractor { features ->
            runOnUiThread {
                analyzeAndDisplayPhishingResult(features)
            }
        }
        webView.evaluateJavascript(extractor.getFeatureExtractionScript(), null)
    }

    private fun analyzeAndDisplayPhishingResult(features: WebFeatures) {
        // Merge dynamic runtime redirect counters into the feature map so ML sees real behaviour
        val merged = features.toMutableMap()
        try {
            // override counts that JS might set or leave null
            merged["nb_redirection"] = dynamicTotalRedirects.toFloat()
            merged["nb_external_redirection"] = dynamicExternalRedirects.toFloat()
            if (dynamicTotalRedirects == 0) {
                merged["ratio_intRedirection"] = 0f
                merged["ratio_extRedirection"] = 0f
            } else {
                val internal = (dynamicTotalRedirects - dynamicExternalRedirects)
                merged["ratio_intRedirection"] = (internal.toFloat() / dynamicTotalRedirects.toFloat())
                merged["ratio_extRedirection"] = (dynamicExternalRedirects.toFloat() / dynamicTotalRedirects.toFloat())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to merge dynamic redirect counters", e)
        }

        Log.d(TAG, "dynamic redirects total=$dynamicTotalRedirects external=$dynamicExternalRedirects | errors total=$dynamicTotalErrors external=$dynamicExternalErrors")

            // merge dynamic error counters as well (overwrite any JS-provided values)
            try {
                merged["nb_errors"] = dynamicTotalErrors.toFloat()
                merged["nb_external_errors"] = dynamicExternalErrors.toFloat()
                if (dynamicTotalErrors == 0) {
                    merged["ratio_intErrors"] = 0f
                    merged["ratio_extErrors"] = 0f
                } else {
                    val internalErrors = (dynamicTotalErrors - dynamicExternalErrors)
                    merged["ratio_intErrors"] = internalErrors.toFloat() / dynamicTotalErrors.toFloat()
                    merged["ratio_extErrors"] = dynamicExternalErrors.toFloat() / dynamicTotalErrors.toFloat()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to merge dynamic error counters", e)
            }

            val analysisResult = phishingDetector.analyzePhishing(merged, currentUrl)
        isAnalyzingFeatures = false
        lastAnalyzedPageKey = analysisResult.inspectedUrl ?: currentUrl
        renderAnalysis(analysisResult)
    }

    private fun renderAnalysis(analysisResult: PhishingAnalysisResult, allowModal: Boolean = true) {
        val modeDescription = "ML 기반 통합 분석"
        val targetUrl = analysisResult.inspectedUrl ?: currentUrl

        val resultText = StringBuilder().apply {
            append("🤖 ML 기반 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("📊 신뢰도 점수: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n")
            append("🎯 판정 결과: ${if (analysisResult.isPhishing) "🚨 피싱 의심" else "✅ 안전"}\n")
            append("🧪 분석 모드: $modeDescription\n")
            targetUrl?.let {
                append("🌐 분석 URL: $it\n")
            }

            val features = analysisResult.features
            if (features != null) {
                append("\n📋 WebView 피처 분석:\n")
                append("• DOM 노드 수: ${features["domNodeCount"]?.toInt() ?: 0}\n")
                append("• iframe 개수: ${features["iframeCount"]?.toInt() ?: 0}\n")
                append("• 외부 도메인 form: ${features["externalDomainFormCount"]?.toInt() ?: 0}\n")
                append("• base64 스크립트: ${features["base64ScriptCount"]?.toInt() ?: 0}\n")
                append("• 이벤트 리스너: ${features["eventListenerCount"]?.toInt() ?: 0}\n")
                append("• 의심스러운 스크립트: ${features["suspiciousScriptCount"]?.toInt() ?: 0}\n")
                append("• 로그인 폼: ${if (features["hasLoginForm"] == 1.0f) "있음" else "없음"}\n")
                append("• 신용카드 폼: ${if (features["hasCreditCardForm"] == 1.0f) "있음" else "없음"}\n")
                append("• URL 길이: ${features["urlLength"]?.toInt() ?: 0}\n")
                append("• 특수문자 수: ${features["specialCharCount"]?.toInt() ?: 0}\n")
            }

            if (analysisResult.riskFactors.isNotEmpty()) {
                append("\n⚠️ ML 분석 결과:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
            }

            append("\n💡 시스템 특징:\n")
            append("• 온-디바이스 ML 모델 사용\n")
            append("• 외부 서버 통신 없음\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 프라이버시 보호\n")

            append("\n💡 권장사항:\n")
            if (analysisResult.isPhishing) {
                append("• 이 사이트를 신뢰하지 마세요\n")
                append("• 개인정보를 입력하지 마세요\n")
                append("• 즉시 페이지를 닫으세요")
            } else {
                append("• 안전한 사이트로 보입니다\n")
                append("• 그래도 주의해서 사용하세요")
            }
        }

        resultTextView.text = resultText.toString()

        if (allowModal) {
            val warningKey = targetUrl ?: NO_URL_WARNING_KEY
            if (analysisResult.isPhishing) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                if (lastWarningShownForUrl != warningKey) {
                    lastWarningShownForUrl = warningKey
                    showPhishingWarningDialog(analysisResult)
                }
            } else if (lastWarningShownForUrl == warningKey) {
                lastWarningShownForUrl = null
            }
        }
    }

    private fun showPhishingWarningDialog(analysisResult: PhishingAnalysisResult) {
        val messageBuilder = StringBuilder().apply {
            append("🚨 ML 모델이 이 웹페이지를 피싱으로 분석했습니다!\n\n")
            append("📊 ML 신뢰도: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n\n")
            append("🤖 분석 방식:\n")
            append("• 온-디바이스 머신러닝 모델\n")
            append("• WebView 기반 행위 분석\n")
            append("• 실시간 피처 추출 및 판정\n\n")
            if (analysisResult.riskFactors.isNotEmpty()) {
                append("⚠️ ML 분석 근거:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
                append("\n")
            }
            append("🔒 보안 권장사항:\n")
            append("• 이 사이트에서 어떠한 정보도 입력하지 마세요\n")
            append("• 개인정보, 비밀번호, 신용카드 정보를 절대 입력하지 마세요\n")
            append("• 의심스러운 링크는 클릭하지 마세요\n")
            append("• 즉시 이 페이지를 닫으세요\n\n")
            append("연결은 차단됐으며 카메라 화면으로 돌아갑니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("🚨 ML 기반 피싱 경고!")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("확인") { _, _ ->
                returnToCameraView()
            }
            .setCancelable(false)
            .show()
    }

    private fun isValidUrl(url: String): Boolean {
        return Patterns.WEB_URL.matcher(url).matches() ||
               url.startsWith("http://") ||
               url.startsWith("https://")
    }

    private fun shouldAnalyzeUrl(url: String): Boolean {
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) {
            return false
        }
        if (isAnalyzingFeatures) {
            return false
        }
        if (lastAnalyzedPageKey != null && lastAnalyzedPageKey == url) {
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val NO_URL_WARNING_KEY = "__NO_URL__"
        private const val DEFAULT_CAMERA_HINT = "QR을 비추면 위협 URL이 여기에 나타납니다"
        // 디버그용으로 자동 분석할 URL (예: "https://phish.example.com"), 주석 해제 후 값 입력
        private const val DEBUG_AUTO_LAUNCH_URL = "https://www.velocidrone.com/"
    }

    private fun maybeLaunchDebugUrl() {
        if (!BuildConfig.DEBUG) return
        if (DEBUG_AUTO_LAUNCH_URL.isBlank()) return
        previewView.post {
            val url = DEBUG_AUTO_LAUNCH_URL.trim()
            cameraHintText.text = "디버그 URL 자동 분석 중..."
            currentUrl = url
            showUrlSuggestion(url)
            launchSandbox(url)
        }
    }
}

// 논문에서 제안하는 피처 추출을 위한 JavaScript 인터페이스
class WebFeatureExtractor(private val callback: (WebFeatures) -> Unit) {

    @JavascriptInterface
    fun receiveFeatures(featuresJson: String) {
        try {
            // Raw JSON from WebView — log it for debugging so you can inspect exactly
            // what values the JS extracted (including nulls or strings).
            Log.d("WebFeatureExtractor", "RAW_FEATURES_JSON: $featuresJson")

            val jsonObject = JSONObject(featuresJson)
            val features = mutableMapOf<String, Float?>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // If JS explicitly put null, treat as Kotlin null
                if (jsonObject.isNull(key)) {
                    features[key] = null
                    continue
                }

                val value = jsonObject.get(key)
                features[key] = when (value) {
                    is Number -> value.toFloat()
                    is Boolean -> if (value) 1.0f else 0.0f
                    is String -> {
                        val s = value.trim()
                        s.toFloatOrNull()?.also {
                            Log.d("WebFeatureExtractor", "Parsed numeric-string for $key: $s")
                        } ?: run {
                            Log.d("WebFeatureExtractor", "Non-numeric value for $key: '$s'")
                            null
                        }
                    }
                    else -> {
                        Log.d("WebFeatureExtractor", "Unexpected type for $key: ${value?.javaClass?.name}")
                        null
                    }
                }
            }

            // Log summary to quickly see how many nulls vs present values
            val presentCount = features.count { it.value != null }
            val nullCount = features.count { it.value == null }
            Log.d("WebFeatureExtractor", "Parsed features: total=${features.size}, present=$presentCount, null=$nullCount")
            callback(features)
        } catch (e: Exception) {
            Log.e("WebFeatureExtractor", "피처 파싱 실패", e)
        }
    }
// 
    fun getFeatureExtractionScript(): String {
        return """
            javascript:(function() {
                try {
                    function normalizeUrl(raw) {
                        try {
                            return new URL(raw, window.location.href);
                        } catch (e) {
                            return null;
                        }
                    }

                    var url = window.location.href;
                    var hostLower = window.location.hostname.toLowerCase();
                    var pathLower = window.location.pathname.toLowerCase();
                    var hostParts = hostLower.split('.');
                    var subdomainPart = hostParts.length > 2 ? hostParts.slice(0, hostParts.length - 2).join('.') : '';
                    var domainLabel = hostParts.length > 1 ? hostParts[hostParts.length - 2] : hostLower;
                    var tld = hostParts.length > 0 ? hostParts[hostParts.length - 1] : '';
                    
                    // URL 전체에서 단어 추출 (Python 로직과 동일하게 프로토콜 제외 및 분리 문자 지정)
                    // Python split: "-.|/?=@&%:_"
                    var splitRegex = /[\-\.\/\?\=\@\&\%\:\_]/;
                    var urlForWords = window.location.hostname + window.location.pathname + window.location.search;
                    var urlWords = urlForWords.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    var hostWords = window.location.hostname.split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    var pathWords = (window.location.pathname + window.location.search).split(splitRegex).filter(function(w){ return w && w.length > 0; });
                    
                    var features = {};

                    // ===== URL 기반 피처 (url_features.py 기준) =====
                    
                    // length_url
                    features.length_url = url.length;
                    
                    // length_hostname
                    features.length_hostname = window.location.hostname.length;
                    
                    // ip: IP 주소 형태인지 확인 (IPv4)
                    features.ip = /^(\d{1,3}\.){3}\d{1,3}$/.test(window.location.hostname) ? 1 : 0;
                    
                    // nb_dots
                    features.nb_dots = (url.match(/\./g) || []).length;
                    
                    // nb_hyphens
                    features.nb_hyphens = (url.match(/-/g) || []).length;
                    
                    // nb_at
                    features.nb_at = (url.match(/@/g) || []).length;
                    
                    // nb_qm (물음표)
                    features.nb_qm = (url.match(/\?/g) || []).length;
                    
                    // nb_and
                    features.nb_and = (url.match(/&/g) || []).length;
                    
                    // nb_or
                    features.nb_or = (url.match(/\|/g) || []).length;
                    
                    // nb_eq
                    features.nb_eq = (url.match(/=/g) || []).length;
                    
                    // nb_underscore
                    features.nb_underscore = (url.match(/_/g) || []).length;
                    
                    // nb_tilde
                    features.nb_tilde = (url.match(/~/g) || []).length;
                    
                    // nb_percent
                    features.nb_percent = (url.match(/%/g) || []).length;
                    
                    // nb_slash
                    features.nb_slash = (url.match(/\//g) || []).length;
                    
                    // nb_star
                    features.nb_star = (url.match(/\*/g) || []).length;
                    
                    // nb_colon
                    features.nb_colon = (url.match(/:/g) || []).length;
                    
                    // nb_comma
                    features.nb_comma = (url.match(/,/g) || []).length;
                    
                    // nb_semicolumn
                    features.nb_semicolumn = (url.match(/;/g) || []).length;
                    
                    // nb_dollar
                    features.nb_dollar = (url.match(/\$/g) || []).length;
                    
                    // nb_space
                    features.nb_space = (url.match(/ /g) || []).length + (url.match(/%20/g) || []).length;
                    
                    // nb_www: words_raw 배열에서 'www'를 포함한 단어 개수
                    var wwwCount = 0;
                    for (var wi = 0; wi < urlWords.length; wi++) {
                        if (urlWords[wi].toLowerCase().indexOf('www') !== -1) wwwCount++;
                    }
                    features.nb_www = wwwCount;
                    
                    // nb_com: words_raw 배열에서 'com'을 포함한 단어 개수
                    var comCount = 0;
                    for (var ci = 0; ci < urlWords.length; ci++) {
                        if (urlWords[ci].toLowerCase().indexOf('com') !== -1) comCount++;
                    }
                    features.nb_com = comCount;
                    
                    // nb_dslash: Python 로직 - 마지막 '//' 위치가 6보다 크면 1, 아니면 0
                    var slashMatches = [];
                    var slashRegex = /\/\//g;
                    var match;
                    while ((match = slashRegex.exec(url)) !== null) {
                        slashMatches.push(match.index);
                    }
                    if (slashMatches.length > 0 && slashMatches[slashMatches.length - 1] > 6) {
                        features.nb_dslash = 1;
                    } else {
                        features.nb_dslash = 0;
                    }
                    
                    // http_in_path
                    features.http_in_path = pathLower.includes('http') ? 1 : 0;
                    
                    // https_token: HTTPS면 0(안전), HTTP면 1(위험)
                    features.https_token = window.location.protocol === 'https:' ? 0 : 1;
                    
                    // ratio_digits_url
                    features.ratio_digits_url = (url.match(/\d/g) || []).length / Math.max(url.length, 1);
                    
                    // ratio_digits_host
                    features.ratio_digits_host = (window.location.hostname.match(/\d/g) || []).length / Math.max(window.location.hostname.length, 1);
                    
                    // punycode: Python 로직 - URL이 http://xn-- 또는 https://xn--로 시작하는지
                    features.punycode = (url.startsWith('http://xn--') || url.startsWith('https://xn--')) ? 1 : 0;
                    
                    // port: Python 정규식으로 포트 체크
                    features.port = /^[a-z][a-z0-9+\-.]*:\/\/([a-z0-9\-._~%!$&'()*+,;=]+@)?([a-z0-9\-._~%]+|\[[a-z0-9\-._~%!$&'()*+,;=:]+\]):([0-9]+)/.test(url) ? 1 : 0;
                    
                    // tld_in_path: Python 로직 - path에 tld 문자열이 포함되어 있는지
                    features.tld_in_path = pathLower.indexOf(tld) !== -1 ? 1 : 0;
                    
                    // tld_in_subdomain: Python 로직 - subdomain에 tld 문자열이 포함되어 있는지
                    features.tld_in_subdomain = subdomainPart.toLowerCase().indexOf(tld) !== -1 ? 1 : 0;
                    
                    // abnormal_subdomain: Python 정규식
                    features.abnormal_subdomain = /(http[s]?:\/\/(w[w]?|\d))([w]?(\d|-))/.test(url) ? 1 : 0;
                    
                    // nb_subdomains: Python 로직 - 점이 1개면 1, 2개면 2, 그 외는 3
                    var dotCount = (url.match(/\./g) || []).length;
                    if (dotCount == 1) {
                        features.nb_subdomains = 1;
                    } else if (dotCount == 2) {
                        features.nb_subdomains = 2;
                    } else {
                        features.nb_subdomains = 3;
                    }
                    
                    // prefix_suffix: Python 정규식 - https?://[^\-]+-[^\-]+/ 패턴 체크
                    features.prefix_suffix = /https?:\/\/[^\-]+-[^\-]+\//.test(url) ? 1 : 0;
                    
                    // random_domain: 모음이 적은 랜덤 도메인인지
                    features.random_domain = (domainLabel && domainLabel.length >= 5 && (domainLabel.replace(/[aeiou]/gi,'').length / domainLabel.length) > 0.6) ? 1 : 0;
                    
                    // shortening_service
                    var shortenerHosts = ['bit.ly','tinyurl.com','t.co','goo.gl','ow.ly','is.gd','s.id','rebrand.ly','buff.ly','cutt.ly','lnkd.in'];
                    features.shortening_service = shortenerHosts.includes(hostLower) ? 1 : 0;
                    
                    // path_extension: Python 로직 - .txt로 끝나면 1, 아니면 0
                    features.path_extension = window.location.pathname.endsWith('.txt') ? 1 : 0;
                    
                    // nb_redirection: Performance API로 리다이렉트 카운트 (JavaScript에서 가능한 범위)
                    var redirectChainLength = 0;
                    try {
                        if (window.performance && window.performance.getEntriesByType) {
                            var navEntries = window.performance.getEntriesByType('navigation');
                            if (navEntries && navEntries.length > 0 && typeof navEntries[0].redirectCount === 'number') {
                                redirectChainLength = navEntries[0].redirectCount;
                            } else if (window.performance.navigation && typeof window.performance.navigation.redirectCount === 'number') {
                                redirectChainLength = window.performance.navigation.redirectCount;
                            }
                        }
                    } catch (redirectErr) {
                        redirectChainLength = 0;
                    }
                    features.nb_redirection = redirectChainLength;
                    
                    // nb_external_redirection: 앱에서 동적으로 계산
                    features.nb_external_redirection = 0;
                    
                    // length_words_raw
                    features.length_words_raw = urlWords.length;
                    
                    // char_repeat: 2~5자 연속 반복 횟수의 합계 (Python 로직과 동일)
                    function countCharRepeat(words) {
                        var repeatCounts = {2: 0, 3: 0, 4: 0, 5: 0};
                        for (var wi = 0; wi < words.length; wi++) {
                            var word = words[wi];
                            for (var len = 2; len <= 5; len++) {
                                for (var i = 0; i <= word.length - len; i++) {
                                    var substr = word.substr(i, len);
                                    var allSame = true;
                                    for (var c = 1; c < substr.length; c++) {
                                        if (substr[c] !== substr[0]) { allSame = false; break; }
                                    }
                                    if (allSame) repeatCounts[len]++;
                                }
                            }
                        }
                        return repeatCounts[2] + repeatCounts[3] + repeatCounts[4] + repeatCounts[5];
                    }
                    features.char_repeat = countCharRepeat(urlWords);
                    
                    // shortest_words_raw
                    var urlWordLengths = urlWords.map(function(w) { return w.length; });
                    features.shortest_words_raw = urlWordLengths.length > 0 ? Math.min.apply(null, urlWordLengths) : 0;
                    
                    // shortest_word_host
                    var hostWordLengths = hostWords.map(function(w) { return w.length; });
                    features.shortest_word_host = hostWordLengths.length > 0 ? Math.min.apply(null, hostWordLengths) : 0;
                    
                    // shortest_word_path
                    var pathWordLengths = pathWords.map(function(w) { return w.length; });
                    features.shortest_word_path = pathWordLengths.length > 0 ? Math.min.apply(null, pathWordLengths) : 0;
                    
                    // longest_words_raw
                    features.longest_words_raw = urlWordLengths.length > 0 ? Math.max.apply(null, urlWordLengths) : 0;
                    
                    // longest_word_host
                    features.longest_word_host = hostWordLengths.length > 0 ? Math.max.apply(null, hostWordLengths) : 0;
                    
                    // longest_word_path
                    features.longest_word_path = pathWordLengths.length > 0 ? Math.max.apply(null, pathWordLengths) : 0;
                    
                    // avg_words_raw
                    function calcAvg(arr) {
                        if (!arr || arr.length === 0) return 0;
                        var sum = 0;
                        for (var i = 0; i < arr.length; i++) sum += arr[i];
                        return sum / arr.length;
                    }
                    features.avg_words_raw = calcAvg(urlWordLengths);
                    
                    // avg_word_host
                    features.avg_word_host = calcAvg(hostWordLengths);
                    
                    // avg_word_path
                    features.avg_word_path = calcAvg(pathWordLengths);
                    
                    // phish_hints: Python의 HINTS 리스트와 동일
                    var phishKeywords = ['wp','login','includes','admin','content','site','images','js','alibaba','css','myaccount','dropbox','themes','plugins','signin','view'];
                    var urlLower = url.toLowerCase();
                    var phishHintCount = 0;
                    for (var pk = 0; pk < phishKeywords.length; pk++) {
                        if (urlLower.indexOf(phishKeywords[pk]) !== -1) phishHintCount++;
                    }
                    features.phish_hints = phishHintCount;
                    
                    // domain_in_brand: Python 로직 - domain이 brand 리스트에 정확히 있는지 (Exact Match)
                    var brandKeywords = ['paypal','naver','apple','bank','google','microsoft','kakao','facebook','instagram','amazon','ebay','netflix','samsung'];
                    features.domain_in_brand = brandKeywords.includes(domainLabel) ? 1 : 0;
                    
                    // brand_in_subdomain: Python 로직 - '.'+brand+'.'이 subdomain에 있는지
                    features.brand_in_subdomain = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        if (subdomainPart.indexOf('.' + brandKeywords[b] + '.') !== -1) {
                            features.brand_in_subdomain = 1;
                            break;
                        }
                    }
                    
                    // brand_in_path: Python 로직 - '.'+brand+'.'이 path에 있는지
                    features.brand_in_path = 0;
                    for (var b = 0; b < brandKeywords.length; b++) {
                        if (pathLower.indexOf('.' + brandKeywords[b] + '.') !== -1) {
                            features.brand_in_path = 1;
                            break;
                        }
                    }
                    
                    // suspecious_tld
                    var suspiciousTlds = ['fit','tk','gp','ga','work','ml','date','wang','men','icu','online','click','xyz','top','zip','country','stream','download','xin','racing','jetzt','ren','mom','party','review','trade','accountants','science','ninja','faith','cricket','win','accountant','realtor','christmas','gdn','link','asia','club','la','ae','exposed','pe','rs','audio','website','bj','mx','media'];
                    features.suspecious_tld = suspiciousTlds.includes(tld) ? 1 : 0;
                    
                    // statistical_report: 클라이언트에서는 구현 불가능하므로 0으로 설정
                    features.statistical_report = 0;

                    // ===== 콘텐츠 기반 피처 (content_features.py 기준) =====
                    
                    // nb_hyperlinks: 모든 hyperlink 요소의 합 (Href, Link, Media, Form, CSS, Favicon)
                    // Python: len(Href['internals']) + len(Href['externals']) + ... (모든 카테고리)
                    var allHrefElements = document.querySelectorAll('[href]');
                    var allSrcElements = document.querySelectorAll('[src]');
                    features.nb_hyperlinks = allHrefElements.length + allSrcElements.length;
                    
                    // Anchor 분석용
                    var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                    var totalAnchors = anchors.length;
                    
                    // ratio_intHyperlinks, ratio_extHyperlinks, ratio_nullHyperlinks
                    var internalCount = 0;
                    var externalCount = 0;
                    var nullCount = 0;
                    for (var a = 0; a < anchors.length; a++) {
                        var href = anchors[a].getAttribute('href');
                        if (!href || href.trim() === '' || href.startsWith('#') || href.toLowerCase().startsWith('javascript:')) {
                            nullCount++;
                            continue;
                        }
                        var n = normalizeUrl(href);
                        if (!n || !n.hostname) {
                            nullCount++;
                            continue;
                        }
                        if (n.hostname === window.location.hostname) internalCount++; else externalCount++;
                    }
                    features.ratio_intHyperlinks = totalAnchors === 0 ? 0 : (internalCount / totalAnchors);
                    features.ratio_extHyperlinks = totalAnchors === 0 ? 0 : (externalCount / totalAnchors);
                    features.ratio_nullHyperlinks = totalAnchors === 0 ? 0 : (nullCount / totalAnchors);
                    
                    // nb_extCSS: 외부 도메인에서 로드하는 CSS 파일 수
                    var cssLinks = document.querySelectorAll('link[rel="stylesheet"]');
                    var extCSSCount = 0;
                    for (var ci = 0; ci < cssLinks.length; ci++) {
                        var cssHref = cssLinks[ci].getAttribute('href');
                        if (cssHref) {
                            var cssUrl = normalizeUrl(cssHref);
                            if (cssUrl && cssUrl.hostname && cssUrl.hostname !== window.location.hostname) {
                                extCSSCount++;
                            }
                        }
                    }
                    features.nb_extCSS = extCSSCount;
                    
                    // ratio_intRedirection, ratio_extRedirection: 앱에서 동적으로 계산
                    features.ratio_intRedirection = 0;
                    features.ratio_extRedirection = 0;
                    
                    // ratio_intErrors, ratio_extErrors: 앱에서 동적으로 계산
                    features.ratio_intErrors = 0;
                    features.ratio_extErrors = 0;
                    
                    // login_form: Python 로직 - 외부/null Form이 있거나 .php로 끝나는 Form action
                    var forms = document.getElementsByTagName('form');
                    var hasExternalOrNullForm = false;
                    var hasPhpForm = false;
                    
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').trim();
                        
                        // null 또는 외부 Form 체크
                        if (!action || action === '' || action === '#' || action === 'about:blank' || action.startsWith('javascript:')) {
                            hasExternalOrNullForm = true;
                        } else if (action.indexOf('http') === 0) {
                            var formUrl = normalizeUrl(action);
                            if (formUrl && formUrl.hostname && formUrl.hostname !== window.location.hostname) {
                                hasExternalOrNullForm = true;
                            }
                        }
                        
                        // .php로 끝나는지 체크
                        if (/([a-zA-Z0-9_])+\.php/.test(action)) {
                            hasPhpForm = true;
                        }
                    }
                    
                    features.login_form = (hasExternalOrNullForm || hasPhpForm) ? 1 : 0;
                    
                    // external_favicon: 외부 favicon 존재 여부
                    var faviconLinks = document.querySelectorAll('link[rel*="icon"]');
                    var hasExternalFavicon = false;
                    for (var fi = 0; fi < faviconLinks.length; fi++) {
                        var faviHref = faviconLinks[fi].getAttribute('href');
                        if (faviHref && faviHref.indexOf('http') === 0) {
                            var favUrl = normalizeUrl(faviHref);
                            if (favUrl && favUrl.hostname && favUrl.hostname !== window.location.hostname) {
                                hasExternalFavicon = true;
                                break;
                            }
                        }
                    }
                    features.external_favicon = hasExternalFavicon ? 1 : 0;
                    
                    // links_in_tags: 내부 링크 비율 (0 ~ 100, Python과 동일)
                    // Python: Link['internals'] / (Link['internals'] + Link['externals']) * 100
                    var linkElements = document.querySelectorAll('link[href]');
                    var internalLinks = 0;
                    var externalLinks = 0;
                    for (var li = 0; li < linkElements.length; li++) {
                        var linkHref = linkElements[li].getAttribute('href');
                        if (!linkHref) continue;
                        var linkUrl = normalizeUrl(linkHref);
                        if (!linkUrl || !linkUrl.hostname) continue;
                        if (linkUrl.hostname === window.location.hostname) internalLinks++; else externalLinks++;
                    }
                    var totalLinks = internalLinks + externalLinks;
                    features.links_in_tags = totalLinks === 0 ? 0 : ((internalLinks / totalLinks) * 100);
                    
                    // submit_email: Python 로직 - Form action에 mailto: 또는 mail() 포함
                    var hasEmailSubmit = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').toLowerCase();
                        if (action.indexOf('mailto:') !== -1 || action.indexOf('mail()') !== -1) {
                            hasEmailSubmit = true;
                        } else {
                            hasEmailSubmit = false;
                        }
                        break; // Python은 첫 번째 Form만 체크
                    }
                    features.submit_email = hasEmailSubmit ? 1 : 0;
                    
                    // ratio_intMedia, ratio_extMedia: 미디어 비율 (0 ~ 100, Python과 동일)
                    var mediaEls = Array.prototype.slice.call(document.querySelectorAll('img, video, audio, source'));
                    var totalMedia = mediaEls.length;
                    var internalMedia = 0;
                    var externalMedia = 0;
                    for (var m = 0; m < mediaEls.length; m++) {
                        var src = mediaEls[m].getAttribute('src') || mediaEls[m].getAttribute('data-src');
                        if (!src) continue;
                        var nm = normalizeUrl(src);
                        if (!nm || !nm.hostname) continue;
                        if (nm.hostname === window.location.hostname) internalMedia++; else externalMedia++;
                    }
                    features.ratio_intMedia = totalMedia === 0 ? 0 : ((internalMedia / totalMedia) * 100);
                    features.ratio_extMedia = totalMedia === 0 ? 0 : ((externalMedia / totalMedia) * 100);
                    
                    // sfh: Server Form Handler (빈값/#/외부 도메인/about:blank일 때 unsafe)
                    var unsafeForms = 0;
                    for (var f = 0; f < forms.length; f++) {
                        var action = forms[f].getAttribute('action') || '';
                        var trimmed = action.trim().toLowerCase();
                        if (!trimmed || trimmed === '#' || trimmed === 'about:blank' || trimmed.startsWith('javascript:')) {
                            unsafeForms++; continue;
                        }
                        if (trimmed.indexOf('http') === 0) {
                            var urlA = normalizeUrl(trimmed);
                            if (urlA && urlA.hostname && urlA.hostname !== window.location.hostname) unsafeForms++;
                        }
                    }
                    features.sfh = forms.length === 0 ? 0 : (unsafeForms / forms.length);
                    
                    // iframe: iframe 개수
                    var iframes = document.getElementsByTagName('iframe');
                    var invisibleIframeCount = 0;
                    for (var ifi = 0; ifi < iframes.length; ifi++) {
                        var iframe = iframes[ifi];
                        var width = iframe.getAttribute('width') || iframe.width || '';
                        var height = iframe.getAttribute('height') || iframe.height || '';
                        var border = iframe.getAttribute('frameborder') || iframe.getAttribute('border') || '';
                        var style = iframe.getAttribute('style') || '';
                        if ((width === '0' || width === 0) && (height === '0' || height === 0)) {
                            invisibleIframeCount++;
                        }
                        if (border === '0' && style.indexOf('border:none') !== -1 && (width === '0' || height === '0')) {
                            invisibleIframeCount++;
                        }
                    }
                    features.iframe = invisibleIframeCount > 0 ? 1 : 0;
                    
                    // popup_window: prompt가 있는지 (Python 로직)
                    var hasPopup = false;
                    var scripts = document.getElementsByTagName('script');
                    for (var si = 0; si < scripts.length && !hasPopup; si++) {
                        var scriptContent = scripts[si].textContent || '';
                        if (scriptContent.indexOf('prompt(') !== -1) hasPopup = true;
                    }
                    features.popup_window = hasPopup ? 1 : 0;
                    
                    // safe_anchor: 안전하지 않은 앵커 비율 (0 ~ 100, Python과 동일)
                    // Python의 Anchor['safe']는 외부링크, Anchor['unsafe']는 null/javascript 링크
                    var safeAnchors = externalCount;
                    var unsafeAnchors = nullCount;
                    var totalForSafe = safeAnchors + unsafeAnchors;
                    features.safe_anchor = totalForSafe === 0 ? 0 : ((unsafeAnchors / totalForSafe) * 100);
                    
                    // onmouseover: onmouseover 이벤트가 있는지
                    var hasOnmouseover = (document.querySelectorAll('[onmouseover]').length > 0);
                    if (!hasOnmouseover && document.body) {
                        hasOnmouseover = document.body.innerHTML.toLowerCase().indexOf('onmouseover="window.status=') !== -1;
                    }
                    features.onmouseover = hasOnmouseover ? 1 : 0;
                    
                    // right_clic: 우클릭 방지가 있는지
                    var hasRightClick = false;
                    if (document.body && document.body.oncontextmenu) hasRightClick = true;
                    if (document.querySelectorAll('[oncontextmenu]').length > 0) hasRightClick = true;
                    if (document.body && document.body.innerHTML.match(/event\.button\s*==\s*2/)) hasRightClick = true;
                    features.right_clic = hasRightClick ? 1 : 0;
                    
                    // empty_title: 타이틀이 비어있는지
                    features.empty_title = (document.title.trim() === '') ? 1 : 0;
                    
                    // domain_in_title: 타이틀에 도메인이 있는지 (0=있음, 1=없음)
                    var titleLower = document.title.toLowerCase();
                    var mainDomain = hostParts.length >= 2 ? hostParts[hostParts.length - 2] : hostParts[0];
                    features.domain_in_title = (titleLower.indexOf(mainDomain) !== -1) ? 0 : 1;
                    
                    // domain_with_copyright: 페이지에 © 기호와 도메인이 함께 있는지 (0=있음, 1=없음)
                    var bodyTextForCopy = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
                    var hasCopyright = (bodyTextForCopy.indexOf('©') !== -1 || bodyTextForCopy.indexOf('copyright') !== -1);
                    features.domain_with_copyright = (hasCopyright && bodyTextForCopy.indexOf(mainDomain) !== -1) ? 0 : 1;

                    // Android로 데이터 전송
                    Android.receiveFeatures(JSON.stringify(features));
                } catch (e) {
                    console.error('피처 추출 중 오류:', e);
                    Android.receiveFeatures(JSON.stringify({
                        error: e.message
                    }));
                }
            })();
        """.trimIndent()
    }
}
