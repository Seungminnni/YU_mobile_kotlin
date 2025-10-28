package com.example.a1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.Toolbar
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var previewView: PreviewView
    private lateinit var resultTextView: TextView
    private lateinit var webView: WebView
    private lateinit var toggleButton: Button

    private var currentUrl: String? = null
    private var isWebViewVisible = false
    private var lastWarningShownForUrl: String? = null
    private lateinit var phishingDetector: PhishingDetector

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
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
        toggleButton = findViewById(R.id.toggleButton)

        setupWebView()

        // 피싱 탐지 모듈 초기화
        phishingDetector = PhishingDetector()

        // 토글 버튼 클릭 리스너
        toggleButton.setOnClickListener {
            toggleView()
        }

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                if (webView.settings.javaScriptEnabled) {
                    resultTextView.text = "🔍 가상환경에서 피처 분석 중..."
                    extractWebFeatures()
                } else {
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

    private fun toggleView() {
        if (isWebViewVisible) {
            // WebView에서 카메라 뷰로 전환
            webView.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            toggleButton.text = "웹뷰로 전환"
            resultTextView.text = currentUrl ?: "QR 코드 결과를 여기에 표시합니다"
        } else {
            // 카메라 뷰에서 WebView로 전환
            if (currentUrl != null) {
                showVirtualEnvironmentWarning(currentUrl!!)
            } else {
                Toast.makeText(this, "먼저 QR 코드를 스캔해주세요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVirtualEnvironmentWarning(url: String) {
        AlertDialog.Builder(this)
            .setTitle("🚨 가상환경 보안 경고")
            .setMessage("""
                이 QR 코드는 다음 URL로 연결됩니다:
                $url

                🔒 가상환경에서 실행됩니다:
                • JavaScript가 기본적으로 비활성화됨
                • 외부 리소스 접근이 제한됨
                • 파일 시스템 접근이 차단됨
                • 위치 정보 접근이 비활성화됨

                ⚠️  주의사항:
                • 알려지지 않은 출처의 QR 코드는 위험할 수 있습니다
                • 개인정보를 입력하지 마세요
                • 의심스러운 링크는 피하세요

                JavaScript를 활성화하시겠습니까?
            """.trimIndent())
            .setPositiveButton("JavaScript 활성화") { dialog: android.content.DialogInterface?, which: Int ->
                enableJavaScriptAndLoad(url)
            }
            .setNegativeButton("보안 모드로 진행") { dialog: android.content.DialogInterface?, which: Int ->
                loadInSecureMode(url)
            }
            .setNeutralButton("취소") { dialog: android.content.DialogInterface?, which: Int ->
                dialog!!.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun enableJavaScriptAndLoad(url: String) {
        // WebView 표시 설정
        previewView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        toggleButton.text = "카메라로 전환"
        isWebViewVisible = true

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        resultTextView.text = "⚠️ JavaScript가 활성화된 가상환경에서 로드 중..."
        webView.loadUrl(url)
    }

    private fun loadInSecureMode(url: String) {
        // WebView 표시 설정
        previewView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        toggleButton.text = "카메라로 전환"
        isWebViewVisible = true

        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
        renderAnalysis(phishingDetector.analyzeUrlOnly(url))
        webView.loadUrl(url)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, BarcodeAnalyzer())
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "카메라 시작 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val rawValue = barcode.rawValue
                            runOnUiThread {
                                currentUrl = rawValue
                                if (rawValue != null && isValidUrl(rawValue)) {
                                    val quickAnalysis = phishingDetector.analyzeUrlOnly(rawValue)
                                    renderAnalysis(quickAnalysis, allowModal = false)
                                    if (!isWebViewVisible) {
                                        resultTextView.append(
                                            "\n\n🔒 가상환경에서 검증하려면 '웹뷰로 전환' 버튼을 눌러주세요."
                                        )
                                    }
                                    toggleButton.visibility = View.VISIBLE
                                    toggleButton.text = "🔒 가상환경에서 열기"
                                } else {
                                    resultTextView.text = "📄 QR 코드 결과: $rawValue"
                                    toggleButton.visibility = View.GONE
                                }
                                Toast.makeText(this@MainActivity, "QR 코드 인식됨", Toast.LENGTH_SHORT).show()
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

    override fun onBackPressed() {
        if (isWebViewVisible && webView.canGoBack()) {
            webView.goBack()
        } else if (isWebViewVisible) {
            toggleView() // WebView에서 카메라 뷰로 돌아가기
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    private fun extractWebFeatures() {
        val extractor = WebFeatureExtractor { features ->
            runOnUiThread {
                analyzeAndDisplayPhishingResult(features)
            }
        }
        webView.evaluateJavascript(extractor.getFeatureExtractionScript(), null)
    }

    private fun analyzeAndDisplayPhishingResult(features: WebFeatures) {
        val analysisResult = phishingDetector.analyzePhishing(features, currentUrl)
        renderAnalysis(analysisResult)
    }

    private fun renderAnalysis(analysisResult: PhishingAnalysisResult, allowModal: Boolean = true) {
        val modeDescription = when (analysisResult.analysisMode) {
            AnalysisMode.FULL -> "DOM + URL 결합 분석"
            AnalysisMode.DOM_ONLY -> "DOM 기반 분석"
            AnalysisMode.URL_ONLY -> "URL 기반 간소 분석"
        }
        val targetUrl = analysisResult.inspectedUrl ?: currentUrl

        val resultText = StringBuilder().apply {
            append("🔍 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("📊 신뢰도 점수: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n")
            append("🎯 판정 결과: ${if (analysisResult.isPhishing) "🚨 피싱 의심" else "✅ 안전"}\n")
            append("🧪 분석 모드: $modeDescription\n")
            targetUrl?.let {
                append("🌐 분석 URL: $it\n")
            }

            if (analysisResult.analysisMode == AnalysisMode.URL_ONLY) {
                append("\nℹ️ JavaScript 비활성화로 인해 DOM 기반 상세 분석이 제한되었습니다.\n")
            }

            val features = analysisResult.features
            if (features != null) {
                append("\n📋 주요 피처:\n")
                append("• DOM 노드 수: ${features.domNodeCount}\n")
                append("• iframe 개수: ${features.iframeCount}\n")
                append("• 외부 도메인 form: ${features.externalDomainFormCount}\n")
                append("• base64 스크립트: ${features.base64ScriptCount}\n")
                append("• 이벤트 리스너: ${features.eventListenerCount}\n")
                append("• 의심스러운 스크립트: ${features.suspiciousScriptCount}\n")
                append("• 로그인 폼: ${if (features.hasLoginForm) "있음" else "없음"}\n")
                append("• 신용카드 폼: ${if (features.hasCreditCardForm) "있음" else "없음"}\n")
                append("• URL 길이: ${features.urlLength}\n")
                append("• 특수문자 수: ${features.specialCharCount}\n")
            } else if (targetUrl != null) {
                val specialCharCount = targetUrl.count { !it.isLetterOrDigit() }
                append("\n🔗 URL 메트릭:\n")
                append("• URL 길이: ${targetUrl.length}\n")
                append("• 특수문자 수: $specialCharCount\n")
            }

            if (analysisResult.riskFactors.isNotEmpty()) {
                append("\n⚠️ 위험 요인:\n")
                analysisResult.riskFactors.distinct().forEach { factor ->
                    append("• $factor\n")
                }
            }

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
            append("이 웹페이지는 피싱으로 의심됩니다!\n\n")
            append("📊 신뢰도: ${(analysisResult.confidenceScore.coerceIn(0.0, 1.0) * 100).toInt()}%\n\n")
            if (analysisResult.riskFactors.isNotEmpty()) {
                append("⚠️ 발견된 위험 요인:\n")
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
            append("정말로 계속하시겠습니까?")
        }

        AlertDialog.Builder(this)
            .setTitle("🚨 피싱 경고!")
            .setMessage(messageBuilder.toString())
            .setPositiveButton("계속하기 (위험)") { dialog: android.content.DialogInterface?, which: Int ->
                // 사용자가 위험을 감수하고 계속하기로 선택
                Toast.makeText(this, "⚠️ 주의: 피싱 의심 사이트입니다", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("닫기 (권장)") { dialog: android.content.DialogInterface?, which: Int ->
                toggleView() // 카메라 뷰로 돌아가기
            }
            .setCancelable(false)
            .show()
    }

    private fun isValidUrl(url: String): Boolean {
        return Patterns.WEB_URL.matcher(url).matches() ||
               url.startsWith("http://") ||
               url.startsWith("https://")
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val NO_URL_WARNING_KEY = "__NO_URL__"
    }
}

// 논문에서 제안하는 피처 추출을 위한 JavaScript 인터페이스
class WebFeatureExtractor(private val callback: (WebFeatures) -> Unit) {

    @JavascriptInterface
    fun receiveFeatures(featuresJson: String) {
        try {
            val jsonObject = JSONObject(featuresJson)
            val features = WebFeatures(
                domNodeCount = jsonObject.getInt("domNodeCount"),
                iframeCount = jsonObject.getInt("iframeCount"),
                externalDomainFormCount = jsonObject.getInt("externalDomainFormCount"),
                base64ScriptCount = jsonObject.getInt("base64ScriptCount"),
                eventListenerCount = jsonObject.getInt("eventListenerCount"),
                suspiciousScriptCount = jsonObject.getInt("suspiciousScriptCount"),
                redirectChainLength = jsonObject.getInt("redirectChainLength"),
                hasLoginForm = jsonObject.getBoolean("hasLoginForm"),
                hasCreditCardForm = jsonObject.getBoolean("hasCreditCardForm"),
                urlLength = jsonObject.getInt("urlLength"),
                specialCharCount = jsonObject.getInt("specialCharCount")
            )
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
                    var hasCreditCardForm = false;
                    for (var i = 0; i < forms.length; i++) {
                        var inputs = forms[i].getElementsByTagName('input');
                        for (var j = 0; j < inputs.length; j++) {
                            var name = inputs[j].getAttribute('name') || '';
                            var placeholder = inputs[j].getAttribute('placeholder') || '';
                            if (name.includes('card') || name.includes('credit') ||
                                placeholder.includes('card') || placeholder.includes('credit')) {
                                hasCreditCardForm = true;
                                break;
                            }
                        }
                        if (hasCreditCardForm) break;
                    }

                    // URL 길이 및 특수문자 수
                    var url = window.location.href;
                    var urlLength = url.length;
                    var specialCharCount = (url.match(/[^a-zA-Z0-9]/g) || []).length;

                    // 결과 객체 생성
                    var features = {
                        domNodeCount: domNodeCount,
                        iframeCount: iframeCount,
                        externalDomainFormCount: externalDomainFormCount,
                        base64ScriptCount: base64ScriptCount,
                        eventListenerCount: eventListenerCount,
                        suspiciousScriptCount: suspiciousScriptCount,
                        redirectChainLength: redirectChainLength,
                        hasLoginForm: hasLoginForm,
                        hasCreditCardForm: hasCreditCardForm,
                        urlLength: urlLength,
                        specialCharCount: specialCharCount
                    };

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

// 웹페이지 피처 데이터 클래스
data class WebFeatures(
    val domNodeCount: Int,
    val iframeCount: Int,
    val externalDomainFormCount: Int,
    val base64ScriptCount: Int,
    val eventListenerCount: Int,
    val suspiciousScriptCount: Int,
    val redirectChainLength: Int,
    val hasLoginForm: Boolean,
    val hasCreditCardForm: Boolean,
    val urlLength: Int,
    val specialCharCount: Int
)

// 논문에서 제안하는 규칙 기반 피싱 탐지 시스템
class PhishingDetector {

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

    // 피싱 점수 계산 (0.0 ~ 1.0)
    fun calculatePhishingScore(features: WebFeatures): Double {
        var score = 0.0
        var maxScore = 0.0

        // 각 피처에 대한 점수 계산
        score += calculateFeatureScore(features.domNodeCount, PHISHING_RULES["DOM_NODE_THRESHOLD"]!!, 0.15)
        maxScore += 0.15

        score += calculateFeatureScore(features.iframeCount, PHISHING_RULES["IFRAME_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        score += calculateFeatureScore(features.externalDomainFormCount, PHISHING_RULES["EXTERNAL_FORM_THRESHOLD"]!!, 0.15)
        maxScore += 0.15

        score += calculateFeatureScore(features.base64ScriptCount, PHISHING_RULES["BASE64_SCRIPT_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        score += calculateFeatureScore(features.eventListenerCount, PHISHING_RULES["EVENT_LISTENER_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        score += calculateFeatureScore(features.suspiciousScriptCount, PHISHING_RULES["SUSPICIOUS_SCRIPT_THRESHOLD"]!!, 0.15)
        maxScore += 0.15

        score += calculateFeatureScore(features.redirectChainLength, PHISHING_RULES["REDIRECT_CHAIN_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        score += calculateFeatureScore(features.urlLength, PHISHING_RULES["URL_LENGTH_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        score += calculateFeatureScore(features.specialCharCount, PHISHING_RULES["SPECIAL_CHAR_THRESHOLD"]!!, 0.1)
        maxScore += 0.1

        // 로그인 폼이나 신용카드 폼이 있는 경우 추가 점수
        if (features.hasLoginForm) {
            score += 0.2
            maxScore += 0.2
        }

        if (features.hasCreditCardForm) {
            score += 0.3
            maxScore += 0.3
        }

        return if (maxScore > 0) minOf(score / maxScore, 1.0) else 0.0
    }

    private fun calculateFeatureScore(value: Int, threshold: Int, weight: Double): Double {
        return if (value > threshold) {
            weight * minOf(value.toDouble() / threshold.toDouble(), 2.0) // 최대 2배까지
        } else {
            0.0
        }
    }

    // 피싱 여부 판단
    fun isPhishing(features: WebFeatures, url: String? = null, threshold: Double = phishingThreshold): Boolean {
        val result = analyzePhishing(features, url)
        return result.confidenceScore >= threshold
    }

    // 피싱 분석 결과 생성 (DOM + URL 결합)
    fun analyzePhishing(features: WebFeatures, url: String? = null): PhishingAnalysisResult {
        val featureScore = calculatePhishingScore(features)
        val riskFactors = collectFeatureRiskFactors(features)
        val urlHeuristics = url?.let { evaluateUrlHeuristics(it) }

        val combinedScore = urlHeuristics?.let { combineScores(featureScore, it.score) } ?: featureScore

        if (urlHeuristics != null) {
            riskFactors.addAll(urlHeuristics.riskFactors)
        }

        return PhishingAnalysisResult(
            isPhishing = combinedScore >= phishingThreshold,
            confidenceScore = combinedScore.coerceIn(0.0, 1.0),
            riskFactors = riskFactors.distinct(),
            features = features,
            inspectedUrl = url,
            analysisMode = if (urlHeuristics != null) AnalysisMode.FULL else AnalysisMode.DOM_ONLY
        )
    }

    // URL만으로 간소 분석 수행
    fun analyzeUrlOnly(url: String): PhishingAnalysisResult {
        val urlHeuristics = evaluateUrlHeuristics(url)
        return PhishingAnalysisResult(
            isPhishing = urlHeuristics.score >= phishingThreshold,
            confidenceScore = urlHeuristics.score.coerceIn(0.0, 1.0),
            riskFactors = urlHeuristics.riskFactors.distinct(),
            features = null,
            inspectedUrl = url,
            analysisMode = AnalysisMode.URL_ONLY
        )
    }

    private fun collectFeatureRiskFactors(features: WebFeatures): MutableList<String> {
        val factors = mutableListOf<String>()

        if (features.domNodeCount > PHISHING_RULES["DOM_NODE_THRESHOLD"]!!) {
            factors.add("DOM 노드 수가 많음 (${features.domNodeCount})")
        }

        if (features.iframeCount > PHISHING_RULES["IFRAME_THRESHOLD"]!!) {
            factors.add("iframe 개수가 많음 (${features.iframeCount})")
        }

        if (features.externalDomainFormCount > PHISHING_RULES["EXTERNAL_FORM_THRESHOLD"]!!) {
            factors.add("외부 도메인 form이 많음 (${features.externalDomainFormCount})")
        }

        if (features.base64ScriptCount > PHISHING_RULES["BASE64_SCRIPT_THRESHOLD"]!!) {
            factors.add("base64 스크립트가 발견됨 (${features.base64ScriptCount})")
        }

        if (features.eventListenerCount > PHISHING_RULES["EVENT_LISTENER_THRESHOLD"]!!) {
            factors.add("이벤트 리스너가 많음 (${features.eventListenerCount})")
        }

        if (features.suspiciousScriptCount > PHISHING_RULES["SUSPICIOUS_SCRIPT_THRESHOLD"]!!) {
            factors.add("의심스러운 스크립트가 발견됨 (${features.suspiciousScriptCount})")
        }

        if (features.redirectChainLength > PHISHING_RULES["REDIRECT_CHAIN_THRESHOLD"]!!) {
            factors.add("리다이렉트 체인이 길음 (${features.redirectChainLength})")
        }

        if (features.hasLoginForm) {
            factors.add("로그인 폼이 발견됨")
        }

        if (features.hasCreditCardForm) {
            factors.add("신용카드 관련 폼이 발견됨")
        }

        if (features.urlLength > PHISHING_RULES["URL_LENGTH_THRESHOLD"]!!) {
            factors.add("URL이 너무 김 (${features.urlLength})")
        }

        if (features.specialCharCount > PHISHING_RULES["SPECIAL_CHAR_THRESHOLD"]!!) {
            factors.add("특수문자가 많음 (${features.specialCharCount})")
        }

        return factors
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
            "'@' 문자를 포함한 URL"
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
