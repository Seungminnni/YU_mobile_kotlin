package com.example.a1

// Shared alias for feature maps produced by the WebFeatureExtractor and used by the ML model
typealias WebFeatures = Map<String, Float?>

// Result object returned by the PhishingDetector
data class PhishingAnalysisResult(
    val inspectedUrl: String? = null,
    val isPhishing: Boolean = false,
    val confidenceScore: Double = 0.0,
    val features: WebFeatures? = null,
    val riskFactors: List<String> = emptyList()
)
