"""
Train separate static (URL) and dynamic (HTML/JS) phishing detectors and
prepare an ensemble score that mirrors the requested weighting logic.

This script reuses the 70 input features from phishing_data_tflite_ready.csv
by splitting them into two groups:
- STATIC_FEATURES: URL-only signals
- DYNAMIC_FEATURES: page-content signals (HTML/JS/DOM)

Running this file will:
1) Load and split the dataset
2) Train two small MLP classifiers (one per feature group)
3) Report test metrics for each model and for the simple ensemble rule
4) Export TFLite versions of both models for on-device use

Labels: status (0 = phishing, 1 = legitimate)
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable, Tuple

import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, roc_auc_score
from sklearn.model_selection import train_test_split
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers


BASE_DIR = Path(__file__).resolve().parent
DATA_PATH = BASE_DIR / "phishing_data_tflite_ready.csv"

RANDOM_SEED = 42
TEST_SIZE = 0.2
BATCH_SIZE = 128
EPOCHS = 50
VAL_SPLIT = 0.1
PATIENCE = 6

STATIC_FEATURES: Tuple[str, ...] = (
    "length_url",
    "length_hostname",
    "ip",
    "nb_dots",
    "nb_hyphens",
    "nb_at",
    "nb_qm",
    "nb_and",
    "nb_or",
    "nb_eq",
    "nb_underscore",
    "nb_tilde",
    "nb_percent",
    "nb_slash",
    "nb_star",
    "nb_colon",
    "nb_comma",
    "nb_semicolumn",
    "nb_dollar",
    "nb_space",
    "nb_www",
    "nb_com",
    "nb_dslash",
    "http_in_path",
    "https_token",
    "ratio_digits_url",
    "ratio_digits_host",
    "punycode",
    "port",
    "tld_in_path",
    "tld_in_subdomain",
    "abnormal_subdomain",
    "nb_subdomains",
    "prefix_suffix",
    "random_domain",
    "shortening_service",
    "path_extension",
    "nb_redirection",
    "nb_external_redirection",
    "length_words_raw",
    "char_repeat",
    "shortest_words_raw",
    "shortest_word_host",
    "shortest_word_path",
    "longest_words_raw",
    "longest_word_host",
    "longest_word_path",
    "avg_words_raw",
    "avg_word_host",
    "avg_word_path",
    "phish_hints",
    "domain_in_brand",
    "brand_in_subdomain",
    "brand_in_path",
    "suspecious_tld",
    "statistical_report",
)

DYNAMIC_FEATURES: Tuple[str, ...] = (
    "nb_extCSS",
    "ratio_intRedirection",
    "ratio_extRedirection",
    "ratio_intErrors",
    "ratio_extErrors",
    "login_form",
    "submit_email",
    "sfh",
    "iframe",
    "popup_window",
    "onmouseover",
    "right_clic",
    "empty_title",
    "domain_in_title",
    "domain_with_copyright",
)

TARGET_COL = "status"


def set_seed(seed: int = RANDOM_SEED) -> None:
    np.random.seed(seed)
    tf.random.set_seed(seed)


def load_features(path: Path) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    df = pd.read_csv(path)
    required = list(STATIC_FEATURES) + list(DYNAMIC_FEATURES)
    missing = [c for c in required if c not in df.columns]
    for col in missing:
        df[col] = 0.0
    if missing:
        print(f"⚠️  Missing columns filled with 0.0: {missing}")
    static = df[list(STATIC_FEATURES)].to_numpy(dtype="float32")
    dynamic = df[list(DYNAMIC_FEATURES)].to_numpy(dtype="float32")
    labels = df[TARGET_COL].to_numpy(dtype="float32")
    return static, dynamic, labels


def split_data(
    static: np.ndarray, dynamic: np.ndarray, labels: np.ndarray
) -> Tuple[np.ndarray, ...]:
    return train_test_split(
        static,
        dynamic,
        labels,
        test_size=TEST_SIZE,
        random_state=RANDOM_SEED,
        stratify=labels,
    )


def build_classifier(
    input_dim: int,
    normalization: layers.Normalization,
    hidden_units: Iterable[int],
    dropout: float = 0.15,
) -> keras.Model:
    inputs = keras.Input(shape=(input_dim,), name="features")
    x = normalization(inputs)
    for units in hidden_units:
        x = layers.Dense(units, activation="relu")(x)
        x = layers.Dropout(dropout)(x)
    outputs = layers.Dense(1, activation="sigmoid", name="legitimate_prob")(x)
    return keras.Model(inputs, outputs)


def train_branch(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_val: np.ndarray,
    y_val: np.ndarray,
    name: str,
    hidden_units: Iterable[int],
) -> keras.Model:
    normalization = layers.Normalization()
    normalization.adapt(X_train)

    model = build_classifier(
        input_dim=X_train.shape[1],
        normalization=normalization,
        hidden_units=hidden_units,
    )
    model.compile(
        optimizer=keras.optimizers.Adam(),
        loss="binary_crossentropy",
        metrics=["accuracy", keras.metrics.AUC(name="auc")],
    )

    callbacks = [
        keras.callbacks.EarlyStopping(
            monitor="val_loss", patience=PATIENCE, restore_best_weights=True
        ),
    ]

    history = model.fit(
        X_train,
        y_train,
        validation_data=(X_val, y_val),
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        callbacks=callbacks,
        verbose=2,
    )
    print(f"[{name}] best val_loss: {min(history.history['val_loss']):.4f}")
    return model


def evaluate_branch(model: keras.Model, X_test: np.ndarray, y_test: np.ndarray, name: str):
    preds = model.predict(X_test, verbose=0).squeeze()
    acc = accuracy_score(y_test, preds >= 0.5)
    auc = roc_auc_score(y_test, preds)
    print(f"[{name}] test_acc={acc:.4f} | test_auc={auc:.4f}")
    return preds, acc, auc


def ensemble_phish_prob(
    legit_prob_static: np.ndarray,
    legit_prob_dynamic: np.ndarray,
    phish_threshold: float = 0.5,
    static_low_weight: float = 0.1,
    dynamic_low_weight: float = 0.9,
) -> np.ndarray:
    """
    Combine two probability streams using the requested rule:
    - If the static branch predicts phishing with >= threshold probability,
      trust static only.
    - Otherwise, use a weighted blend favoring the dynamic branch.
    Returns the phishing probability (1 - legitimate_prob).
    """
    phish_static = 1.0 - legit_prob_static
    phish_dynamic = 1.0 - legit_prob_dynamic

    blended = (
        static_low_weight * phish_static + dynamic_low_weight * phish_dynamic
    )

    use_static_only = phish_static >= phish_threshold
    return np.where(use_static_only, phish_static, blended)


def export_tflite(model: keras.Model, path: Path) -> None:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes = converter.convert()
    path.write_bytes(tflite_bytes)
    print(f"Saved TFLite: {path} ({len(tflite_bytes) / 1024:.1f} KB)")


def save_feature_manifest(path: Path) -> None:
    manifest = {
        "static_features": list(STATIC_FEATURES),
        "dynamic_features": list(DYNAMIC_FEATURES),
        "label": TARGET_COL,
    }
    path.write_text(json.dumps(manifest, indent=2))
    print(f"Wrote feature manifest: {path}")


def main() -> None:
    set_seed()
    static, dynamic, labels = load_features(DATA_PATH)

    (
        X_static_train,
        X_static_test,
        X_dynamic_train,
        X_dynamic_test,
        y_train,
        y_test,
    ) = split_data(static, dynamic, labels)

    # Keep a slice of train as validation for both branches.
    Xs_train, Xs_val, Xd_train, Xd_val, y_tr, y_val = train_test_split(
        X_static_train,
        X_dynamic_train,
        y_train,
        test_size=VAL_SPLIT,
        random_state=RANDOM_SEED,
        stratify=y_train,
    )

    static_model = train_branch(
        Xs_train,
        y_tr,
        Xs_val,
        y_val,
        name="static-url",
        hidden_units=(128, 64, 32),
    )
    dynamic_model = train_branch(
        Xd_train,
        y_tr,
        Xd_val,
        y_val,
        name="dynamic-dom",
        hidden_units=(64, 32),
    )

    static_preds, _, _ = evaluate_branch(
        static_model, X_static_test, y_test, name="static-url"
    )
    dynamic_preds, _, _ = evaluate_branch(
        dynamic_model, X_dynamic_test, y_test, name="dynamic-dom"
    )

    phish_prob = ensemble_phish_prob(static_preds, dynamic_preds)
    legit_prob = 1.0 - phish_prob
    ensemble_acc = accuracy_score(y_test, legit_prob >= 0.5)
    ensemble_auc = roc_auc_score(y_test, legit_prob)
    print(f"[ensemble] test_acc={ensemble_acc:.4f} | test_auc={ensemble_auc:.4f}")

    export_tflite(static_model, BASE_DIR / "static_url_model.tflite")
    export_tflite(dynamic_model, BASE_DIR / "dynamic_dom_model.tflite")
    save_feature_manifest(BASE_DIR / "feature_split.json")


if __name__ == "__main__":
    main()
