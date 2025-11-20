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
        val analysisResult = phishingDetector.analyzePhishing(features, currentUrl)
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
        private const val DEBUG_AUTO_LAUNCH_URL = "https://www.naver.com"
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

                    // 87개 피처 계산 (가능한 것만 구현, 어려운 것은 0)
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
                    features.nb_or = (url.match(/\|/g) || []).length; // | 문자
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
                    features.nb_www = (url.match(/www/g) || []).length;
                    features.nb_com = (url.match(/\.com/g) || []).length; // 이진이 아닌데 이진으로 보고있음
                    features.nb_dslash = (url.match(/\/\//g) || []).length;
                    features.http_in_path = (url.match(/http/g) || []).length;
                    features.https_token = (url.match(/https/g) || []).length;
                    features.ratio_digits_url = (url.match(/\d/g) || []).length / url.length;
                    features.ratio_digits_host = (window.location.hostname.match(/\d/g) || []).length / window.location.hostname.length;
                    features.punycode = window.location.hostname.includes('xn--') ? 1 : 0;
                    features.port = window.location.port ? 1 : 0;
                    features.tld_in_path = null; // 구현 어려움
                    features.tld_in_subdomain = null; // 구현 어려움
                    features.abnormal_subdomain = null; // 구현 어려움
                    features.nb_subdomains = window.location.hostname.split('.').length - 2;
                    features.prefix_suffix = (window.location.hostname.match(/-/g) || []).length;
                    features.random_domain = null; // 구현 어려움
                    features.shortening_service = null; // 구현 어려움
                    features.path_extension = null; // 구현 어려움
                    features.nb_redirection = null; // 구현 어려움
                    features.nb_external_redirection = null; // 구현 어려움

                    // 페이지 콘텐츠 기반  !!삼항연산자 혹은 조건문으로 디버ㅗ깅을 해야함
                    features.length_words_raw = url.split(/[^a-zA-Z0-9]/).filter(w => w).length;
                    features.char_repeat = null; // 구현 어려움
                    features.shortest_words_raw = Math.min(...url.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.shortest_word_host = Math.min(...window.location.hostname.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.shortest_word_path = Math.min(...window.location.pathname.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.longest_words_raw = Math.max(...url.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.longest_word_host = Math.max(...window.location.hostname.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.longest_word_path = Math.max(...window.location.pathname.split(/[^a-zA-Z0-9]/).filter(w => w).map(w => w.length)) || 0;
                    features.avg_words_raw = url.split(/[^a-zA-Z0-9]/).filter(w => w).reduce((a, b) => a + b.length, 0) / url.split(/[^a-zA-Z0-9]/).filter(w => w).length || 0;
                    features.avg_word_host = window.location.hostname.split(/[^a-zA-Z0-9]/).filter(w => w).reduce((a, b) => a + b.length, 0) / window.location.hostname.split(/[^a-zA-Z0-9]/).filter(w => w).length || 0;
                    features.avg_word_path = window.location.pathname.split(/[^a-zA-Z0-9]/).filter(w => w).reduce((a, b) => a + b.length, 0) / window.location.pathname.split(/[^a-zA-Z0-9]/).filter(w => w).length || 0;
                    features.phish_hints = null; // 구현 어려움 삼항연산자 혹은 조건문으로 디버ㅗ깅을 해야함
                    features.domain_in_brand = null; // 구현 어려움
                    features.brand_in_subdomain = null; // 구현 어려움
                    features.brand_in_path = null; // 구현 어려움
                    features.suspecious_tld = ['xyz', 'top', 'icu'].includes(window.location.hostname.split('.').pop()) ? 1 : 0;
                    features.statistical_report = null; // 구현 어려움
                    features.nb_hyperlinks = document.getElementsByTagName('a').length;
                    features.ratio_intHyperlinks = null; // 구현 어려움
                    features.ratio_extHyperlinks = null; // 구현 어려움
                    features.ratio_nullHyperlinks = null; // 구현 어려움
                    features.nb_extCSS = document.querySelectorAll('link[rel="stylesheet"]').length;
                    features.ratio_intRedirection = null; // 구현 어려움
                    features.ratio_extRedirection = null; // 구현 어려움
                    features.ratio_intErrors = null; // 구현 어려움
                    features.ratio_extErrors = null; // 구현 어려움
                    features.login_form = hasLoginForm ? 1 : 0;
                    features.external_favicon = document.querySelector('link[rel="icon"][href^="http"]') ? 1 : 0;
                    features.links_in_tags = null; // 구현 어려움
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
                    features.ratio_intMedia = null; // 구현 어려움
                    features.ratio_extMedia = null; // 구현 어려움
                    features.sfh = null; // 구현 어려움
                    features.iframe = iframeCount;
                    features.popup_window = null; // 구현 어려움
                    features.safe_anchor = null; // 구현 어려움
                    features.onmouseover = null; // 구현 어려움
                    features.right_clic = null; // 구현 어려움
                    features.empty_title = document.title.trim() === '' ? 1 : 0;
                    features.domain_in_title = document.title.includes(window.location.hostname) ? 1 : 0;
                    features.domain_with_copyright = document.body.innerText.includes('©') && document.body.innerText.includes(window.location.hostname) ? 1 : 0;
                    features.whois_registered_domain = null; // 외부 필요
                    features.domain_registration_length = null; // 외부 필요
                    features.domain_age = null; // 외부 필요
                    features.web_traffic = null; // 외부 필요
                    features.dns_record = null; // 외부 필요
                    features.google_index = null; // 외부 필요
                    features.page_rank = null; // 외부 필요

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

                    // Android로 데이터 전송
                    Android.receiveFeatures(JSON.stringify(features));
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

// 웹페이지 피처 데이터 클래스 (87개 피처를 Map으로 저장)
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
