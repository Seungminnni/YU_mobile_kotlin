# DOM node (domNodeCount) & feature-parity issue — diagnostic and remediation plan

**Problem summary (short):**
- `domNodeCount` is computed in the WebView feature extractor (JS) but is not part of the model input features (phishing/feature_info.json).
- Several other fields are computed in JS (e.g., `iframeCount`, `hasLoginForm`, `specialCharCount`, `eventListenerCount`) yet some appear duplicated or renamed in the training set (e.g., `iframe` vs `iframeCount`, `login_form` vs `hasLoginForm`).

**Why this is critical:**
- Model and app feature parity is required for consistent on-device inference. If a useful signal is calculated at the WebView level but not present in the training dataset, the model cannot use it. Conversely, model inputs that are not computed/parsed from the app create blind spots.
- Duplicate or semantically overlapping features increase confusion and risk: either redundant information was removed from training (losing useful granularity) or the app uses alternate names that get dropped before inference.
- For the naver.com false-positive we are investigating, these mismatches may hide the true cause or prevent us from fixing it without retraining.

**Immediate diagnostic tasks (high priority):**
1. Confirm that `domNodeCount` and other calculated-but-not-sent fields are logged in the app (done: `domNodeCount` added to payload for diagnostics). Collect several failing (naver) and normal samples as JSON in `phishing/samples/`.
2. Compare collected sample values to training distribution for similar features (e.g., `nb_hyperlinks`, `nb_extCSS`, `iframe`/`iframeCount`, `specialCharCount`) and compute correlations.
3. Run occlusion/permutation tests (per-sample and global) with the trained model (or a proxy) to determine if any of these *omitted* features would materially change predictions.

**Engineering tasks / remediation options:**
- Quick diagnostic (no retrain): keep `domNodeCount` logged and use it as a monitoring/alert signal. Optionally, add a rule-based override if certain combinations (domNodeCount & suspiciousScriptCount) strongly indicate false-positive.
- Reconcile feature names and remove duplication/ambiguity between JS and model schema (feature_info.json) to guarantee one-to-one mapping.
- If domNodeCount or another omitted feature proves useful, add it to the canonical training CSV (`phishing_data_tflite_ready.csv`), update `feature_info.json`, retrain model, export new TFLite, and update app assets.
- If features are redundant, choose single canonical representations (counts vs boolean) and standardize naming.

**Acceptance criteria:**
- Collect reproducible naver/normal samples (at least N=5 each) saved under `phishing/samples/`.
- Produce a short report: distribution comparison + occlusion/permutation impact ranking that demonstrates whether domNodeCount (or other omitted features) affect predictions.
- If adding domNodeCount to training, include new CSV column + updated `feature_info.json` and new model artifact or documented reason for not including it.

**Labels**: bug, analysis, priority:high

---

(If desired, I can create a proper GitHub issue directly; this file is a draft/backup placed in the repo.)