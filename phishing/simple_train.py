#!/usr/bin/env python3
"""
간단한 피싱 모델 학습 및 TFLite 변환 스크립트
"""

import os
import json
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.utils import shuffle

# TensorFlow는 마지막에 import (메모리 절약)
def load_and_preprocess_data():
    """데이터 로드 및 전처리"""
    print("데이터 로드 중...")
    df = pd.read_csv("phishing_data.csv")

    if "url" in df.columns:
        df = df.drop(columns=["url"])

    df = shuffle(df, random_state=42)

    # 라벨 정리
    df["status"] = df["status"].astype(str).replace({"legitimate": 0, "phishing": 1})
    df["status"] = pd.to_numeric(df["status"], errors="coerce").astype("float32")

    # 문자열 치환
    df = df.replace({"zero": 0, "one": 1, "Zero": 0, "One": 1})

    # 숫자 피처만 선택
    feature_cols = [c for c in df.columns if c != "status"]
    numeric_cols = []

    for c in feature_cols:
        ser = pd.to_numeric(df[c], errors="coerce")
        if ser.notna().any():
            df[c] = ser
            numeric_cols.append(c)

    # 결측치 처리 ###메디안 확인 해야함
    for c in numeric_cols:
        med = float(df[c].dropna().median()) if df[c].notna().any() else 0.0
        df[c] = df[c].fillna(med).astype("float32")

    X = df[numeric_cols].to_numpy(dtype="float32")
    y = df["status"].to_numpy(dtype="float32")

    return X, y, numeric_cols

def create_and_train_model(X_train, y_train, X_val, y_val):
    """TensorFlow 모델 생성 및 학습"""
    print("TensorFlow import 중...")
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers

    print(f"TensorFlow 버전: {tf.__version__}")

    # 메모리 최적화
    tf.config.set_visible_devices([], 'GPU')  # CPU only

    # 모델 생성
    inp = keras.Input(shape=(X_train.shape[1],), name="features", dtype=tf.float32)
    norm = layers.Normalization(name="norm_all")
    norm.adapt(X_train)

    x = norm(inp)
    x = layers.Dense(64, activation="relu")(x)
    x = layers.Dropout(0.1)(x)
    x = layers.Dense(32, activation="relu")(x)
    x = layers.Dropout(0.1)(x)
    out = layers.Dense(1, activation="sigmoid")(x)

    model = keras.Model(inputs=inp, outputs=out, name="phish_numeric_only")
    model.compile(
        optimizer="adam",
        loss="binary_crossentropy",
        metrics=[keras.metrics.BinaryAccuracy(name="accuracy")]
    )

    print("모델 구조:")
    model.summary()

    # 학습
    print("모델 학습 시작...")
    es = keras.callbacks.EarlyStopping(
        monitor="val_loss", patience=5, restore_best_weights=True
    )

    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=50, batch_size=64,
        callbacks=[es],
        verbose=1
    )

    # 평가
    val_loss, val_acc = model.evaluate(X_val, y_val, verbose=0)
    print(".4f")

    return model, history

def convert_to_tflite(model, numeric_cols):
    """TFLite 변환"""
    print("TFLite 변환 중...")
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    # 모델 저장
    tflite_path = "phishing_model.tflite"
    with open(tflite_path, 'wb') as f:
        f.write(tflite_model)

    print(f"TFLite 모델 저장됨: {tflite_path}")

    # 피처 정보 저장
    feature_info = {
        "feature_columns": numeric_cols,
        "input_shape": [len(numeric_cols)],
        "normalization_layer": "norm_all"
    }

    feature_info_path = "feature_info.json"
    with open(feature_info_path, 'w') as f:
        json.dump(feature_info, f, indent=2)

    print(f"피처 정보 저장됨: {feature_info_path}")

    return tflite_path, feature_info_path

def main():
    """메인 함수"""
    print("피싱 모델 학습 시작...")

    # 데이터 로드
    X, y, numeric_cols = load_and_preprocess_data()

    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42
    )

    print(f"학습 데이터: {X_train.shape}, 검증 데이터: {X_val.shape}")
    print(f"피처 수: {len(numeric_cols)}")

    # 모델 학습
    model, history = create_and_train_model(X_train, y_train, X_val, y_val)

    # TFLite 변환
    tflite_path, feature_info_path = convert_to_tflite(model, numeric_cols)

    print("\n🎉 완료!")
    print(f"📁 TFLite 모델: {tflite_path}")
    print(f"📋 피처 정보: {feature_info_path}")

if __name__ == "__main__":
    main()