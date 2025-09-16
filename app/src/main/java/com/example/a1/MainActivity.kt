package com.example.a1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
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
        webView.settings.domStorageEnabled = false   // DOM 스토리지 비활성화
        webView.settings.databaseEnabled = false     // 데이터베이스 비활성화
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE  // 캐시 비활성화
        webView.settings.setGeolocationEnabled(false)  // 위치 정보 비활성화
        webView.settings.allowFileAccess = false      // 파일 시스템 접근 비활성화
        webView.settings.allowContentAccess = false   // 콘텐츠 접근 비활성화
        webView.settings.allowFileAccessFromFileURLs = false  // 파일 URL 접근 비활성화
        webView.settings.allowUniversalAccessFromFileURLs = false  // 범용 파일 URL 접근 비활성화
        webView.settings.setSupportMultipleWindows(false)  // 다중 창 지원 비활성화
        webView.settings.setSupportZoom(true)         // 줌만 허용
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

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
                dialog.dismiss()
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
        resultTextView.text = "🔒 보안 모드 가상환경에서 로드 중..."
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
                                    resultTextView.text = """
                                        🌐 URL 감지됨: $rawValue
                                        🔒 가상환경에서 안전하게 실행됩니다
                                        📱 '웹뷰로 전환' 버튼을 클릭하세요
                                    """.trimIndent()
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
        val analysisResult = phishingDetector.analyzePhishing(features)

        val resultText = StringBuilder().apply {
            append("🔍 피싱 분석 결과\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("📊 신뢰도 점수: ${(analysisResult.confidenceScore * 100).toInt()}%\n")
            append("🎯 판정 결과: ${if (analysisResult.isPhishing) "🚨 피싱 의심" else "✅ 안전"}\n")
            append("\n📋 주요 피처:\n")
            append("• DOM 노드 수: ${features.domNodeCount}\n")
            append("• iframe 개수: ${features.iframeCount}\n")
            append("• 외부 도메인 form: ${features.externalDomainFormCount}\n")
            append("• base64 스크립트: ${features.base64ScriptCount}\n")
            append("• 이벤트 리스너: ${features.eventListenerCount}\n")
            append("• 의심스러운 스크립트: ${features.suspiciousScriptCount}\n")
            append("• 로그인 폼: ${if (features.hasLoginForm) "있음" else "없음"}\n")
            append("• 신용카드 폼: ${if (features.hasCreditCardForm) "있음" else "없음"}\n")

            if (analysisResult.riskFactors.isNotEmpty()) {
                append("\n⚠️ 위험 요인:\n")
                analysisResult.riskFactors.forEach { factor ->
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

        // 피싱 의심 시 경고 다이얼로그 표시
        if (analysisResult.isPhishing) {
            showPhishingWarningDialog(analysisResult)
        }
    }

    private fun showPhishingWarningDialog(analysisResult: PhishingAnalysisResult) {
        val riskFactorsText = analysisResult.riskFactors.joinToString("\n• ")

        AlertDialog.Builder(this)
            .setTitle("🚨 피싱 경고!")
            .setMessage("""
                이 웹페이지는 피싱으로 의심됩니다!

                📊 신뢰도: ${(analysisResult.confidenceScore * 100).toInt()}%

                ⚠️ 발견된 위험 요인:
                • $riskFactorsText

                🔒 보안 권장사항:
                • 이 사이트에서 어떠한 정보도 입력하지 마세요
                • 개인정보, 비밀번호, 신용카드 정보를 절대 입력하지 마세요
                • 의심스러운 링크는 클릭하지 마세요
                • 즉시 이 페이지를 닫으세요

                정말로 계속하시겠습니까?
            """.trimIndent())
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
                    for (var i = 0; i < allElements.length; i++) {
                        var events = getEventListeners(allElements[i]);
                        if (events) {
                            eventListenerCount += Object.keys(events).length;
                        }
                    }

                    // 의심스러운 스크립트 수 계산
                    var suspiciousScriptCount = 0;
                    var suspiciousKeywords = ['eval', 'document.write', 'innerHTML', 'location.href', 'window.open'];
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
                    var redirectChainLength = window.history.length;

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

        return if (maxScore > 0) score / maxScore else 0.0
    }

    private fun calculateFeatureScore(value: Int, threshold: Int, weight: Double): Double {
        return if (value > threshold) {
            weight * minOf(value.toDouble() / threshold.toDouble(), 2.0) // 최대 2배까지
        } else {
            0.0
        }
    }

    // 피싱 여부 판단
    fun isPhishing(features: WebFeatures, threshold: Double = 0.6): Boolean {
        val score = calculatePhishingScore(features)
        return score >= threshold
    }

    // 피싱 분석 결과 생성
    fun analyzePhishing(features: WebFeatures): PhishingAnalysisResult {
        val score = calculatePhishingScore(features)
        val isPhishing = score >= 0.6

        val riskFactors = mutableListOf<String>()

        if (features.domNodeCount > PHISHING_RULES["DOM_NODE_THRESHOLD"]!!)
            riskFactors.add("DOM 노드 수가 많음 (${features.domNodeCount})")

        if (features.iframeCount > PHISHING_RULES["IFRAME_THRESHOLD"]!!)
            riskFactors.add("iframe 개수가 많음 (${features.iframeCount})")

        if (features.externalDomainFormCount > PHISHING_RULES["EXTERNAL_FORM_THRESHOLD"]!!)
            riskFactors.add("외부 도메인 form이 많음 (${features.externalDomainFormCount})")

        if (features.base64ScriptCount > PHISHING_RULES["BASE64_SCRIPT_THRESHOLD"]!!)
            riskFactors.add("base64 스크립트가 발견됨 (${features.base64ScriptCount})")

        if (features.eventListenerCount > PHISHING_RULES["EVENT_LISTENER_THRESHOLD"]!!)
            riskFactors.add("이벤트 리스너가 많음 (${features.eventListenerCount})")

        if (features.suspiciousScriptCount > PHISHING_RULES["SUSPICIOUS_SCRIPT_THRESHOLD"]!!)
            riskFactors.add("의심스러운 스크립트가 발견됨 (${features.suspiciousScriptCount})")

        if (features.redirectChainLength > PHISHING_RULES["REDIRECT_CHAIN_THRESHOLD"]!!)
            riskFactors.add("리다이렉트 체인이 길음 (${features.redirectChainLength})")

        if (features.hasLoginForm)
            riskFactors.add("로그인 폼이 발견됨")

        if (features.hasCreditCardForm)
            riskFactors.add("신용카드 관련 폼이 발견됨")

        if (features.urlLength > PHISHING_RULES["URL_LENGTH_THRESHOLD"]!!)
            riskFactors.add("URL이 너무 김 (${features.urlLength})")

        if (features.specialCharCount > PHISHING_RULES["SPECIAL_CHAR_THRESHOLD"]!!)
            riskFactors.add("특수문자가 많음 (${features.specialCharCount})")

        return PhishingAnalysisResult(
            isPhishing = isPhishing,
            confidenceScore = score,
            riskFactors = riskFactors,
            features = features
        )
    }
}

// 피싱 분석 결과 데이터 클래스
data class PhishingAnalysisResult(
    val isPhishing: Boolean,
    val confidenceScore: Double,
    val riskFactors: List<String>,
    val features: WebFeatures
)