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

// 웹페이지 피처 데이터 클래스 (79개 피처를 Map으로 저장)
typealias WebFeatures = Map<String, Float?>

// 논문에서 제안하는 규칙 기반 피싱 탐지 시스템
class PhishingDetector(private val context: Context) {

    private val mlPredictor = TFLitePhishingPredictor(context)

    // 피싱 탐지 규칙들 (논문 기반)
    private val PHISHING_RULES = mapOf(
        "DOM_NODE_THRESHOLD" to 500,      // DOM 노드 수 임계값
                        // If array contains numbers (lengths) treat elements as numbers
                        if (typeof words[0] === 'number') {
                            var minNum = Infinity;
                            for (var i = 0; i < words.length; i++) {
                                var v = Number(words[i]);
                                if (isFinite(v) && v < minNum) minNum = v;
                            }
                            return (minNum === Infinity) ? 0 : minNum;
                        }
                        // Otherwise treat items as strings and use their lengths
                        var minLen = Infinity;
                        for (var i = 0; i < words.length; i++) {
                            var cur = words[i];
                            var l = (cur == null) ? Infinity : (typeof cur === 'number' ? cur : String(cur).length);
                            if (l < minLen) minLen = l;
                        }
                        return (minLen === Infinity) ? 0 : minLen;
                    }

                    function safeMax(words) {
                        if (!words || words.length === 0) return 0;
                        // If numbers (precomputed lengths) provided, return numeric max
                        if (typeof words[0] === 'number') {
                            var maxNum = -Infinity;
                            for (var i = 0; i < words.length; i++) {
                                var v = Number(words[i]);
                                if (isFinite(v) && v > maxNum) maxNum = v;
                            }
                            return (maxNum === -Infinity) ? 0 : maxNum;
                        }
                        // Otherwise compute by string length
                        var maxLen = 0;
                        for (var i = 0; i < words.length; i++) {
                            var cur = words[i];
                            var l = (cur == null) ? 0 : (typeof cur === 'number' ? cur : String(cur).length);
                            if (l > maxLen) maxLen = l;
                        }
                        return maxLen;
                    }

                    function safeAvg(words) {
                        if (!words || words.length === 0) return 0;
                        // If array of numbers (lengths) is provided
                        if (typeof words[0] === 'number') {
                            var totalNum = 0;
                            var cntNum = 0;
                            for (var i = 0; i < words.length; i++) {
                                var v = Number(words[i]);
                                if (isFinite(v)) { totalNum += v; cntNum++; }
                            }
                            return cntNum === 0 ? 0 : (totalNum / cntNum);
                        }
                        var total = 0;
                        var cnt = 0;
                        for (var i = 0; i < words.length; i++) {
                            var cur = words[i];
                            if (cur != null) {
                                var l = (typeof cur === 'number') ? cur : String(cur).length;
                                if (isFinite(l)) { total += l; cnt++; }
                            }
                        }
                        return cnt === 0 ? 0 : (total / cnt);
                    }

                    function normalizeUrl(raw) {
                        try {
                            return new URL(raw, window.location.href);
                        } catch (e) {
                            return null;
                        }
                    }
                    // safeMin/safeMax/safeAvg are defined above and reused.
                    // DOM 노드 수 계산 
                    var domNodeCount = document.getElementsByTagName('*').length;

                    // iframe 개수 계산
                    var iframeCount = document.getElementsByTagName('iframe').length;

                    // 외부 도메인 form 개수 계산
                    var externalDomainFormCount = 0;
                    var forms = document.getElementsByTagName('form');
                    var currentDomain = window.location.hostname;
                    for (var i = 0; i < forms.length; i++) {
                        var action = forms[i].getAttribute('action');
                        if (action && action.includes('http') && !action.includes(currentDomain)) {
                            externalDomainFormCount++;
                        }
                    }

                    // base64 인코딩 스크립트 수 계산
                    var base64ScriptCount = 0;
                    var scripts = document.getElementsByTagName('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var src = scripts[i].getAttribute('src');
                        if (src && (src.includes('base64') || src.includes('data:text'))) {
                            base64ScriptCount++;
                        }
                    }

                    // 이벤트 리스너 수 계산 (추정)
                    var eventListenerCount = 0;
                    var allElements = document.getElementsByTagName('*');
                    var eventAttributes = ['onclick','onload','onmouseover','onfocus','onblur','onchange','onsubmit','onerror','onkeydown','onkeyup','onkeypress','onmouseenter','onmouseleave','ondragstart','ondrop'];
                    for (var i = 0; i < allElements.length; i++) {
                        var el = allElements[i];
                        for (var j = 0; j < eventAttributes.length; j++) {
                            var attr = eventAttributes[j];
                            if (el.getAttribute(attr) !== null || typeof el[attr] === 'function') {
                                eventListenerCount++;
                            }
                        }
                    }

                    // 의심스러운 스크립트 수 계산
                    var suspiciousScriptCount = 0;
                    var suspiciousKeywords = ['eval', 'document.write', 'innerHTML', 'location.href', 'window.open', 'addEventListener', 'fetch(', 'XMLHttpRequest'];
                    for (var i = 0; i < scripts.length; i++) {
                        var scriptContent = scripts[i].textContent || scripts[i].innerText || '';
                        for (var j = 0; j < suspiciousKeywords.length; j++) {
                            if (scriptContent.includes(suspiciousKeywords[j])) {
                                suspiciousScriptCount++;
                                break;
                            }
                        }
                    }

                    // 리다이렉트 체인 길이 (현재 URL 기준)
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

                    // 로그인 폼 존재 여부
                    var hasLoginForm = false;
                    for (var i = 0; i < forms.length; i++) {
                        var inputs = forms[i].getElementsByTagName('input');
                        var hasUsername = false;
                        var hasPassword = false;
                        for (var j = 0; j < inputs.length; j++) {
                            var type = inputs[j].getAttribute('type');
                            var name = inputs[j].getAttribute('name');
                            if (type === 'password' || name === 'password') hasPassword = true;
                            if (type === 'text' || type === 'email' || name === 'username' || name === 'email') hasUsername = true;
                        }
                        if (hasUsername && hasPassword) {
                            hasLoginForm = true;
                            break;
                        }
                    }

                    // 신용카드 폼 존재 여부
                    // Improve credit card detection: check name/id/class/placeholder/label/aria, maxlength, pattern and form action
                    var hasCreditCardForm = false;
                    var cardFieldRegex = /card|cc|cvc|cvv|pan|cardnumber|card-number|card_no|cardno|expiry|exp|card_exp|cardnumber/i;
                    var paymentActionRegex = /(stripe|paypal|checkout|payment|billing|pay|square|authorize|adyen|payu|alipay|googlepay|checkout)/i;

                    for (var i = 0; i < forms.length && !hasCreditCardForm; i++) {
                        var f = forms[i];
                        var inputs = f.getElementsByTagName('input');
                        for (var j = 0; j < inputs.length && !hasCreditCardForm; j++) {
                            var inp = inputs[j];
                            var name = (inp.getAttribute('name') || '') + ' ' + (inp.getAttribute('id') || '') + ' ' + (inp.className || '');
                            var placeholder = inp.getAttribute('placeholder') || '';
                            var aria = inp.getAttribute('aria-label') || '';
                            var labelText = '';
                            try {
                                var label = document.querySelector('label[for="' + inp.id + '"]');
                                if (label) labelText = label.textContent || '';
                            } catch (e) { }

                            // If name/id/class/placeholder/aria/label contain card keywords
                            if (cardFieldRegex.test(name) || cardFieldRegex.test(placeholder) || cardFieldRegex.test(aria) || cardFieldRegex.test(labelText)) {
                                hasCreditCardForm = true;
                                break;
                            }

                            // Check maxlength or pattern for card numbers
                            var ml = inp.maxLength; // -1 if not set
                            if (ml && ml >= 13 && ml <= 19) {
                                hasCreditCardForm = true; break;
                            }
                            var pattern = inp.getAttribute('pattern') || '';
                            if (/\d{13,19}/.test(pattern)) { hasCreditCardForm = true; break; }
                        }

                        var action = (f.getAttribute('action') || '') + ' ' + (f.textContent || '');
                        if (paymentActionRegex.test(action)) {
                            hasCreditCardForm = true; break;
                        }
                    }

                    // URL 길이 및 특수문자 수
                    var url = window.location.href;
                    var urlLength = url.length;
                    var specialCharCount = (url.match(/[^a-zA-Z0-9]/g) || []).length;
                    var hostLower = window.location.hostname.toLowerCase();
                    var pathLower = window.location.pathname.toLowerCase();
                    var hostParts = hostLower.split('.');
                    var subdomainPart = hostParts.length > 2 ? hostParts.slice(0, hostParts.length - 2).join('.') : '';
                    var domainLabel = hostParts.length > 1 ? hostParts[hostParts.length - 2] : hostLower;
                    var knownTlds = ['com','net','org','edu','gov','co','biz','info','xyz','top','icu','io','me','shop','online','site','ru','cn','su'];
                    var shortenerHosts = ['bit.ly','tinyurl.com','t.co','goo.gl','ow.ly','is.gd','s.id','rebrand.ly','buff.ly','cutt.ly','lnkd.in'];
                    var pathTokens = pathLower.split(/[\/\?#&_\-.]/).filter(function(w){ return w; });

                    var features = {};

                    // URL 기반 피처
                    features.length_url = url.length;
                    features.length_hostname = window.location.hostname.length;
                    features.ip = /^(\d{1,3}\.){3}\d{1,3}$/.test(window.location.hostname) ? 1 : 0;
                    features.nb_dots = (url.match(/\./g) || []).length;
                    features.nb_hyphens = (url.match(/-/g) || []).length;
                    features.nb_at = (url.match(/@/g) || []).length;
                    features.nb_qm = (url.match(/\?/g) || []).length;
                    features.nb_and = (url.match(/&/g) || []).length;
                    features.nb_or = (url.match(/\|/g) || []).length;
                    features.nb_eq = (url.match(/=/g) || []).length;
                    features.nb_underscore = (url.match(/_/g) || []).length;
                    features.nb_tilde = (url.match(/~/g) || []).length;
                    features.nb_percent = (url.match(/%/g) || []).length;
                    features.nb_slash = (url.match(/\//g) || []).length;
                    features.nb_star = (url.match(/\*/g) || []).length;
                    features.nb_colon = (url.match(/:/g) || []).length;
                    features.nb_comma = (url.match(/,/g) || []).length;
                    features.nb_semicolumn = (url.match(/;/g) || []).length;
                    features.nb_dollar = (url.match(/\$/g) || []).length;
                    features.nb_space = (url.match(/ /g) || []).length;
                    features.nb_www = (url.match(/www/gi) || []).length;
                    features.nb_com = (url.match(/\.com/gi) || []).length;
                    // nb_dslash: 프로토콜(http://, https://) 제외하고 // 카운트
                    var urlWithoutProtocol = url.replace(/^https?:\/\//, '');
                    features.nb_dslash = (urlWithoutProtocol.match(/\/\//g) || []).length;
                    features.http_in_path = pathLower.includes('http') ? 1 : 0;
                    // https_token: URL에 "https" 토큰이 있는지 (호스트네임이 아닌 path 등에)
                    // CSV에서는 https://인 경우 1, http://인 경우 0으로 보임
                    features.https_token = window.location.protocol === 'https:' ? 1 : 0;
                    features.ratio_digits_url = (url.match(/\d/g) || []).length / Math.max(url.length, 1);
                    features.ratio_digits_host = (window.location.hostname.match(/\d/g) || []).length / Math.max(window.location.hostname.length, 1);
                    features.punycode = window.location.hostname.includes('xn--') ? 1 : 0;
                    features.port = window.location.port ? 1 : 0;
                    features.tld_in_path = pathTokens.some(function(tok){ return knownTlds.includes(tok); }) ? 1 : 0;
                    var subTokens = subdomainPart.split('.').filter(function(w){ return w; });
                    features.tld_in_subdomain = subTokens.some(function(tok){ return knownTlds.includes(tok); }) ? 1 : 0;
                    var subDigits = subdomainPart.replace(/[^0-9]/g,'').length;
                    features.abnormal_subdomain = (subdomainPart.length >= 30 || (subdomainPart.match(/\./g) || []).length >= 2 || (subDigits / Math.max(subdomainPart.length || 1, 1)) > 0.3) ? 1 : 0;
                    // nb_subdomains: 호스트의 점(.) 개수 = 서브도메인 레벨 수
                    // 예: www.example.com => 2개 점 => nb_subdomains = 2가 아닌 3 (구분되는 파트 수 - 1)
                    // CSV 기준: 점 개수가 서브도메인 수를 의미하는 것으로 보임
                    features.nb_subdomains = (window.location.hostname.match(/\./g) || []).length;
                    features.prefix_suffix = window.location.hostname.includes('-') ? 1 : 0;
                    features.random_domain = (domainLabel && domainLabel.length >= 5 && (domainLabel.replace(/[aeiou]/gi,'').length / domainLabel.length) > 0.6) ? 1 : 0;
                    features.shortening_service = shortenerHosts.includes(hostLower) ? 1 : 0;
                    features.path_extension = /\.(php|html|htm|asp|aspx|jsp|exe|scr|zip|rar|jar|bat)$/i.test(window.location.pathname) ? 1 : 0;
                    features.nb_redirection = redirectChainLength;
                    // nb_external_redirection: 페이지 리소스 중 외부 도메인 수 (과거 방식 유지하되 실제로는 측정 어려움)
                    features.nb_external_redirection = 0;

                    // 페이지 콘텐츠 기반 - URL 전체를 단어로 분리하여 계산 (CSV 방식)
                    // URL에서 알파벳/숫자가 아닌 문자로 분리한 단어들
                    var urlWords = url.split(/[^a-zA-Z0-9]/).filter(function(w){ return w && w.length > 0; });
                    var hostWords = window.location.hostname.split(/[^a-zA-Z0-9]/).filter(function(w){ return w && w.length > 0; });
                    var pathWords = window.location.pathname.split(/[^a-zA-Z0-9]/).filter(function(w){ return w && w.length > 0; });
                    
                    // length_words_raw: URL 전체에서 추출한 단어 개수
                    features.length_words_raw = urlWords.length;
                    
                    // char_repeat: URL에서 같은 문자가 3번 이상 연속으로 반복되는 패턴 중 가장 긴 것의 길이
                    var repeatMatches = url.match(/(.)\1+/g) || [];
                    var maxRepeat = 0;
                    for (var ri = 0; ri < repeatMatches.length; ri++) {
                        if (repeatMatches[ri].length > maxRepeat) maxRepeat = repeatMatches[ri].length;
                    }
                    features.char_repeat = maxRepeat;
                    
                    // shortest/longest/avg words: 단어 길이 계산
                    var urlWordLengths = urlWords.map(function(w) { return w.length; });
                    var hostWordLengths = hostWords.map(function(w) { return w.length; });
                    var pathWordLengths = pathWords.map(function(w) { return w.length; });
                    
                    features.shortest_words_raw = urlWordLengths.length > 0 ? Math.min.apply(null, urlWordLengths) : 0;
                    features.shortest_word_host = hostWordLengths.length > 0 ? Math.min.apply(null, hostWordLengths) : 0;
                    features.shortest_word_path = pathWordLengths.length > 0 ? Math.min.apply(null, pathWordLengths) : 0;
                    features.longest_words_raw = urlWordLengths.length > 0 ? Math.max.apply(null, urlWordLengths) : 0;
                    features.longest_word_host = hostWordLengths.length > 0 ? Math.max.apply(null, hostWordLengths) : 0;
                    features.longest_word_path = pathWordLengths.length > 0 ? Math.max.apply(null, pathWordLengths) : 0;
                    
                    // avg: 단어 길이 평균
                    function calcAvg(arr) {
                        if (!arr || arr.length === 0) return 0;
                        var sum = 0;
                        for (var i = 0; i < arr.length; i++) sum += arr[i];
                        return sum / arr.length;
                    }
                    features.avg_words_raw = calcAvg(urlWordLengths);
                    features.avg_word_host = calcAvg(hostWordLengths);
                    features.avg_word_path = calcAvg(pathWordLengths);
                    
                    // phish_hints: URL에서 피싱 관련 키워드 수 (문서 본문이 아닌 URL에서만)
                    var phishKeywords = ['login','signin','verify','account','update','secure','banking','confirm','password','credential','authenticate','wallet','suspend'];
                    var urlLower = url.toLowerCase();
                    var phishHintCount = 0;
                    for (var pk = 0; pk < phishKeywords.length; pk++) {
                        if (urlLower.indexOf(phishKeywords[pk]) !== -1) phishHintCount++;
                    }
                    features.phish_hints = phishHintCount;
                    // 브랜드 관련: 단순 포함 검사 (앱에서 브랜드 리스트로 관리 권장)
                    var brandKeywords = ['paypal','naver','apple','bank','google','microsoft','kakao','facebook','instagram'];
                    function containsBrand(str) {
                        if (!str) return false;
                        var lower = str.toLowerCase();
                        for (var b = 0; b < brandKeywords.length; b++) {
                            if (lower.indexOf(brandKeywords[b]) !== -1) return true;
                        }
                        return false;
                    }
                    features.domain_in_brand = containsBrand(domainLabel) ? 1 : 0;
                    features.brand_in_subdomain = containsBrand(subdomainPart) ? 1 : 0;
                    features.brand_in_path = containsBrand(pathLower) ? 1 : 0;
                    features.suspecious_tld = ['xyz', 'top', 'icu'].includes(window.location.hostname.split('.').pop()) ? 1 : 0;
                    // nb_hyperlinks: href 속성이 있는 a 태그 수
                    var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                    var totalAnchors = anchors.length;
                    features.nb_hyperlinks = totalAnchors;
                    // 링크 비율 계산 (내부/외부/무효)
                    var internalCount = 0;
                    var externalCount = 0;
                    var nullCount = 0;
                    for (var a = 0; a < anchors.length; a++) {
                        var href = anchors[a].getAttribute('href');
                        if (!href || href.trim() === '' || href.startsWith('#') || href.startsWith('javascript:')) {
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
                    // ratio_intRedirection ~ ratio_extErrors: 구현 어려움, 0으로 설정
                    features.ratio_intRedirection = 0;
                    features.ratio_extRedirection = 0;
                    features.ratio_intErrors = 0;
                    features.ratio_extErrors = 0;
                    features.login_form = hasLoginForm ? 1 : 0;
                    features.external_favicon = document.querySelector('link[rel="icon"][href^="http"]') ? 1 : 0;
                    // links_in_tags: 링크가 시맨틱 태그 안에 있는 비율 (0-100 퍼센트)
                    try {
                        var containerTags = ['nav','header','footer','article','section','aside','p','li'];
                        var anchorsAllWithHref = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                        var anchoredInTagsCount = 0;
                        for (var i = 0; i < anchorsAllWithHref.length; i++) {
                            var el = anchorsAllWithHref[i];
                            var ancestor = el.closest(containerTags.join(','));
                            if (ancestor) anchoredInTagsCount++;
                        }
                        features.links_in_tags = anchorsAllWithHref.length === 0 ? 0 : ((anchoredInTagsCount / anchorsAllWithHref.length) * 100);
                    } catch (e) {
                        features.links_in_tags = 0;
                    }
                    // Improve submit_email detection: treat as email if there is an input type='email'
                    var hasEmailSubmit = false;
                    for (var i = 0; i < forms.length; i++) {
                        var inputs = forms[i].getElementsByTagName('input');
                        for (var j = 0; j < inputs.length; j++) {
                            var t = (inputs[j].getAttribute('type') || '').toLowerCase();
                            var name = (inputs[j].getAttribute('name') || '').toLowerCase();
                            if (t == 'email' || name.includes('email')) { hasEmailSubmit = true; break; }
                        }
                        if (hasEmailSubmit) break;
                    }
                    features.submit_email = hasEmailSubmit ? 1 : 0;
                    // 미디어 src 비율 (img/video/audio/source) - 0-100 퍼센트
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
                    // sfh: form action 빈값/#/외부 도메인일 때 unsafe, 비율로 반환
                    var unsafeForms = 0;
                    for (var f = 0; f < forms.length; f++) {
                        var action = forms[f].getAttribute('action') || '';
                        var trimmed = action.trim();
                        if (!trimmed || trimmed === '#') {
                            unsafeForms++; continue;
                        }
                        if (trimmed.indexOf('http') === 0) {
                            var urlA = normalizeUrl(trimmed);
                            if (urlA && urlA.hostname && urlA.hostname !== window.location.hostname) unsafeForms++;
                        }
                    }
                    features.sfh = forms.length === 0 ? 0 : (unsafeForms / forms.length);
                    features.iframe = iframeCount;
                    // popup 및 target=_blank 수집 - 1이면 있음, 0이면 없음
                    var hasPopup = false;
                    var anchorsAll = document.getElementsByTagName('a');
                    for (var x = 0; x < anchorsAll.length && !hasPopup; x++) {
                        var el = anchorsAll[x];
                        var onclick = el.getAttribute('onclick') || '';
                        if (onclick && onclick.indexOf('window.open') !== -1) hasPopup = true;
                    }
                    // 스크립트에서 window.open 검사
                    if (!hasPopup) {
                        for (var si = 0; si < scripts.length && !hasPopup; si++) {
                            var scriptContent = scripts[si].textContent || '';
                            if (scriptContent.indexOf('window.open') !== -1) hasPopup = true;
                        }
                    }
                    features.popup_window = hasPopup ? 1 : 0;
                    // safe_anchor: 안전한 앵커 비율 (0-100 퍼센트)
                    // null이 아닌 유효한 링크의 비율
                    features.safe_anchor = totalAnchors === 0 ? 0 : ((1 - (nullCount / totalAnchors)) * 100);
                    features.onmouseover = document.querySelectorAll('[onmouseover]').length > 0 ? 1 : 0;
                    features.right_clic = (document.body && document.body.oncontextmenu) ? 1 : (document.querySelectorAll('[oncontextmenu]').length > 0 ? 1 : 0);
                    features.empty_title = document.title.trim() === '' ? 1 : 0;
                    // domain_in_title: 타이틀에 도메인 이름(또는 주요 부분)이 포함되어 있는지
                    var titleLower = document.title.toLowerCase();
                    var domainParts = window.location.hostname.toLowerCase().split('.');
                    var mainDomain = domainParts.length >= 2 ? domainParts[domainParts.length - 2] : domainParts[0];
                    features.domain_in_title = (titleLower.indexOf(mainDomain) !== -1) ? 1 : 0;
                    // domain_with_copyright: 페이지에 © 기호와 도메인이 함께 있는지
                    var bodyTextForCopy = (document.body && document.body.innerText) ? document.body.innerText.toLowerCase() : '';
                    features.domain_with_copyright = (bodyTextForCopy.indexOf('©') !== -1 && bodyTextForCopy.indexOf(mainDomain) !== -1) ? 1 : 0;
                    // External API dependant features (left as null or commented)
                    // features.whois_registered_domain = null; // requires WHOIS lookup
                    // features.domain_registration_length = null; // requires WHOIS
                    // features.domain_age = null; // requires WHOIS
                    // features.web_traffic = null; // requires 3rd-party analytics
                    // features.dns_record = null; // requires DNS lookup
                    // features.google_index = null; // requires search engine API
                    // features.page_rank = null; // requires external API
                    // 외부 통신 기반 피처들은 앱/서버 통합을 통해 수집해야 하므로 여기서는 제외합니다

                    // 기존 피처 유지 (호환성)
                    features.domNodeCount = domNodeCount;
                    features.iframeCount = iframeCount;
                    features.externalDomainFormCount = externalDomainFormCount;
                    features.base64ScriptCount = base64ScriptCount;
                    features.eventListenerCount = eventListenerCount;
                    features.suspiciousScriptCount = suspiciousScriptCount;
                    features.redirectChainLength = redirectChainLength;
                    features.hasLoginForm = hasLoginForm;
                    features.hasCreditCardForm = hasCreditCardForm;
                    features.urlLength = urlLength;
                    features.specialCharCount = specialCharCount;

                    // Android로 데이터 전송 — only include the exact feature set used by training
                    var payload = {
                        length_url: features.length_url,
                        length_hostname: features.length_hostname,
                        ip: features.ip,
                        nb_dots: features.nb_dots,
                        nb_hyphens: features.nb_hyphens,
                        nb_at: features.nb_at,
                        nb_qm: features.nb_qm,
                        nb_and: features.nb_and,
                        nb_or: features.nb_or,
                        nb_eq: features.nb_eq,
                        nb_underscore: features.nb_underscore,
                        nb_tilde: features.nb_tilde,
                        nb_percent: features.nb_percent,
                        nb_slash: features.nb_slash,
                        nb_star: features.nb_star,
                        nb_colon: features.nb_colon,
                        nb_comma: features.nb_comma,
                        nb_semicolumn: features.nb_semicolumn,
                        nb_dollar: features.nb_dollar,
                        nb_space: features.nb_space,
                        nb_www: features.nb_www,
                        nb_com: features.nb_com,
                        nb_dslash: features.nb_dslash,
                        http_in_path: features.http_in_path,
                        https_token: features.https_token,
                        ratio_digits_url: features.ratio_digits_url,
                        ratio_digits_host: features.ratio_digits_host,
                        punycode: features.punycode,
                        port: features.port,
                        tld_in_path: features.tld_in_path,
                        tld_in_subdomain: features.tld_in_subdomain,
                        abnormal_subdomain: features.abnormal_subdomain,
                        nb_subdomains: features.nb_subdomains,
                        prefix_suffix: features.prefix_suffix,
                        random_domain: features.random_domain,
                        shortening_service: features.shortening_service,
                        path_extension: features.path_extension,
                        nb_redirection: features.nb_redirection,
                        nb_external_redirection: features.nb_external_redirection,
                        length_words_raw: features.length_words_raw,
                        char_repeat: features.char_repeat,
                        shortest_words_raw: features.shortest_words_raw,
                        shortest_word_host: features.shortest_word_host,
                        shortest_word_path: features.shortest_word_path,
                        longest_words_raw: features.longest_words_raw,
                        longest_word_host: features.longest_word_host,
                        longest_word_path: features.longest_word_path,
                        avg_words_raw: features.avg_words_raw,
                        avg_word_host: features.avg_word_host,
                        avg_word_path: features.avg_word_path,
                        phish_hints: features.phish_hints,
                        domain_in_brand: features.domain_in_brand,
                        brand_in_subdomain: features.brand_in_subdomain,
                        brand_in_path: features.brand_in_path,
                        suspecious_tld: features.suspecious_tld,
                        nb_hyperlinks: features.nb_hyperlinks,
                        ratio_intHyperlinks: features.ratio_intHyperlinks,
                        ratio_extHyperlinks: features.ratio_extHyperlinks,
                        ratio_nullHyperlinks: features.ratio_nullHyperlinks,
                        nb_extCSS: features.nb_extCSS,
                        ratio_intRedirection: features.ratio_intRedirection,
                        ratio_extRedirection: features.ratio_extRedirection,
                        ratio_intErrors: features.ratio_intErrors,
                        ratio_extErrors: features.ratio_extErrors,
                        login_form: features.login_form,
                        external_favicon: features.external_favicon,
                        links_in_tags: features.links_in_tags,
                        submit_email: features.submit_email,
                        ratio_intMedia: features.ratio_intMedia,
                        ratio_extMedia: features.ratio_extMedia,
                        sfh: features.sfh,
                        iframe: features.iframe,
                        popup_window: features.popup_window,
                        safe_anchor: features.safe_anchor,
                        onmouseover: features.onmouseover,
                        right_clic: features.right_clic,
                        empty_title: features.empty_title,
                        domain_in_title: features.domain_in_title,
                        domain_with_copyright: features.domain_with_copyright
                    };

                    Android.receiveFeatures(JSON.stringify(payload));
                } catch (e) {
                    console.error('피처 추출 중 오류:', e);
                    Android.receiveFeatures(JSON.stringify({
                        error: e.message,
                        domNodeCount: 0,
                        iframeCount: 0,
                        externalDomainFormCount: 0,
                        base64ScriptCount: 0,
                        eventListenerCount: 0,
                        suspiciousScriptCount: 0,
                        redirectChainLength: 0,
                        hasLoginForm: false,
                        hasCreditCardForm: false,
                        urlLength: 0,
                        specialCharCount: 0
                    }));
                }
            })();
        """.trimIndent()
    }
}

// 웹페이지 피처 데이터 클래스 (79개 피처를 Map으로 저장)
typealias WebFeatures = Map<String, Float?>

// 논문에서 제안하는 규칙 기반 피싱 탐지 시스템
class PhishingDetector(private val context: Context) {

    private val mlPredictor = TFLitePhishingPredictor(context)

    // 피싱 탐지 규칙들 (논문 기반)
    private val PHISHING_RULES = mapOf(
        "DOM_NODE_THRESHOLD" to 500,      // DOM 노드 수 임계값
        "IFRAME_THRESHOLD" to 3,          // iframe 개수 임계값
        "EXTERNAL_FORM_THRESHOLD" to 2,   // 외부 도메인 form 임계값
        "BASE64_SCRIPT_THRESHOLD" to 1,   // base64 스크립트 임계값
        "EVENT_LISTENER_THRESHOLD" to 50, // 이벤트 리스너 임계값
        "SUSPICIOUS_SCRIPT_THRESHOLD" to 2, // 의심스러운 스크립트 임계값
        "REDIRECT_CHAIN_THRESHOLD" to 5,  // 리다이렉트 체인 임계값
        "URL_LENGTH_THRESHOLD" to 100,    // URL 길이 임계값
        "SPECIAL_CHAR_THRESHOLD" to 20    // 특수문자 수 임계값
    )

    private val phishingThreshold = 0.6
    private val suspiciousUrlKeywords = listOf(
        "login", "verify", "account", "secure", "security", "update",
        "bank", "wallet", "airdrop", "bonus", "gift", "event", "signin",
        "confirm", "billing", "support", "unlock", "reset"
    )
    private val highRiskTopLevelDomains = setOf(
        "xyz", "top", "icu", "zip", "click", "gq", "cf", "ml", "tk",
        "work", "monster", "support", "fit", "cn", "ru", "su"
    )

    // 피싱 여부 판단
    fun isPhishing(features: WebFeatures, url: String? = null, threshold: Double = phishingThreshold): Boolean {
        val result = analyzePhishing(features, url)
        return result.confidenceScore >= threshold
    }

    // ML 기반 통합 판정 시스템 (규칙 기반 제거)
    fun analyzePhishing(features: WebFeatures, url: String? = null): PhishingAnalysisResult {
        // ML 예측 수행 (모든 피처를 ML 모델에 입력)
        val mlPrediction = mlPredictor.predictWithML(features)

        val riskFactors = mutableListOf<String>()
        val urlHeuristics = url?.let { evaluateUrlHeuristics(it) }

        // ML 예측 결과를 기반으로 판정
        val confidenceScore = if (mlPrediction >= 0.0f) {
            mlPrediction.toDouble().coerceIn(0.0, 1.0)
        } else {
            // ML 모델 로드 실패 시 기본값 (안전하게 의심)
            0.5
        }

        val isPhishing = confidenceScore >= phishingThreshold

        // Log which features are null or sentinel for diagnostics
        val nullKeys = features.filter { it.value == null }.map { it.key }
        if (nullKeys.isNotEmpty()) {
            Log.d("WebFeatureExtractor", "NULL(미구현) 피처 목록: ${nullKeys.joinToString(", ")}")
        }

        // 위험 요인 수집 (ML 기반)
        if (mlPrediction >= 0.0f) {
            riskFactors.add("ML 예측 점수: ${(confidenceScore * 100).toInt()}%")
            if (isPhishing) {
                riskFactors.add("ML 모델이 피싱으로 판정")
            } else {
                riskFactors.add("ML 모델이 안전으로 판정")
            }
        } else {
            riskFactors.add("ML 모델 로드 실패 - 기본 판정 사용")
        }

        // URL 기반 위험 요인 추가
        if (urlHeuristics != null) {
            riskFactors.addAll(urlHeuristics.riskFactors)
        }

        return PhishingAnalysisResult(
            isPhishing = isPhishing,
            confidenceScore = confidenceScore,
            riskFactors = riskFactors.distinct(),
            features = features,
            inspectedUrl = url,
            analysisMode = AnalysisMode.FULL
        )
    }

    private fun evaluateUrlHeuristics(url: String): UrlHeuristicResult {
        val normalizedUrl = url.trim()
        val lowerUrl = normalizedUrl.lowercase(Locale.ROOT)
        val uri = runCatching { URI(normalizedUrl) }.getOrNull()

        val rawHost = uri?.host ?: run {
            val stripped = normalizedUrl.substringAfter("://", normalizedUrl)
            stripped.substringBefore('/').substringBefore('?')
        }
        val host = rawHost.lowercase(Locale.ROOT)
        val hostWithoutPort = host.substringBefore(':')
        val scheme = uri?.scheme ?: normalizedUrl.substringBefore("://", "")
        val path = uri?.path ?: ""
        val pathDepth = path.split('/').filter { it.isNotBlank() }.size
        val encodedCharCount = normalizedUrl.count { it == '%' }
        val specialCharCount = normalizedUrl.count { !it.isLetterOrDigit() }
        val urlLength = normalizedUrl.length
        val subdomainCount = countSubdomains(hostWithoutPort)
        val hasIpAddress = hostWithoutPort.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}\$")) ||
            hostWithoutPort.matches(Regex("^[0-9a-fA-F:]+$"))
        val matchedKeyword = suspiciousUrlKeywords.firstOrNull { lowerUrl.contains(it) }
        val hasHighRiskTld = highRiskTopLevelDomains.any { hostWithoutPort.endsWith(".$it") }
        val hasDoubleSlash = normalizedUrl.substringAfter("://", normalizedUrl).contains("//")

        var score = 0.0
        var totalWeight = 0.0
        val riskFactors = mutableListOf<String>()

        fun apply(weight: Double, condition: Boolean, message: () -> String) {
            totalWeight += weight
            if (condition) {
                score += weight
                riskFactors.add(message())
            }
        }

        apply(0.18, urlLength > PHISHING_RULES["URL_LENGTH_THRESHOLD"]!!) {
            "URL이 너무 김 ($urlLength)"
        }

        apply(0.18, specialCharCount > PHISHING_RULES["SPECIAL_CHAR_THRESHOLD"]!!) {
            "특수문자가 많음 ($specialCharCount)"
        }

        apply(0.2, hasIpAddress) {
            "도메인 대신 IP 주소 사용"
        }

        apply(0.1, normalizedUrl.contains("@")) {
            "\'@\' 문자를 포함한 URL"
        }

        apply(0.1, scheme.equals("http", ignoreCase = true)) {
            "HTTPS가 아닌 HTTP 연결"
        }

        apply(0.12, subdomainCount >= 3) {
            "과도한 서브도메인 사용 ($subdomainCount)"
        }

        apply(0.15, matchedKeyword != null) {
            "피싱 의심 키워드 포함 ('$matchedKeyword')"
        }

        apply(0.15, hostWithoutPort.contains("xn--")) {
            "Punycode 도메인 사용"
        }

        apply(0.12, hasHighRiskTld) {
            "위험 TLD 사용 (.${hostWithoutPort.substringAfterLast('.')})"
        }

        apply(0.1, pathDepth >= 4) {
            "URL 경로 깊이가 큼 ($pathDepth 단계)"
        }

        apply(0.1, encodedCharCount > 3) {
            "인코딩 문자(%)가 과다 ($encodedCharCount)"
        }

        apply(0.08, hasDoubleSlash) {
            "이중 '//' 경로 패턴 발견"
        }

        val normalizedScore = if (totalWeight > 0) score / totalWeight else 0.0
        return UrlHeuristicResult(
            score = normalizedScore.coerceIn(0.0, 1.0),
            riskFactors = riskFactors
        )
    }

    private fun combineScores(featureScore: Double, urlScore: Double): Double {
        val feature = featureScore.coerceIn(0.0, 1.0)
        val url = urlScore.coerceIn(0.0, 1.0)
        return 1 - (1 - feature) * (1 - url)
    }

    private fun countSubdomains(host: String): Int {
        if (host.isBlank()) return 0
        val labels = host.split('.').filter { it.isNotBlank() }
        return if (labels.size > 2) labels.size - 2 else 0
    }

    private data class UrlHeuristicResult(
        val score: Double,
        val riskFactors: MutableList<String>
    )
}

enum class AnalysisMode {
    FULL,
    DOM_ONLY,
    URL_ONLY
}

// 피싱 분석 결과 데이터 클래스
data class PhishingAnalysisResult(
    val isPhishing: Boolean,
    val confidenceScore: Double,
    val riskFactors: List<String>,
    val features: WebFeatures?,
    val inspectedUrl: String?,
    val analysisMode: AnalysisMode
)

    // 피싱 탐지 규칙들 (논문 기반)
    private val PHISHING_RULES = mapOf(
        "DOM_NODE_THRESHOLD" to 500,
        "IFRAME_THRESHOLD" to 3,
        "EXTERNAL_FORM_THRESHOLD" to 2,
        "BASE64_SCRIPT_THRESHOLD" to 1,
        "EVENT_LISTENER_THRESHOLD" to 50,
        "SUSPICIOUS_SCRIPT_THRESHOLD" to 2,
        "REDIRECT_CHAIN_THRESHOLD" to 5,
        "URL_LENGTH_THRESHOLD" to 100,
        "SPECIAL_CHAR_THRESHOLD" to 20
    )

    private val phishingThreshold = 0.6
    private val suspiciousUrlKeywords = listOf(
        "login", "verify", "account", "secure", "security", "update",
        "bank", "wallet", "airdrop", "bonus", "gift", "event", "signin",
        "confirm", "billing", "support", "unlock", "reset"
    )
    private val highRiskTopLevelDomains = setOf(
        "xyz", "top", "icu", "zip", "click", "gq", "cf", "ml", "tk",
        "work", "monster", "support", "fit", "cn", "ru", "su"
    )

    fun isPhishing(features: WebFeatures, url: String? = null, threshold: Double = phishingThreshold): Boolean {
        val result = analyzePhishing(features, url)
        return result.confidenceScore >= threshold
    }

    fun analyzePhishing(features: WebFeatures, url: String? = null): PhishingAnalysisResult {
        val mlPrediction = mlPredictor.predictWithML(features)
        val riskFactors = mutableListOf<String>()
        val urlHeuristics = url?.let { evaluateUrlHeuristics(it) }

        val confidenceScore = if (mlPrediction >= 0.0f) {
            mlPrediction.toDouble().coerceIn(0.0, 1.0)
        } else {
            0.5
        }

        val isPhishing = confidenceScore >= phishingThreshold

        val nullKeys = features.filter { it.value == null }.map { it.key }
        if (nullKeys.isNotEmpty()) {
            Log.d("WebFeatureExtractor", "NULL(미구현) 피처 목록: ${nullKeys.joinToString(", ")}")
        }

        if (mlPrediction >= 0.0f) {
            riskFactors.add("ML 예측 점수: ${(confidenceScore * 100).toInt()}%")
            if (isPhishing) {
                riskFactors.add("ML 모델이 피싱으로 판정")
            } else {
                riskFactors.add("ML 모델이 안전으로 판정")
            }
        } else {
            riskFactors.add("ML 모델 로드 실패 - 기본 판정 사용")
        }

        if (urlHeuristics != null) {
            riskFactors.addAll(urlHeuristics.riskFactors)
        }

        return PhishingAnalysisResult(
            isPhishing = isPhishing,
            confidenceScore = confidenceScore,
            riskFactors = riskFactors.distinct(),
            features = features,
            inspectedUrl = url,
            analysisMode = AnalysisMode.FULL
        )
    }

    private fun evaluateUrlHeuristics(url: String): UrlHeuristicResult {
        val normalizedUrl = url.trim()
        val lowerUrl = normalizedUrl.lowercase(Locale.ROOT)
        val uri = runCatching { URI(normalizedUrl) }.getOrNull()

        val rawHost = uri?.host ?: run {
            val stripped = normalizedUrl.substringAfter("://", normalizedUrl)
            stripped.substringBefore('/').substringBefore('?')
        }
        val host = rawHost.lowercase(Locale.ROOT)
        val hostWithoutPort = host.substringBefore(':')
        val scheme = uri?.scheme ?: normalizedUrl.substringBefore("://", "")
        val path = uri?.path ?: ""
        val pathDepth = path.split('/').filter { it.isNotBlank() }.size
        val encodedCharCount = normalizedUrl.count { it == '%' }
        val specialCharCount = normalizedUrl.count { !it.isLetterOrDigit() }
        val urlLength = normalizedUrl.length
        val subdomainCount = countSubdomains(hostWithoutPort)
        val hasIpAddress = hostWithoutPort.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}\$")) ||
            hostWithoutPort.matches(Regex("^[0-9a-fA-F:]+$"))
        val matchedKeyword = suspiciousUrlKeywords.firstOrNull { lowerUrl.contains(it) }
        val hasHighRiskTld = highRiskTopLevelDomains.any { hostWithoutPort.endsWith(".$it") }
        val hasDoubleSlash = normalizedUrl.substringAfter("://", normalizedUrl).contains("//")

        var score = 0.0
        var totalWeight = 0.0
        val riskFactors = mutableListOf<String>()

        fun apply(weight: Double, condition: Boolean, message: () -> String) {
            totalWeight += weight
            if (condition) {
                score += weight
                riskFactors.add(message())
            }
        }

        apply(0.18, urlLength > PHISHING_RULES["URL_LENGTH_THRESHOLD"]!!) {
            "URL이 너무 김 ($urlLength)"
        }
        apply(0.18, specialCharCount > PHISHING_RULES["SPECIAL_CHAR_THRESHOLD"]!!) {
            "특수문자가 많음 ($specialCharCount)"
        }
        apply(0.2, hasIpAddress) { "도메인 대신 IP 주소 사용" }
        apply(0.1, normalizedUrl.contains("@")) { "\'@\' 문자를 포함한 URL" }
        apply(0.1, scheme.equals("http", ignoreCase = true)) { "HTTPS가 아닌 HTTP 연결" }
        apply(0.12, subdomainCount >= 3) { "과도한 서브도메인 사용 ($subdomainCount)" }
        apply(0.15, matchedKeyword != null) { "피싱 의심 키워드 포함 ('$matchedKeyword')" }
        apply(0.15, hostWithoutPort.contains("xn--")) { "Punycode 도메인 사용" }
        apply(0.12, hasHighRiskTld) { "위험 TLD 사용 (.${hostWithoutPort.substringAfterLast('.')})" }
        apply(0.1, pathDepth >= 4) { "URL 경로 깊이가 큼 ($pathDepth 단계)" }
        apply(0.1, encodedCharCount > 3) { "인코딩 문자(%)가 과다 ($encodedCharCount)" }
        apply(0.08, hasDoubleSlash) { "이중 '//' 경로 패턴 발견" }

        val normalizedScore = if (totalWeight > 0) score / totalWeight else 0.0
        return UrlHeuristicResult(
            score = normalizedScore.coerceIn(0.0, 1.0),
            riskFactors = riskFactors
        )
    }

    private fun countSubdomains(host: String): Int {
        if (host.isBlank()) return 0
        val labels = host.split('.').filter { it.isNotBlank() }
        return if (labels.size > 2) labels.size - 2 else 0
    }

    private data class UrlHeuristicResult(
        val score: Double,
        val riskFactors: MutableList<String>
    )
}

enum class AnalysisMode {
    FULL,
    DOM_ONLY,
    URL_ONLY
}

data class PhishingAnalysisResult(
    val isPhishing: Boolean,
    val confidenceScore: Double,
    val riskFactors: List<String>,
    val features: WebFeatures?,
    val inspectedUrl: String?,
    val analysisMode: AnalysisMode
)
