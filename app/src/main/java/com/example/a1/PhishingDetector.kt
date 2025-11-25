package com.example.a1

import android.content.Context
import android.util.Log

/**
 * PhishingDetector orchestrates the ML predictor and a small set of deterministic
 * heuristics used as fallbacks/interpretability signals. We intentionally avoid a
 * large hard-coded PHISHING_RULES map in MainActivity and instead keep a compact,
 * maintainable implementation here.
 */
class PhishingDetector(private val context: Context) {

    private val predictor = TFLitePhishingPredictor(context)

    companion object {
        private const val TAG = "PhishingDetector"
        private const val ML_THRESHOLD = 0.5f
    }

    fun analyzePhishing(features: WebFeatures, currentUrl: String?): PhishingAnalysisResult {
        val riskReasons = mutableListOf<String>()

        // Basic heuristics for explainability (keeps MainActivity clean from rules map)
        runCatching {
            if (features["shortening_service"] == 1.0f) riskReasons.add("단축 URL 서비스 감지")
            if (features["external_favicon"] == 1.0f) riskReasons.add("외부 파비콘")
            if (features["login_form"] == 1.0f) riskReasons.add("로그인/외부 폼 감지")
            if ((features["nb_redirection"] ?: 0f) >= 3f) riskReasons.add("다수의 리다이렉션 감지")
            if (features["suspecious_tld"] == 1.0f) riskReasons.add("의심스러운 최상위 도메인")
            // brand indicators
            if (features["domain_in_brand"] == 1.0f) riskReasons.add("브랜드명 포함 도메인")
            if (features["brand_in_path"] == 1.0f) riskReasons.add("브랜드명 포함 경로")
        }

        // Ask ML model for a prediction
        val mlScoreFloat = runCatching { predictor.predictWithML(features) }.getOrElse {
            Log.w(TAG, "ML prediction failed, falling back to heuristics", it)
            -1.0f
        }

        val (confidenceScore, isPhishing) = if (mlScoreFloat >= 0f) {
            // Ensure normalized value 0..1
            val score = mlScoreFloat.coerceIn(0f, 1f).toDouble()
            Pair(score, score >= ML_THRESHOLD)
        } else {
            // ML not available — use heuristics in a conservative way
            val heuristicsScore = if (riskReasons.isNotEmpty()) 0.6 else 0.0
            Pair(heuristicsScore, heuristicsScore >= ML_THRESHOLD)
        }

        return PhishingAnalysisResult(
            inspectedUrl = currentUrl,
            isPhishing = isPhishing,
            confidenceScore = confidenceScore,
            features = features,
            riskFactors = riskReasons
        )
    }

    fun isModelReady(): Boolean = predictor.isModelReady()

    fun close() { predictor.close() }
}
