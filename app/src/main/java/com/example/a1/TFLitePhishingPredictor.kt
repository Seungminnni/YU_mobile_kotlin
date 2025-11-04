package com.example.a1

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLitePhishingPredictor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var featureColumns: List<String> = emptyList()
    private var inputShape: IntArray = intArrayOf()

    companion object {
        private const val TAG = "TFLitePhishingPredictor"
        private const val MODEL_FILE = "phishing_model.tflite"
        private const val FEATURE_INFO_FILE = "feature_info.json"
    }

    init {
        try {
            loadModel()
            loadFeatureInfo()
        } catch (e: Exception) {
            Log.e(TAG, "TFLite 모델 초기화 실패", e)
        }
    }

    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile(context.assets.openFd(MODEL_FILE))
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "TFLite 모델 로드 성공")

            // 입력 텐서 정보 확인
            val inputTensor = interpreter?.getInputTensor(0)
            inputShape = inputTensor?.shape() ?: intArrayOf()
            Log.d(TAG, "입력 텐서 shape: ${inputShape.joinToString(", ")}")

        } catch (e: Exception) {
            Log.e(TAG, "모델 로드 실패", e)
            throw e
        }
    }

    private fun loadModelFile(fd: android.content.res.AssetFileDescriptor): MappedByteBuffer {
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fd.startOffset
        val declaredLength = fd.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadFeatureInfo() {
        try {
            val assetManager = context.assets
            val featureInfoJson = assetManager.open(FEATURE_INFO_FILE).bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(featureInfoJson)

            val columnsArray = jsonObject.getJSONArray("feature_columns")
            featureColumns = (0 until columnsArray.length()).map { columnsArray.getString(it) }

            Log.d(TAG, "피처 정보 로드 성공: ${featureColumns.size}개 피처")
            Log.d(TAG, "피처 목록: ${featureColumns.joinToString(", ")}")

        } catch (e: Exception) {
            Log.e(TAG, "피처 정보 로드 실패", e)
            throw e
        }
    }

    /**
     * WebFeatures를 float array로 변환하여 ML 예측 수행
     */
    fun predictWithML(features: WebFeatures): Float {
        if (interpreter == null || featureColumns.isEmpty()) {
            Log.w(TAG, "모델이 초기화되지 않아 규칙 기반 예측으로 대체")
            return -1.0f // 모델 예측 실패 표시
        }

        return try {
            val inputArray = webFeaturesToFloatArray(features)
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 1), DataType.FLOAT32)

            interpreter?.run(inputArray, outputBuffer.buffer)

            val result = outputBuffer.floatArray[0]
            Log.d(TAG, "ML 예측 결과: $result")
            result

        } catch (e: Exception) {
            Log.e(TAG, "ML 예측 실패", e)
            -1.0f
        }
    }

    /**
     * WebFeatures를 모델이 기대하는 float array로 변환
     */
    private fun webFeaturesToFloatArray(features: WebFeatures): FloatArray {
        // feature_info.json에 정의된 피처 순서대로 변환
        val featureMap = mapOf(
            "dom_node_count" to features.domNodeCount.toFloat(),
            "iframe_count" to features.iframeCount.toFloat(),
            "external_domain_form_count" to features.externalDomainFormCount.toFloat(),
            "base64_script_count" to features.base64ScriptCount.toFloat(),
            "event_listener_count" to features.eventListenerCount.toFloat(),
            "suspicious_script_count" to features.suspiciousScriptCount.toFloat(),
            "redirect_chain_length" to features.redirectChainLength.toFloat(),
            "has_login_form" to if (features.hasLoginForm) 1.0f else 0.0f,
            "has_credit_card_form" to if (features.hasCreditCardForm) 1.0f else 0.0f,
            "url_length" to features.urlLength.toFloat(),
            "special_char_count" to features.specialCharCount.toFloat()
        )

        // 모델이 기대하는 피처 순서대로 배열 생성
        val inputArray = FloatArray(featureColumns.size)
        for (i in featureColumns.indices) {
            val featureName = featureColumns[i]
            inputArray[i] = featureMap[featureName] ?: 0.0f
        }

        Log.d(TAG, "입력 피처 배열: ${inputArray.joinToString(", ")}")
        return inputArray
    }

    /**
     * 모델이 정상적으로 로드되었는지 확인
     */
    fun isModelReady(): Boolean {
        return interpreter != null && featureColumns.isNotEmpty()
    }

    /**
     * 리소스 정리
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}