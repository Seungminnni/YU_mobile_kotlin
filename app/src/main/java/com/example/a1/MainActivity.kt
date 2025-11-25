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
    // dynamic, runtime counter to capture actual WebView redirect behaviour
    private var dynamicTotalRedirects: Int = 0
    private var lastNavigationUrlForDynamicCounters: String? = null
    private var pendingDetectedUrl: String? = null
    private var lastDisplayedUrl: String? = null
    private var imageCapture: ImageCapture? = null
    private var isWebViewVisible = false
    private var lastAnalyzedPageKey: String? = null
    private var isAnalyzingFeatures = false
    private var lastWarningShownForUrl: String? = null
    private lateinit var phishingDetector: PhishingDetector
    private val uciFeatureSummaryOrder = listOf(
        "having_IP_Address",
        "URL_Length",
        "Shortining_Service",
        "having_At_Symbol",
        "double_slash_redirecting",
        "Prefix_Suffix",
        "having_Sub_Domain",
        "SSLfinal_State",
        "Favicon",
        "port",
        "HTTPS_token",
        "Request_URL",
        "URL_of_Anchor",
        "Links_in_tags",
        "SFH",
        "Submitting_to_email",
        "Redirect",
        "on_mouseover",
        "RightClick",
        "popUpWidnow",
        "Iframe"
    )

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

    private fun appendUciFeatureSummary(builder: StringBuilder, features: WebFeatures) {
        uciFeatureSummaryOrder.forEach { key ->
            builder.append("• ${describeUciFeatureValue(key, features[key])}\n")
        }
    }

    private fun describeUciFeatureValue(key: String, raw: Float?): String {
        val label = when (key) {
            "having_IP_Address" -> "IP 주소 사용"
            "URL_Length" -> "URL 길이"
            "Shortining_Service" -> "단축 URL"
            "having_At_Symbol" -> "@ 문자"
            "double_slash_redirecting" -> "이중 //"
            "Prefix_Suffix" -> "도메인 '-'"
            "having_Sub_Domain" -> "서브도메인"
            "SSLfinal_State" -> "SSL 상태"
            "Favicon" -> "파비콘 출처"
            "port" -> "포트"
            "HTTPS_token" -> "도메인 내 'https'"
            "Request_URL" -> "외부 리소스"
            "URL_of_Anchor" -> "외부 앵커"
            "Links_in_tags" -> "태그 내 외부 링크"
            "SFH" -> "폼 핸들러"
            "Submitting_to_email" -> "메일 제출"
            "Redirect" -> "리다이렉트"
            "on_mouseover" -> "마우스오버 이벤트"
            "RightClick" -> "우클릭 차단"
            "popUpWidnow" -> "팝업"
            "Iframe" -> "iframe"
            else -> key
        }
        val value = raw ?: return "$label: 측정 실패"
        return when (key) {
            "having_IP_Address" -> if (value <= 0f) "$label: URL이 IP 형식" else "$label: 도메인 사용"
            "URL_Length" -> when (value.toInt()) {
                1 -> "$label: 54자 미만"
                0 -> "$label: 54~75자"
                -1 -> "$label: 75자 초과"
                else -> "$label: ${value.toInt()}"
            }
            "Shortining_Service" -> if (value <= 0f) "$label: 사용" else "$label: 미사용"
            "having_At_Symbol" -> if (value <= 0f) "$label: 포함" else "$label: 없음"
            "double_slash_redirecting" -> if (value <= 0f) "$label: 경로에 존재" else "$label: 없음"
            "Prefix_Suffix" -> if (value <= 0f) "$label: 포함" else "$label: 없음"
            "having_Sub_Domain" -> when (value.toInt()) {
                1 -> "$label: 0-1개"
                0 -> "$label: 2개"
                -1 -> "$label: 3개 이상"
                else -> "$label: ${value.toInt()}"
            }
            "SSLfinal_State" -> when (value.toInt()) {
                1 -> "$label: HTTPS"
                0 -> "$label: 확인 불가"
                -1 -> "$label: HTTP"
                else -> "$label: ${value.toInt()}"
            }
            "Favicon" -> if (value <= 0f) "$label: 외부" else "$label: 내부/없음"
            "port" -> if (value <= 0f) "$label: 비표준" else "$label: 80/443"
            "HTTPS_token" -> if (value <= 0f) "$label: 포함" else "$label: 없음"
            "Request_URL" -> when (value.toInt()) {
                1 -> "$label: 외부 <22%"
                0 -> "$label: 외부 22~61%"
                -1 -> "$label: 외부 >61%"
                else -> "$label: ${value.toInt()}"
            }
            "URL_of_Anchor" -> when (value.toInt()) {
                1 -> "$label: 외부 <31%"
                0 -> "$label: 외부 31~67%"
                -1 -> "$label: 외부 >67%"
                else -> "$label: ${value.toInt()}"
            }
            "Links_in_tags" -> when (value.toInt()) {
                1 -> "$label: 외부 <17%"
                0 -> "$label: 외부 17~81%"
                -1 -> "$label: 외부 >81%"
                else -> "$label: ${value.toInt()}"
            }
            "SFH" -> when (value.toInt()) {
                1 -> "$label: 안전"
                0 -> "$label: 일부 의심"
                -1 -> "$label: 의심"
                else -> "$label: ${value.toInt()}"
            }
            "Submitting_to_email" -> if (value <= 0f) "$label: 있음" else "$label: 없음"
            "Redirect" -> if (value <= 0f) "$label: 2회 이상" else "$label: 0-1회"
            "on_mouseover" -> if (value <= 0f) "$label: 사용" else "$label: 없음"
            "RightClick" -> if (value <= 0f) "$label: 차단" else "$label: 허용"
            "popUpWidnow" -> if (value <= 0f) "$label: 있음" else "$label: 없음"
            "Iframe" -> if (value <= 0f) "$label: 포함" else "$label: 없음"
            else -> "$label: ${value.toInt()}"
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
                        if (!prev.isNullOrBlank() && prev != url) {
                            dynamicTotalRedirects += 1
                        }
                        lastNavigationUrlForDynamicCounters = url
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "dynamic-redirect-counter error", e)
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

        // reset dynamic counters for this session so we accurately capture redirects
        dynamicTotalRedirects = 0
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
        val merged = features.toMutableMap()
        val nullCount = merged.count { it.value == null }
        if (nullCount > 0) {
            val message = "❌ 피처 추출 실패 (${nullCount}개 null) - 다시 시도해주세요"
            Log.w(TAG, message)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            resultTextView.text = message
            isAnalyzingFeatures = false
            return
        }
        Log.d(TAG, "dynamic redirects observed=$dynamicTotalRedirects")
        try {
            val dynamicRedirectValue = if (dynamicTotalRedirects > 1) -1f else 0f
            if (dynamicTotalRedirects > 1 || merged["Redirect"] == null) {
                merged["Redirect"] = dynamicRedirectValue
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to merge dynamic redirect counter", e)
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
                append("\n📋 UCI 피처 분석:\n")
                appendUciFeatureSummary(this, features)
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
        private const val DEBUG_AUTO_LAUNCH_URL = "https://www.progarchives.com/album.asp?id=61737"
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

    fun getFeatureExtractionScript(): String {
        return """
            javascript:(function() {
                try {
                    // ===== UCI Phishing Dataset 21개 피처 추출 =====
                    // 모든 피처는 -1 (피싱 의심), 0 (애매), 1 (안전) 값을 가집니다
                    
                    var url = window.location.href;
                    var hostname = window.location.hostname;
                    var protocol = window.location.protocol;
                    var pathname = window.location.pathname;
                    
                    // 유틸리티 함수들
                    function isIP(host) {
                        // IPv4 패턴
                        var ipv4Pattern = /^(\d{1,3}\.){3}\d{1,3}$/;
                        // IPv6 패턴 (간단한 버전)
                        var ipv6Pattern = /^[0-9a-fA-F:]+$/;
                        return ipv4Pattern.test(host) || (host.includes(':') && ipv6Pattern.test(host));
                    }
                    
                    var shortenerHosts = [
                        'bit.ly', 'tinyurl.com', 't.co', 'goo.gl', 'ow.ly', 
                        'is.gd', 's.id', 'rebrand.ly', 'buff.ly', 'cutt.ly', 
                        'lnkd.in', 'short.link', 'tiny.cc'
                    ];
                    
                    function normalizeUrl(raw) {
                        try {
                            return new URL(raw, window.location.href);
                        } catch (e) {
                            return null;
                        }
                    }
                    
                    // UCI 21개 피처 계산
                    var features = {};
                    
                    // 1. having_IP_Address: URL에 IP 주소 사용 (-1: IP 사용, 1: 도메인 사용)
                    features.having_IP_Address = isIP(hostname) ? -1 : 1;
                    
                    // 2. URL_Length: URL 길이 (-1: >75, 0: 54-75, 1: <54)
                    if (url.length < 54) {
                        features.URL_Length = 1;
                    } else if (url.length <= 75) {
                        features.URL_Length = 0;
                    } else {
                        features.URL_Length = -1;
                    }
                    
                    // 3. Shortining_Service: URL 단축 서비스 사용 (-1: 사용, 1: 미사용)
                    features.Shortining_Service = shortenerHosts.includes(hostname.toLowerCase()) ? -1 : 1;
                    
                    // 4. having_At_Symbol: URL에 @ 기호 포함 (-1: 포함, 1: 미포함)
                    features.having_At_Symbol = url.includes('@') ? -1 : 1;
                    
                    // 5. double_slash_redirecting: '//' 리다이렉션 (-1: 존재, 1: 없음)
                    // 프로토콜 이후의 '//' 확인
                    var afterProtocol = url.substring(url.indexOf('://') + 3);
                    features.double_slash_redirecting = afterProtocol.includes('//') ? -1 : 1;
                    
                    // 6. Prefix_Suffix: 도메인에 '-' 기호 (-1: 존재, 1: 없음)
                    features.Prefix_Suffix = hostname.includes('-') ? -1 : 1;
                    
                    // 7. having_Sub_Domain: 서브도메인 개수 (-1: >=3, 0: 2, 1: 0-1)
                    var dots = (hostname.match(/\./g) || []).length;
                    if (dots < 2) {
                        features.having_Sub_Domain = 1;
                    } else if (dots == 2) {
                        features.having_Sub_Domain = 0;
                    } else {
                        features.having_Sub_Domain = -1;
                    }
                    
                    // 8. SSLfinal_State: HTTPS 및 SSL 상태 (-1: 신뢰할 수 없음, 0: 애매, 1: 신뢰)
                    // 간단 구현: HTTPS 사용 여부 + 인증서 체크 불가능하므로 기본적으로 HTTPS면 1
                    if (protocol === 'https:') {
                        features.SSLfinal_State = 1;
                    } else if (protocol === 'http:') {
                        features.SSLfinal_State = -1;
                    } else {
                        features.SSLfinal_State = 0;
                    }
                    
                    // 9. Favicon: 파비콘이 외부 도메인에서 로드되는지 (-1: 외부, 1: 내부 또는 없음)
                    var faviconLinks = document.querySelectorAll('link[rel*="icon"]');
                    var externalFavicon = false;
                    for (var i = 0; i < faviconLinks.length; i++) {
                        var href = faviconLinks[i].getAttribute('href');
                        if (href && href.startsWith('http')) {
                            var favUrl = normalizeUrl(href);
                            if (favUrl && favUrl.hostname !== hostname) {
                                externalFavicon = true;
                                break;
                            }
                        }
                    }
                    features.Favicon = externalFavicon ? -1 : 1;
                    
                    // 10. port: 비표준 포트 사용 (-1: 사용, 1: 미사용)
                    var port = window.location.port;
                    features.port = (port && port !== '80' && port !== '443') ? -1 : 1;
                    
                    // 11. HTTPS_token: URL에 'https' 토큰이 도메인 이름에 포함 (-1: 포함, 1: 미포함)
                    // 프로토콜을 제외한 부분에서 'https' 문자열 확인
                    var domainPart = url.substring(url.indexOf('://') + 3).split('/')[0];
                    features.HTTPS_token = domainPart.toLowerCase().includes('https') ? -1 : 1;
                    
                    // 12. Request_URL: 외부 리소스 비율 (-1: >61%, 0: 22-61%, 1: <22%)
                    var imgs = document.querySelectorAll('img, video, audio, source');
                    var totalResources = imgs.length;
                    var externalResources = 0;
                    for (var i = 0; i < imgs.length; i++) {
                        var src = imgs[i].getAttribute('src') || imgs[i].getAttribute('data-src');
                        if (src && src.startsWith('http')) {
                            var resUrl = normalizeUrl(src);
                            if (resUrl && resUrl.hostname !== hostname) {
                                externalResources++;
                            }
                        }
                    }
                    var externalRatio = totalResources > 0 ? (externalResources / totalResources) : 0;
                    if (externalRatio < 0.22) {
                        features.Request_URL = 1;
                    } else if (externalRatio <= 0.61) {
                        features.Request_URL = 0;
                    } else {
                        features.Request_URL = -1;
                    }
                    
                    // 13. URL_of_Anchor: 외부 앵커 비율 (-1: >67%, 0: 31-67%, 1: <31%)
                    var anchors = document.querySelectorAll('a[href]');
                    var totalAnchors = anchors.length;
                    var externalAnchors = 0;
                    for (var i = 0; i < anchors.length; i++) {
                        var href = anchors[i].getAttribute('href');
                        if (href && (href.startsWith('http://') || href.startsWith('https://'))) {
                            var anchorUrl = normalizeUrl(href);
                            if (anchorUrl && anchorUrl.hostname !== hostname) {
                                externalAnchors++;
                            }
                        }
                    }
                    var anchorRatio = totalAnchors > 0 ? (externalAnchors / totalAnchors) : 0;
                    if (anchorRatio < 0.31) {
                        features.URL_of_Anchor = 1;
                    } else if (anchorRatio <= 0.67) {
                        features.URL_of_Anchor = 0;
                    } else {
                        features.URL_of_Anchor = -1;
                    }
                    
                    // 14. Links_in_tags: <meta>, <script>, <link> 태그 내 외부 링크 비율 (-1: >81%, 0: 17-81%, 1: <17%)
                    var metaLinks = document.querySelectorAll('meta[content], script[src], link[href]');
                    var totalMetaLinks = 0;
                    var externalMetaLinks = 0;
                    for (var i = 0; i < metaLinks.length; i++) {
                        var el = metaLinks[i];
                        var linkVal = el.getAttribute('content') || el.getAttribute('src') || el.getAttribute('href');
                        if (linkVal && (linkVal.startsWith('http://') || linkVal.startsWith('https://'))) {
                            totalMetaLinks++;
                            var metaUrl = normalizeUrl(linkVal);
                            if (metaUrl && metaUrl.hostname !== hostname) {
                                externalMetaLinks++;
                            }
                        }
                    }
                    var metaRatio = totalMetaLinks > 0 ? (externalMetaLinks / totalMetaLinks) : 0;
                    if (metaRatio < 0.17) {
                        features.Links_in_tags = 1;
                    } else if (metaRatio <= 0.81) {
                        features.Links_in_tags = 0;
                    } else {
                        features.Links_in_tags = -1;
                    }
                    
                    // 15. SFH (Server Form Handler): Form action이 비어있거나 about:blank 또는 외부 도메인 (-1: 의심, 0: 애매, 1: 안전)
                    var forms = document.getElementsByTagName('form');
                    var suspiciousForms = 0;
                    for (var i = 0; i < forms.length; i++) {
                        var action = forms[i].getAttribute('action');
                        if (!action || action === '' || action === 'about:blank' || action === '#') {
                            suspiciousForms++;
                        } else if (action.startsWith('http')) {
                            var formUrl = normalizeUrl(action);
                            if (formUrl && formUrl.hostname !== hostname) {
                                suspiciousForms++;
                            }
                        }
                    }
                    if (forms.length === 0) {
                        features.SFH = 1;
                    } else {
                        var formRatio = suspiciousForms / forms.length;
                        if (formRatio > 0.5) {
                            features.SFH = -1;
                        } else if (formRatio > 0) {
                            features.SFH = 0;
                        } else {
                            features.SFH = 1;
                        }
                    }
                    
                    // 16. Submitting_to_email: Form이 이메일로 제출되는지 (-1: 사용, 1: 미사용)
                    var emailSubmit = false;
                    for (var i = 0; i < forms.length; i++) {
                        var action = forms[i].getAttribute('action');
                        if (action && action.includes('mailto:')) {
                            emailSubmit = true;
                            break;
                        }
                    }
                    features.Submitting_to_email = emailSubmit ? -1 : 1;
                    
                    // 17. Redirect: 리다이렉트 횟수 (0: <=1, -1: >1)
                    var redirectCount = 0;
                    try {
                        if (window.performance && window.performance.getEntriesByType) {
                            var navEntries = window.performance.getEntriesByType('navigation');
                            if (navEntries && navEntries.length > 0 && typeof navEntries[0].redirectCount === 'number') {
                                redirectCount = navEntries[0].redirectCount;
                            } else if (window.performance.navigation && typeof window.performance.navigation.redirectCount === 'number') {
                                redirectCount = window.performance.navigation.redirectCount;
                            }
                        }
                    } catch (e) {}
                    features.Redirect = redirectCount <= 1 ? 0 : -1;
                    
                    // 18. on_mouseover: onMouseOver 이벤트로 상태 변경 (-1: 사용, 1: 미사용)
                    var hasOnMouseOver = document.querySelectorAll('[onmouseover]').length > 0;
                    features.on_mouseover = hasOnMouseOver ? -1 : 1;
                    
                    // 19. RightClick: 우클릭 비활성화 (-1: 비활성화, 1: 정상)
                    var rightClickDisabled = false;
                    if (document.body && document.body.oncontextmenu !== null) {
                        rightClickDisabled = true;
                    }
                    if (document.querySelectorAll('[oncontextmenu]').length > 0) {
                        rightClickDisabled = true;
                    }
                    features.RightClick = rightClickDisabled ? -1 : 1;
                    
                    // 20. popUpWidnow: 팝업 윈도우 사용 (-1: 사용, 1: 미사용)
                    var hasPopup = false;
                    var allAnchors = document.getElementsByTagName('a');
                    for (var i = 0; i < allAnchors.length; i++) {
                        var target = allAnchors[i].getAttribute('target');
                        var onclick = allAnchors[i].getAttribute('onclick') || '';
                        if (target === '_blank' || onclick.includes('window.open')) {
                            hasPopup = true;
                            break;
                        }
                    }
                    features.popUpWidnow = hasPopup ? -1 : 1;
                    
                    // 21. Iframe: iframe 사용 (-1: 사용, 1: 미사용)
                    var iframeCount = document.getElementsByTagName('iframe').length;
                    features.Iframe = iframeCount > 0 ? -1 : 1;
                    
                    // Android로 21개 피처 전송
                    var payload = {
                        having_IP_Address: features.having_IP_Address,
                        URL_Length: features.URL_Length,
                        Shortining_Service: features.Shortining_Service,
                        having_At_Symbol: features.having_At_Symbol,
                        double_slash_redirecting: features.double_slash_redirecting,
                        Prefix_Suffix: features.Prefix_Suffix,
                        having_Sub_Domain: features.having_Sub_Domain,
                        SSLfinal_State: features.SSLfinal_State,
                        Favicon: features.Favicon,
                        port: features.port,
                        HTTPS_token: features.HTTPS_token,
                        Request_URL: features.Request_URL,
                        URL_of_Anchor: features.URL_of_Anchor,
                        Links_in_tags: features.Links_in_tags,
                        SFH: features.SFH,
                        Submitting_to_email: features.Submitting_to_email,
                        Redirect: features.Redirect,
                        on_mouseover: features.on_mouseover,
                        RightClick: features.RightClick,
                        popUpWidnow: features.popUpWidnow,
                        Iframe: features.Iframe
                    };

                    Android.receiveFeatures(JSON.stringify(payload));
                } catch (e) {
                    console.error('피처 추출 중 오류:', e);
                    // 오류 시 null 값 전송하여 Kotlin에서 실패로 처리하도록 함
                    Android.receiveFeatures(JSON.stringify({
                        error: e.message,
                        having_IP_Address: null,
                        URL_Length: null,
                        Shortining_Service: null,
                        having_At_Symbol: null,
                        double_slash_redirecting: null,
                        Prefix_Suffix: null,
                        having_Sub_Domain: null,
                        SSLfinal_State: null,
                        Favicon: null,
                        port: null,
                        HTTPS_token: null,
                        Request_URL: null,
                        URL_of_Anchor: null,
                        Links_in_tags: null,
                        SFH: null,
                        Submitting_to_email: null,
                        Redirect: null,
                        on_mouseover: null,
                        RightClick: null,
                        popUpWidnow: null,
                        Iframe: null
                    }));
                }
            })();
        """.trimIndent()
    }
}

// 웹페이지 피처 데이터 클래스 (UCI 피처를 Map으로 저장)
typealias WebFeatures = Map<String, Float?>

// 논문에서 제안하는 규칙 기반 피싱 탐지 시스템
class PhishingDetector(private val context: Context) {

    private val mlPredictor = TFLitePhishingPredictor(context)

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
        val featureHeuristics = evaluateDatasetFeatureHeuristics(features)

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
        riskFactors.addAll(featureHeuristics)

        return PhishingAnalysisResult(
            isPhishing = isPhishing,
            confidenceScore = confidenceScore,
            riskFactors = riskFactors.distinct(),
            features = features,
            inspectedUrl = url,
            analysisMode = AnalysisMode.FULL
        )
    }

    private fun evaluateDatasetFeatureHeuristics(features: WebFeatures): List<String> {
        if (features.isEmpty()) return emptyList()
        val insights = mutableListOf<String>()
        fun hasValue(key: String, expected: Float) = features[key]?.let { it == expected } ?: false

        if (hasValue("having_IP_Address", -1f)) {
            insights.add("URL이 도메인 대신 IP 주소를 사용")
        }
        if (hasValue("URL_Length", -1f)) {
            insights.add("URL 길이가 75자를 초과")
        }
        if (hasValue("Shortining_Service", -1f)) {
            insights.add("단축 URL 서비스 사용")
        }
        if (hasValue("having_At_Symbol", -1f)) {
            insights.add("URL에 '@' 문자가 포함")
        }
        if (hasValue("double_slash_redirecting", -1f)) {
            insights.add("이중 '//' 리다이렉션 패턴 발견")
        }
        if (hasValue("Prefix_Suffix", -1f)) {
            insights.add("도메인에 '-' 문자가 포함")
        }
        if (hasValue("having_Sub_Domain", -1f)) {
            insights.add("서브도메인이 3개 이상으로 과도")
        }
        if (hasValue("SSLfinal_State", -1f)) {
            insights.add("HTTPS 대신 HTTP 연결")
        } else if (hasValue("SSLfinal_State", 0f)) {
            insights.add("SSL 인증서 상태를 확인할 수 없음")
        }
        if (hasValue("Favicon", -1f)) {
            insights.add("파비콘이 외부 도메인에서 로드됨")
        }
        if (hasValue("port", -1f)) {
            insights.add("비표준 포트를 사용")
        }
        if (hasValue("HTTPS_token", -1f)) {
            insights.add("도메인에 'https' 문자열 포함")
        }
        if (hasValue("Request_URL", -1f)) {
            insights.add("정적 리소스 중 외부 도메인이 61% 이상")
        } else if (hasValue("Request_URL", 0f)) {
            insights.add("정적 리소스 중 외부 도메인이 22~61%")
        }
        if (hasValue("URL_of_Anchor", -1f)) {
            insights.add("앵커 링크 대부분이 외부 도메인")
        } else if (hasValue("URL_of_Anchor", 0f)) {
            insights.add("앵커 링크 중 외부 도메인이 많음")
        }
        if (hasValue("Links_in_tags", -1f)) {
            insights.add("메타/스크립트 태그가 외부 링크를 과다 사용")
        } else if (hasValue("Links_in_tags", 0f)) {
            insights.add("태그 내 외부 링크 비중이 높음")
        }
        if (hasValue("SFH", -1f)) {
            insights.add("form action이 비어있거나 외부 도메인")
        } else if (hasValue("SFH", 0f)) {
            insights.add("일부 form action이 불완전")
        }
        if (hasValue("Submitting_to_email", -1f)) {
            insights.add("입력값을 이메일로 전송하도록 구성됨")
        }
        if (hasValue("Redirect", -1f)) {
            insights.add("리다이렉트가 2회 이상 발생")
        }
        if (hasValue("on_mouseover", -1f)) {
            insights.add("마우스오버 이벤트로 상태를 변경")
        }
        if (hasValue("RightClick", -1f)) {
            insights.add("우클릭이 비활성화되어 있음")
        }
        if (hasValue("popUpWidnow", -1f)) {
            insights.add("팝업 창을 사용")
        }
        if (hasValue("Iframe", -1f)) {
            insights.add("iframe이 포함됨")
        }
        return insights
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

        apply(0.18, urlLength > 100) {
            "URL이 너무 김 ($urlLength)"
        }

        apply(0.18, specialCharCount > 20) {
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
