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
                // match the actual feature names produced by the JS extractor / feature_info.json
                append("• URL 길이: ${features["length_url"]?.toInt() ?: 0}\n")
                append("• iframe (invisible?) flag: ${features["iframe"]?.toInt() ?: 0}\n")
                append("• 로그인/외부 폼 (login_form): ${if (features["login_form"] == 1.0f) "있음" else "없음"}\n")
                append("• 외부 파비콘 (external_favicon): ${if (features["external_favicon"] == 1.0f) "있음" else "없음"}\n")
                append("• 하이퍼링크 수 (nb_hyperlinks): ${features["nb_hyperlinks"]?.toInt() ?: 0}\n")
                append("• 외부 CSS 파일 수 (nb_extCSS): ${features["nb_extCSS"]?.toInt() ?: 0}\n")
                append("• 총 리다이렉션 (nb_redirection): ${features["nb_redirection"]?.toInt() ?: 0} / 외부 리다이렉션: ${features["nb_external_redirection"]?.toInt() ?: 0}\n")
                append("• 의심 키워드 수 (phish_hints): ${features["phish_hints"]?.toInt() ?: 0}\n")
                append("• 의심 TLD (suspecious_tld): ${if (features["suspecious_tld"] == 1.0f) "예" else "아니오"}\n")
                append("• 브랜드 포함(domain_in_brand / brand_in_path): ${if (features["domain_in_brand"] == 1.0f) "도메인에 브랜드 있음" else if (features["brand_in_path"] == 1.0f) "경로에 브랜드 있음" else "아님"}\n")
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

