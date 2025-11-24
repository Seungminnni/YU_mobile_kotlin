# 피싱 탐지 임베딩 모델

## 개요

이 프로젝트는 **유사도 기반 피싱 탐지**를 위한 임베딩 모델을 구현합니다. Autoencoder를 사용하여 고차원 피싱 피처를 저차원 임베딩 공간으로 매핑하고, 코사인 유사도를 통해 피싱 여부를 판별합니다.

## 주요 특징

### 1. Autoencoder 기반 임베딩
- **입력**: 20개의 피싱 관련 피처 (URL 특성, HTML 특성 등)
- **임베딩 차원**: 16차원 (조정 가능)
- **아키텍처**:
  - Encoder: 20 → 128 → 64 → 32 → 16
  - Decoder: 16 → 32 → 64 → 128 → 30
  - BatchNormalization + Dropout 적용

### 2. 프로토타입 기반 분류
- **정상(Legitimate) 프로토타입**: 정상 샘플들의 평균 임베딩
- **피싱(Phishing) 프로토타입**: 피싱 샘플들의 평균 임베딩
- **분류 방법**: 입력 샘플과 각 프로토타입 간의 코사인 유사도 비교

### 3. 유사도 기반 추론
```python
similarity_legitimate = cosine_similarity(embedding, prototype_legitimate)
similarity_phishing = cosine_similarity(embedding, prototype_phishing)

if similarity_legitimate > similarity_phishing:
    prediction = "Legitimate"
else:
    prediction = "Phishing"
```

## 파일 구조

```
phishing/
├── embedding_model.ipynb           # 메인 학습 노트북
├── uci-ml-phishing-dataset.csv     # 원본 데이터셋
├── encoder_model.h5                # 학습된 Encoder 모델
├── encoder_model.tflite            # TFLite 변환 모델 (모바일용)
├── scaler.pkl                      # StandardScaler 객체
├── prototypes.npz                  # 정상/피싱 프로토타입 벡터
├── embedding_model_metadata.json   # 모델 메타데이터
└── EMBEDDING_MODEL_README.md       # 이 파일
```

## 사용 방법

### 1. 모델 학습

```bash
# Jupyter Notebook 실행
jupyter notebook embedding_model.ipynb

# 또는 Jupyter Lab
jupyter lab embedding_model.ipynb
```

노트북의 모든 셀을 순차적으로 실행하면:
1. 데이터 로드 및 전처리
2. Autoencoder 학습
3. 프로토타입 생성
4. 모델 평가
5. 파일 저장
6. TFLite 변환

### 2. 저장된 모델 사용

```python
import numpy as np
import pickle
from tensorflow import keras
from sklearn.metrics.pairwise import cosine_similarity

# 모델 로드
encoder = keras.models.load_model('encoder_model.h5')
with open('scaler.pkl', 'rb') as f:
    scaler = pickle.load(f)
prototypes = np.load('prototypes.npz')
prototype_legitimate = prototypes['legitimate']
prototype_phishing = prototypes['phishing']

# 새로운 샘플 추론
sample = np.array([...])  # 20개 피처
sample_scaled = scaler.transform(sample.reshape(1, -1))
embedding = encoder.predict(sample_scaled, verbose=0)

# 유사도 계산
sim_legit = cosine_similarity(embedding, prototype_legitimate.reshape(1, -1))[0, 0]
sim_phish = cosine_similarity(embedding, prototype_phishing.reshape(1, -1))[0, 0]

# 분류
if sim_legit > sim_phish:
    print(f"정상 (유사도: {sim_legit:.4f})")
else:
    print(f"피싱 (유사도: {sim_phish:.4f})")

# 신뢰도
confidence = abs(sim_legit - sim_phish)
print(f"신뢰도: {confidence:.4f}")
```

### 3. TFLite 모델 사용 (모바일)

```python
import tensorflow as tf

# TFLite 인터프리터 생성
interpreter = tf.lite.Interpreter(model_path='encoder_model.tflite')
interpreter.allocate_tensors()

# 입력/출력 정보
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# 추론
sample_scaled = scaler.transform(sample.reshape(1, -1)).astype(np.float32)
interpreter.set_tensor(input_details[0]['index'], sample_scaled)
interpreter.invoke()
embedding = interpreter.get_tensor(output_details[0]['index'])

# 이후 프로토타입과 유사도 비교는 동일
```

## 모델 성능

노트북 실행 후 `embedding_model_metadata.json`에서 확인 가능:

```json
{
  "embedding_dim": 16,
  "input_dim": 30,
  "val_accuracy": 0.95,
  "val_auc": 0.98,
  "test_accuracy": 0.94,
  "test_auc": 0.97,
  "training_samples": 6633,
  "epochs_trained": 50,
  "batch_size": 64
}
```

## 장점

1. **해석 가능성**: 프로토타입과의 유사도로 직관적 설명 가능
2. **확장성**: 새로운 피싱 유형 추가 시 프로토타입만 업데이트
3. **경량화**: 임베딩 차원이 작아 모바일 배포에 적합
4. **신뢰도 측정**: 유사도 차이로 예측 신뢰도 계산 가능
5. **이상 탐지**: 두 프로토타입 모두와 낮은 유사도 → 미지의 패턴

## 신뢰도 임계값 활용

```python
confidence_threshold = 0.1

if confidence < confidence_threshold:
    print("⚠️ 신뢰도가 낮습니다. 추가 검증이 필요합니다.")
    # 사용자에게 경고 또는 다른 모델과 앙상블
```

## 추가 개선 방향

1. **다중 프로토타입**: 정상/피싱을 각각 여러 클러스터로 분할
2. **대조 학습(Contrastive Learning)**: Triplet Loss로 임베딩 품질 향상
3. **준지도 학습**: 라벨이 없는 데이터로 Autoencoder 사전 학습
4. **임베딩 차원 최적화**: 16 → 8 또는 32로 실험
5. **앙상블**: 기존 이진 분류 모델과 결합

## 데이터셋 정보

- **출처**: UCI Machine Learning Repository - Phishing Websites Dataset
- **샘플 수**: 11,055개 (정상: 4,898개, 피싱: 6,157개)
- **피처 수**: 30개
- **피처 범위**: 대부분 -1, 0, 1 (일부 범주형)

## 의존성

```bash
pip install tensorflow numpy pandas scikit-learn matplotlib seaborn jupyter
```

## 라이선스

이 프로젝트는 교육 및 연구 목적으로 사용됩니다.

## 문의

프로젝트 관련 문의: YU Mobile Kotlin Team
