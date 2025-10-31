# Phishing Detection Dataset

이 폴더는 피싱 웹사이트 탐지를 위한 데이터셋과 관련 파일들을 포함합니다. 데이터는 CSV 형식으로 제공되며, URL 기반 특징, HTML 분석, WHOIS 정보 등을 포함합니다.

## 파일 목록

### 1. last.csv
- **크기**: 5,344 행 × 31 열
- **설명**: 훈련/검증용 데이터. 정상 및 피싱 사이트의 혼합 데이터.
- **주요 열**:
  - `url`: 대상 URL
  - `label`: 분류 라벨 (0: 정상, 1: 피싱)
  - `domain`: 도메인 이름
  - `remote_ip_*`: IP 관련 정보 (ASN, 국가, ISP)
  - `security_*`: SSL 인증서 정보
  - `whois_*`: 도메인 등록 정보
  - `features.*`: CSS/HTML/TEXT 특징 (JSON 형태)
- **특징**: 정상 사이트가 99.98%로 대부분. 도메인 나이 평균 5,731일.

### 2. phishing_data.csv
- **크기**: 11,481 행 × 89 열
- **설명**: 피싱 탐지 모델 학습용 특징 데이터. URL과 HTML 기반 특징이 풍부.
- **주요 열**:
  - `url`: 대상 URL
  - URL 특징: `length_url`, `nb_dots`, `ratio_digits_url` 등
  - HTML 특징: `nb_hyperlinks`, `login_form`, `iframe` 등
  - WHOIS/트래픽: `domain_age`, `web_traffic`, `google_index`, `page_rank`
  - `status`: 타겟 라벨 (phishing/legitimate)
- **특징**: 균형 잡힌 데이터. 웹 트래픽 최대 10M 이상.

### 3. test_not-phishing.csv
- **크기**: 5,244 행 × 29 열
- **설명**: 정상 사이트 테스트 데이터. 피싱 라벨 없음.
- **주요 열**: `last.csv`와 유사하지만 `label` 및 `brands` 제외.
- **특징**: 도메인 나이 평균 5,798일.

### 4. test_phishing.csv
- **크기**: 5,151 행 × 30 열
- **설명**: 피싱 사이트 테스트 데이터.
- **주요 열**: `last.csv`와 유사, `brands` 열 추가 (브랜드 정보 포함).
- **특징**: 도메인 나이 평균 2,293일 (피싱 사이트 특징).

## 사용 방법
1. Python에서 `pandas`로 로드: `df = pd.read_csv('파일명.csv')`
2. 특징 선택: 숫자 열을 모델 입력으로 사용.
3. 모델 학습: `phishing_data.csv`로 학습, 테스트 파일로 평가.

## 분석 인사이트
- 피싱 사이트는 도메인 나이가 짧고, 트래픽이 낮은 경향.
- 누락 데이터: `whois_registrar` 열은 모두 NaN.
- 추천: 누락 데이터를 처리하거나, 특징 엔지니어링으로 모델 개선.

## 노트북 파일
- `data preprocessing.ipynb`: 데이터 전처리 및 모델 학습 코드.
- `Untitled.ipynb`: 간단한 CSV 기반 모델 학습 코드.

문의: 데이터 관련 질문은 이슈로 남겨주세요.
