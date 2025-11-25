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
    private var medians: Map<String, Float> = emptyMap()

    companion object {
        private const val TAG = "TFLitePhishingPredictor"
        private const val MODEL_FILE = "phishing_classifier.tflite"  // 임베딩 모델 사용
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
            // medians가 있으면 읽어둡니다 (노트북에서 저장하도록 확장 가능)
            if (jsonObject.has("medians")) {
                val medJson = jsonObject.getJSONObject("medians")
                val keys = medJson.keys()
                val map = mutableMapOf<String, Float>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = medJson.getDouble(k).toFloat()
                }
                medians = map
                Log.d(TAG, "medians 로드 성공: ${medians.keys.size}개")
            } else {
                Log.d(TAG, "medians 없음: 기본 결측값 0.0 사용")
            }

        } catch (e: Exception) {
            Log.e(TAG, "피처 정보 로드 실패", e)
            throw e
        }
    }

    /**
     * WebFeatures를 사용하여 피싱 확률 예측
     * 반환값: 피싱 확률 (0.0 = 정상, 1.0 = 피싱)
     */
    fun predictWithML(features: WebFeatures): Float {
        if (interpreter == null || featureColumns.isEmpty()) {
            Log.w(TAG, "모델이 초기화되지 않아 규칙 기반 예측으로 대체")
            return -1.0f // 모델 예측 실패 표시
        }

        return try {
            val expectedSize = inputShape.lastOrNull()
            if (expectedSize != null && expectedSize != featureColumns.size) {
                Log.w(TAG, "모델 입력 피처 수 불일치: 모델 ${expectedSize} vs feature_info ${featureColumns.size} (ML 예측 건너뜀)")
                return -1.0f
            }

            // 1. 피처를 float array로 변환 (-1, 0, 1 값들)
            val inputArray = webFeaturesToFloatArray(features)

            // 2. 분류 모델 추론 (1차원 출력: 0.0~1.0)
            // 모델 출력: 0.0 = 피싱, 1.0 = 정상
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, 1), DataType.FLOAT32)
            interpreter?.run(arrayOf(inputArray), outputBuffer.buffer)
            val output = outputBuffer.floatArray

            // 모델 출력값 (0.0 = 피싱, 1.0 = 정상)
            val legitimateProb = output[0]
            
            // 피싱 확률로 변환 (1 - 정상확률 = 피싱확률)
            val phishingProb = 1.0f - legitimateProb

            Log.d(TAG, "모델 출력 (정상 확률): $legitimateProb")
            Log.d(TAG, "피싱 확률: ${(phishingProb * 100).toInt()}%")

            phishingProb

        } catch (e: Exception) {
            Log.e(TAG, "ML 예측 실패", e)
            -1.0f
        }
    }

    /**
     * WebFeatures를 모델이 기대하는 float array로 변환
     */
    private fun webFeaturesToFloatArray(features: WebFeatures): FloatArray {
        // 모델이 기대하는 피처 순서대로 배열 생성
        val inputArray = FloatArray(featureColumns.size)
        for (i in featureColumns.indices) {
            val featureName = featureColumns[i]
            val v = features[featureName]
            inputArray[i] = when {
                v != null -> v
                medians.containsKey(featureName) -> medians[featureName]!!
                else -> 0.0f
            }
        }

        Log.d(TAG, "입력 피처 배열: ${inputArray.joinToString(", ")}")
        return inputArray
    }

    /**
     * 코사인 유사도 계산
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        normA = kotlin.math.sqrt(normA)
        normB = kotlin.math.sqrt(normB)

        return if (normA > 0.0f && normB > 0.0f) {
            dotProduct / (normA * normB)
        } else {
            0.0f
        }
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
