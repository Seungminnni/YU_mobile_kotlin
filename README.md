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
app/
 └─ src/main/
     ├─ java/com/example/a1/MainActivity.kt   # UI, 카메라, 샌드박스, 탐지 로직
     ├─ res/layout/activity_main.xml          # 카메라 프리뷰·WebView·결과 패널
     └─ res/values/*                          # 문자열, 테마
build.gradle.kts                              # CameraX, ML Kit 의존성
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
