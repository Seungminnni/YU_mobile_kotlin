# YU Mobile Kotlin – QR Phishing Sandbox Demo

본 앱은 「A Decentralized Real-Time QR Phishing Detection Method via Mobile Sandbox Execution」 논문 아이디어를 안드로이드에서 검증하기 위한 프로토타입입니다.  
QR 코드에서 추출한 URL을 모바일 샌드박스(WebView)에서 격리 실행하며, DOM/자바스크립트 특징과 URL 휴리스틱을 결합해 피싱 여부를 추정합니다.

## 주요 기능
- **온디바이스 QR 스캔**: CameraX + ML Kit으로 실시간 QR 코드 인식.
- **가상 WebView 샌드박스**: JavaScript·스토리지·파일 접근 차단, 필요 시 사용자 동의 후 해제.
- **피처 추출 스크립트**: DOM 노드, iframe, form, base64 스크립트, 이벤트 핸들러 등 수집.
- **URL 휴리스틱 분석**: IP 사용, 과도한 특수문자, 위험 키워드·TLD 등을 점수화.
- **결합 판단 로직**: DOM 점수와 URL 점수를 `1-(1-dom)*(1-url)` 방식으로 결합 후 임계값 비교.
- **경고 UX**: 분석 결과/위험 요인/권장 행동을 UI와 모달로 안내.

## 프로젝트 구조
```
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
local.properties
README.md
settings.gradle.kts

app/
   ├─ build.gradle.kts
   ├─ proguard-rules.pro
   ├─ build/                                # 빌드 산출물 (IDE/Gradle용, 보통 무시)
   │  ├─ generated/
   │  ├─ intermediates/
   │  ├─ kotlin/
   │  └─ outputs/
   └─ src/
       ├─ androidTest/
       │  └─ java/
       ├─ main/
       │  ├─ AndroidManifest.xml
       │  ├─ assets/                           # 앱 내 에셋 (ex: feature_info.json)
       │  ├─ java/                             # 주요 소스: `com/example/a1/MainActivity.kt` 등
       │  └─ res/                              # 레이아웃 / values / drawable
       └─ test/

gradle/
   ├─ libs.versions.toml
   └─ wrapper/
       └─ gradle-wrapper.properties

phishing/
   ├─ data preprocessing.ipynb
   ├─ feature_info.json                      # training ⇄ mobile 피처 스키마
   ├─ phishing_data.csv
   ├─ phishing_data_tflite_ready.csv         # tflite-ready CSV (모델 훈련용, 일치된 컬럼 순서)
   ├─ phishing_model.tflite                  # (optional) on-device 모델 바이너리
   ├─ simple_train.py                        # 학습 스크립트 (tflite-ready prefer)
   ├─ test_phishing.csv
   ├─ test_not-phishing.csv
   ├─ last.csv
   ├─ README.md                              # phishing-specific 설명/실험 노트
   └─ other notebooks (Untitled.ipynb ...)

```

## 실행 방법
1. **JDK 설치**  
   `./gradlew` 실행 시 JDK 11 이상을 요구합니다. macOS라면 `brew install temurin` (또는 Oracle/SAP 등)로 설치 후 `JAVA_HOME`을 설정하세요.
2. **Android Studio**  
   - Android Studio Koala 이상 권장  
   - 최소 SDK 24, target SDK 36
3. **의존성 동기화**  
   프로젝트 열기 → Gradle Sync.
4. **앱 실행**  
   실제 기기(카메라가 필요) 또는 CameraX 프리뷰가 가능한 에뮬레이터에서 `app` 모듈을 Run.

## 사용 흐름
1. `MainActivity` 시작 시 카메라 권한을 요청하고 QR 스캔 준비.
2. QR URL 발견 → **즉시 URL 휴리스틱 분석** → 결과 패널에 1차 평판 표시.
3. 사용자가 “웹뷰로 전환”  
   - 기본: JavaScript 꺼진 상태로 URL만 로드.  
   - 필요 시 확인 다이얼로그에서 JavaScript 활성화 선택.
4. 페이지 로딩 종료 → DOM/JS 이벤트 피처 추출 → `PhishingDetector`가 DOM+URL 결합 분석.
5. 위험 판단 시 경고 다이얼로그와 권장 행동 제시. 사용자는 페이지 종료 또는 계속 진행 선택.

## 탐지 로직 개요
- **DOM 기반**: DOM 노드 수, iframe, 외부 도메인 form, base64 script, 이벤트 리스너, 의심 키워드 등.
- **폼 탐지**: 로그인/신용카드 입력 폼 여부.
- **URL 기반**: 길이·특수문자·IP 도메인·HTTP 여부·의심 키워드·위험 TLD·Punycode·서브도메인 수·경로 깊이·% 인코딩·이중 `//`.
- **점수화**: 각 특징을 가중치 기반 스코어로 변환 후 0~1 범위로 정규화.  
  DOM 점수와 URL 점수를 결합해 최종 신뢰도 계산, 0.6 이상이면 피싱 의심.

## 제한 사항 & 개선 아이디어
- **네트워크 분석 미포함**: 실제 시나리오에선 트래픽·리소스 로딩 정보를 추가 수집할 필요가 있습니다.
- **휴리스틱 기반**: 머신러닝 모델(TensorFlow Lite 등)을 접목하면 탐지 정밀도가 향상될 수 있습니다.
- **UI 개선**: 긴 분석 결과로 인해 스크롤이 필요할 수 있습니다. 추후 ScrollView 도입을 고려하세요.
- **화이트리스트/학습 데이터**: 현재는 규칙 기반이므로 오탐/미탐 가능성이 있습니다. 레이블된 데이터셋을 통한 튜닝이 권장됩니다.

## 테스트
- 로컬 기기에서 다양한 QR URL(정상/피싱 의심)을 스캔해 수동 검증.
- 안드로이드 UI 테스트나 Unit 테스트는 포함되어 있지 않습니다. 추후 피처별 테스트 도입을 추천합니다.

## 라이선스
- 리포지토리 정책에 따릅니다. (별도 명시가 없다면 연구용으로 사용하세요.)

문의나 추가 개선 요청이 있으면 언제든지 Issues 또는 직접 질문해주세요.

---

## 개발자 문서: 피처 추출 & 모바일-온디바이스 파이프라인 (상세)
이 프로젝트의 핵심은 WebView에 주입된 JavaScript에서 가능한 많은 페이지/DOM 기반 피처를 추출하고, 해당 피처를 모바일 쪽으로 전달해 온-디바이스 모델(TFLite) 또는 규칙 기반 로직으로 판정하는 것입니다. 아래는 현재 구현된 중요한 세부 사항(동적 피처 포함)과 검증/테스트 방법입니다.

### 1) JS 기반 피처 추출 (app/src/main/java/com/example/a1/MainActivity.kt)
- MainActivity.getFeatureExtractionScript()에 포함된 스크립트가 대부분의 페이지 피처를 계산합니다.
- 특성 예: DOM 노드 수, iframe 개수, 외부 도메인 form 개수, base64 스크립트 수, 이벤트 리스너 추정, 의심스러운 스크립트 패턴, URL 문자 통계, 링크·미디어 내부-외부 비율, form-action(SFH), 로그인/신용카드 폼 판단 등.
- 일부 피처(WHOIS, page_rank, web_traffic 등)는 외부 API/서버가 필요해 JavaScript에서는 수집하지 않고 null을 설정합니다.

### 2) null 처리 정책
- JS는 불가능하거나 불확실한 피처에 대해 `null`을 명시적으로 사용합니다. 예: `ratio_intRedirection`, `ratio_extRedirection`, `ratio_intErrors`, `ratio_extErrors`, `links_in_tags` 등.
- Android 측의 `WebFeatureExtractor.receiveFeatures()`는 JSON에서 `null`을 Kotlin `null`로 보존합니다 (Map<String, Float?> 형태). 즉 key는 남지만 값은 null일 수 있습니다.

### 3) safe helpers 업데이트
- JS의 `safeMin`, `safeMax`, `safeAvg` 헬퍼들은 문자열 배열(토큰) 뿐 아니라 숫자 배열(이미 길이를 담은 배열)도 안전하게 처리하도록 개선했습니다. 빈 배열 또는 non-finite 값에 대해 0을 반환하도록 방어적 코딩이 적용되어 NaN/undefined 문제를 제거했습니다.

### 4) 동적(런타임) 피처 — 'Redirect' 카운팅 (중요)
- 이유: URL 문자열만으로는 파악할 수 없는 동작(실제 로드 중 발생한 리다이렉션) 정보를 동적으로 측정해야 합니다. WebView의 네비게이션 콜백(onPageStarted 등)을 관찰해 실시간 네비게이션 변화를 카운트합니다.
- 구현 개요 (app/src/main/java/com/example/a1/MainActivity.kt):
   - 새 필드: `dynamicTotalRedirects` (총 리다이렉트), `dynamicExternalRedirects` (외부로의 리다이렉트), `lastNavigationUrlForDynamicCounters` (이전 네비게이션 URL 저장)
   - 샌드박스 시작 시 초기화
   - `onPageStarted`에서 이전 URL과 현재 URL을 비교: 다르면 `dynamicTotalRedirects++`. 이전/현재 호스트가 다르면 `dynamicExternalRedirects++`.
   - 카운트는 `analyzeAndDisplayPhishingResult()`에서 기존 JS 피처와 병합되어 모델에 전달됩니다:
      - `nb_redirection` ← dynamicTotalRedirects
      - `nb_external_redirection` ← dynamicExternalRedirects
      - `ratio_intRedirection` / `ratio_extRedirection` 계산(총합 0이면 0)

### 5) ratio_extRedirection의 의미와 한계
- 의미: 외부(다른 호스트) 리다이렉트 카운트 / 총 리다이렉트 카운트 (0.0 ~ 1.0)
- 한계:
   - WebView 콜백은 자동 리다이렉트 vs 사용자 클릭으로 인한 네비게이션을 자동 분류하지 않습니다.
   - 일부 플랫폼/웹페이지에서는 onPageStarted 호출 패턴이 달라서 세밀한 튜닝이 필요합니다.
   - 완전한 리다이렉트 체인을 얻으려면 서버-사이드 로깅 또는 네트워크 프로시를 도입해야 합니다.

### 6) 리소스 에러 비율 (가능한 다음 단계)
- `ratio_intErrors` / `ratio_extErrors`는 아직 JS에서는 구현되지 않았습니다(초기 구현은 null). Android `WebViewClient.onReceivedError` 와 `onReceivedHttpError`를 사용하면 런타임에 실패한 리소스들을 카운트해서 내부/외부 실패 비율을 계산할 수 있습니다.

### 7) 런타임 검증 & 디버깅
1. 로그 확인 (ADB)
    - RAW features JSON (WebFeatureExtractor 로그): Android 로그에 `RAW_FEATURES_JSON`로 남습니다.
    - dynamic redirect 로그: MainActivity는 `dynamic redirects total=X external=Y` 로그를 남깁니다.
    - 예시:
```bash
adb logcat | grep WebFeatureExtractor
adb logcat | grep "dynamic redirects"
```

2. 테스트 케이스
    - 리다이렉트가 있는 URL(A → B → C)을 샌드박스에서 로드해, ADB 로그가 기대하는 카운트를 출력하는지 확인하세요.
    - `RAW_FEATURES_JSON`와 병합된 피처 맵이 일치하는지 확인: `nb_redirection`, `nb_external_redirection`, `ratio_extRedirection` 값들이 예상대로 채워지는지 확인합니다.

### 8) 모델·학습 관련 권장 사항
- 동적 피처를 사용하기 전에 학습 데이터에 동일한 방식으로 생성된 dynamic features가 포함되어야 합니다. 현재 스크립트와 모바일 병합 방식에 맞춘 CSV/feature_info.json이 repository에 포함되어 있습니다.
- 결측값(null) 처리 전략(권장): missing-indicator (explicit missing flag) 또는 모델 교육 시 median/mean 대체 + missing indicator를 함께 사용해 모바일 null과 훈련 데이터 처리를 일치시켜야 합니다.

---

## 다음 권장 작업 (우선순위)
1. `ratio_intErrors` / `ratio_extErrors` 런타임 구현 — `WebViewClient.onReceivedError` 기반 (권장) ✅
2. 자동 리다이렉트 vs 사용자 유도 네비게이션 분리(플랫폼 API 만으로 가능한 경우) — 모델 신뢰도 개선에 도움
3. 서버-사이드 통합을 통한 WHOIS / 트래픽 / page-rank 수집(보안·프라이버시 검토 필요)
4. 모델을 동적/결측치 정책에 맞춰 재학습하고 새로운 TFLite 모델 배포

원하시면 위 항목 중 제가 바로 (1) 또는 (1)+(2) 구현을 진행해 드리겠습니다 — 어떤 것을 먼저 할까요?

---

## Feature Reference — 79 Features (완전 정리)
아래 표는 이 프로젝트에서 수집/모델 입력에 포함되는 79개의 피처를 완전하게 문서화합니다. 각 행은 피처 이름, 간단한 설명, 값의 타입/범위, 런타임(동적) 여부, 결측값(null) 처리 권장 방식을 담고 있습니다. 이 표는 모델학습/테스트/운영시 참고용으로 사용하세요.

Note: 값 타입은 일반적으로 정수(int count), 이진(0/1), 또는 비율(0.0–1.0)입니다. 모바일 측에서 수집되지 않거나 불확실한 경우 null로 표기됩니다 — 모델 훈련 시 동일한 결측 정책을 사용해야 합니다.

| # | feature name | description | type / range | dynamic? | notes / preprocessing suggestions |
|---:|---|---|---:|:---:|---|
| 1 | length_url | Full URL length | int >=0 | No | scale/normalize (log or min-max) |
| 2 | length_hostname | Hostname length | int >=0 | No | scale/normalize |
| 3 | ip | Host looks like an IP address (v4/v6) | 0/1 | No | binary |
| 4 | nb_dots | Count of '.' characters | int >=0 | No | normalize |
| 5 | nb_hyphens | Count of '-' characters | int >=0 | No |
| 6 | nb_at | Count of '@' characters | int >=0 | No | suspicious if >0 |
| 7 | nb_qm | Count of '?' characters | int >=0 | No |
| 8 | nb_and | Count of '&' characters | int >=0 | No |
| 9 | nb_or | Count of '|' characters | int >=0 | No |
|10 | nb_eq | Count of '=' characters | int >=0 | No |
|11 | nb_underscore | Count of '_' characters | int >=0 | No |
|12 | nb_tilde | Count of '~' characters | int >=0 | No |
|13 | nb_percent | Count of '%' characters | int >=0 | No | high values often suspicious |
|14 | nb_slash | Count of '/' characters | int >=0 | No |
|15 | nb_star | Count of '*' characters | int >=0 | No |
|16 | nb_colon | Count of ':' characters | int >=0 | No |
|17 | nb_comma | Count of ',' characters | int >=0 | No |
|18 | nb_semicolumn | Count of ';' characters | int >=0 | No |
|19 | nb_dollar | Count of '$' characters | int >=0 | No |
|20 | nb_space | Count of spaces in URL | int >=0 | No |
|21 | nb_www | Occurrences of 'www' in URL | int >=0 | No | often benign but not definitive |
|22 | nb_com | Occurrences of '.com' | int >=0 | No |
|23 | nb_dslash | Occurrences of '//' | int >=0 | No | used for detecting deceptive patterns |
|24 | http_in_path | 'http' substring inside path | 0/1 | No | suspicious token inside path |
|25 | https_token | 'https' token present in url string | 0/1 | No | 'https' in host/path is suspicious as token |
|26 | ratio_digits_url | Fraction of chars that are digits (URL) | 0.0–1.0 | No | handle divide-by-zero = 0 |
|27 | ratio_digits_host | Fraction of digits in hostname | 0.0–1.0 | No | high fraction may indicate random/auto-gen domain |
|28 | punycode | Host contains 'xn--' (Punycode) | 0/1 | No | IDN homograph risk indicator |
|29 | port | URL contains explicit port | 0/1 | No | may indicate non-standard hosting |
|30 | tld_in_path | Known TLD string appears in path tokens | 0/1 | No |
|31 | tld_in_subdomain | TLD-like token in subdomain | 0/1 | No | suspicious if subdomain contains tld-like tokens |
|32 | abnormal_subdomain | Very long / many labels / many digits in subdomain | 0/1 | No | heuristic flag for abnormal subdomain |
|33 | nb_subdomains | Number of subdomain labels (depth) | int >=0 | No | normalize; large count often suspicious |
|34 | prefix_suffix | '-' present in hostname (prefix/suffix) | 0/1 | No | common in phishing domains |
|35 | random_domain | Heuristic: many consonants (likely randomized) | 0/1 | No |
|36 | shortening_service | Host matches known shortener list | 0/1 | No | shorteners often used for obfuscation |
|37 | path_extension | Path ends with suspicious extension (.php/.exe/.zip etc.) | 0/1 | No | binary flag |
|38 | nb_redirection | Number of redirects observed | int >=0 | Yes (dynamic) | collected via nav timing + Android dynamic counters; if unknown may be null |
|39 | nb_external_redirection | Number of redirects to other hosts | int >=0 | Yes (dynamic) | dynamicExternalRedirects on Android; null if unavailable |
|40 | length_words_raw | Number of tokens in path | int >=0 | No | token count extracted from path |
|41 | char_repeat | Count of repeated-char sequences | int >=0 | No | repeated characters often suspicious (aaa, !!!) |
|42 | shortest_words_raw | Shortest token length in path | int >=0 | No | safeMin applied (0 when no tokens) |
|43 | shortest_word_host | Shortest token length in host | int >=0 | No |
|44 | shortest_word_path | Shortest token length in path | int >=0 | No |
|45 | longest_words_raw | Longest token length in URL / path | int >=0 | No | safeMax handles numeric arrays as well |
|46 | longest_word_host | Longest token length in host | int >=0 | No |
|47 | longest_word_path | Longest token length in path | int >=0 | No |
|48 | avg_words_raw | Average token length in URL/path | float >=0 | No | safeAvg returns 0 when empty |
|49 | avg_word_host | Average host token length | float >=0 | No |
|50 | avg_word_path | Average path token length | float >=0 | No |
|51 | phish_hints | Count of suspicious keywords in body (login, verify, bank, etc.) | int >=0 | No | language-sensitive list; consider localization |
|52 | domain_in_brand | Domain contains known brand keywords | 0/1 | No | watch for brand impersonation |
|53 | brand_in_subdomain | Brand present in subdomain | 0/1 | No |
|54 | brand_in_path | Brand present in path | 0/1 | No |
|55 | suspecious_tld | Host TLD in suspicious list (xyz/top/icu) | 0/1 | No | TLD based risk marker |
|56 | nb_hyperlinks | Number of anchor tags (<a>) | int >=0 | No | normalization recommended |
|57 | ratio_intHyperlinks | fraction of anchors internal to host | 0.0–1.0 | No | 0 if no anchors |
|58 | ratio_extHyperlinks | fraction of anchors external to host | 0.0–1.0 | No |
|59 | ratio_nullHyperlinks | fraction of invalid/empty/anchor-href-like links | 0.0–1.0 | No |
|60 | ratio_intRedirection | fraction of redirects that are internal | 0.0–1.0 | Yes | computed from dynamic counters—0 if total redirects == 0 |
|61 | ratio_extRedirection | fraction of redirects to external hosts | 0.0–1.0 | Yes | computed from dynamic counters—0 if total redirects == 0 |
|62 | ratio_intErrors | fraction of internal resource errors | 0.0–1.0 or null | Dynamic (not implemented in JS) | can be implemented via onReceivedError/onReceivedHttpError in Android |
|63 | ratio_extErrors | fraction of external resource errors | 0.0–1.0 or null | Dynamic | same as above |
|64 | login_form | Page contains login form (username + password) | 0/1 | No | DOM analysis checks input types/names |
|65 | external_favicon | favicon link points to external host | 0/1 | No | presence of external favicon may indicate copycat sites |
|66 | links_in_tags | # of links referenced in tag attributes (href/src/meta content) | int or null | No (JS: null by default) | define and implement consistently if used in training |
|67 | submit_email | Forms include an email input (submit to email) | 0/1 | No |
|68 | ratio_intMedia | fraction of media resources hosted internally | 0.0–1.0 | No | counts img/video/audio/source tokens, internal fraction |
|69 | ratio_extMedia | fraction of media hosted externally | 0.0–1.0 | No |
|70 | sfh | Ratio of suspicious form-handler actions (empty/#/external) | 0.0–1.0 | No | SFH (form action handler) metric, 0 if no forms |
|71 | iframe | Number of <iframe> elements | int >=0 | No |
|72 | popup_window | Count of links opening new windows (target=_blank or window.open) | int >=0 | No |
|73 | safe_anchor | 1 - (ratio_nullHyperlinks) — fraction of useful anchors | 0.0–1.0 | No |
|74 | onmouseover | Page uses onmouseover handlers | 0/1 | No | often used to hide malicious actions |
|75 | right_clic | oncontextmenu / custom right-click behavior found | 0/1 | No |
|76 | empty_title | document.title is empty | 0/1 | No | suspicious when title empty |
|77 | domain_in_title | title contains the domain name | 0/1 | No | inconsistent branding can be suspicious |
|78 | domain_with_copyright | page contains © and hostname together | 0/1 | No | may indicate more legitimate site, but not guaranteed |
|79 | nb_extCSS | Number of external CSS link elements | int >=0 | No | useful as a content-sourcing signal |

Notes on pre-processing & missing data:
- For integer-count features normalize via log(1 + x) or min-max scaling; ratios already 0..1.
- For boolean/binary features keep as 0/1 floats.
- For null values — use the same missing strategy in training as in serving. Preferred approach: add explicit missing-indicator columns in the training data (eg `feature_is_missing`) or use median imputation + missing-indicator columns.

If you want, I can append a CSV-compatible feature documentation file (phishing/feature_reference.md or .csv) with the same contents to make it machine-readable for training/data pipelines.
