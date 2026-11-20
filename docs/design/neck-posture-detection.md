# 거북목(Text-Neck) 자세 측정 — 구현 가능성, 계획, 설계

> 상태: **설계 v2** (구현·빌드·측정·커밋 전) · 2026-11-16
> 대상: Time To Stretch 의 Sensing → Estimation 단계, **Neck-related Debt `D_n`**
> 방향: **SmartPose(2013)를 베이스**로 하고, **문제 1(폰 각도 ≠ 목 각도)은 Watanabe & Nakamura(2025)** 방법으로 보완. **문제 2(몸통 각도)는 추후 과제**로 두고 가정·제외 규칙으로만 처리.

---

## 0. 요약

| 항목 | 결정 |
| --- | --- |
| 측정 지표 | **목 각도 θ_neck** = 귀(이주)–어깨(견봉) 선이 **수직선**과 이루는 각 (Watanabe 정의, 0° = 귀가 어깨 바로 위) |
| 모드 A — 상시(백그라운드) | SmartPose: 폰 기울기 → 분 단위 CATA → 숙임 판정 → `D_n`. 카메라 없음 |
| 모드 B — 정밀(앱 화면이 열려 있을 때) | Watanabe: 전면 카메라 + 센서 특징 3개 → Extra Trees 회귀 → θ_neck (°) |
| 두 모드 연결 | 모드 B 에서 모은 (폰 기울기, 추정 목 각도) 쌍으로 **사용자별 보정식 g(θ_phone) → θ_neck** 를 학습해 모드 A 결과를 각도(°)로 환산 |
| 문제 2 | **가정 A1: 몸통은 대략 수직(서기·앉기)**. 명백한 위반(누움 등)은 감지해 측정에서 제외하고 로그만 남김 |

**구현 가능성 결론**
- 모드 A: **가능** (v1 판단 그대로. 포그라운드 서비스 + 화면 상태 게이트 필요).
- 모드 B: **조건부 가능**. Android 에서 필요한 모든 부품(얼굴 pitch, 코·눈·어깨 키포인트, 폰 기울기, Extra Trees 추론)을 온디바이스로 구할 수 있다. 다만 ① **카메라는 앱이 포그라운드일 때만** 쓸 수 있고, ② **전면 카메라에 어깨가 잡혀야** 하며(최대 위험), ③ 논문 수준 정확도(MAE ≈ 7–9°)도 **우리가 직접 정답 데이터를 수집·학습**해야 얻을 수 있다.
- 정확도 기대치: 논문 기준 공통 모델 R² 0.70 / MAE 7.9°, 사용자별 모델 R² 0.75 / MAE 6.9° (Extra Trees). "몇 도 단위의 정밀 측정"보다는 **10° 구간 수준의 단계 판정**이 현실적 목표.

---

## 1. 참고 방법

### 1.1 SmartPose (베이스)
Lee, Choi, Lee, Shim. *Smart Pose: Mobile Posture-aware System for Lowering Physical Health Risk of Smartphone Users.* CHI EA 2013, 2257–2266.

- 가설: 손에 든 폰을 볼 때 사용자는 시야각을 유지하려 하므로 폰 기울기와 목 기울기가 양의 상관을 가진다.
- 흐름: 5초 모니터링 주기마다 (얼굴 검출 + 흔들림 카운트 → 조건 만족 시 폰 기울기 기록), 60초 사이클마다 (흔들림 합 ≥ 임계 → 평균 기울기 CATA 계산 → 범위 밖이면 알림).
- `CATA = Σ|TiltAngle_i + 90| / n` — 폰이 수직이면 0°, 평평하면 90°.
- 검증(12명, BackMapper 흉추각): 숙인 자세 CATA ≈ 65°, 바른 자세 ≈ 15°. 숙인 자세 상관 r = 0.84(서기)/0.69(앉기), 바른 자세 0.47/0.03. 비정상 범위 **56.12°–75.34°**.
- 한계: 정답이 목이 아니라 흉추각, 폰–목 상대 각도 미반영(문제 1), 몸통 각도 미고려(문제 2), 얼굴 검출은 앱 안에서만 동작.

### 1.2 Watanabe & Nakamura 2025 (문제 1 보완)
*Can We Prevent "Text Neck" Using Only a Smartphone? Real-Time Neck Angle Estimation and a Serious Game as a Case Study.* OzCHI 2025. DOI 10.1145/3764687.3764720. 공개 코드: `kntWT/posture-correction-backend` (브랜치 `ozchi-2025`).

**핵심 아이디어** — 폰 기울기, 폰 대비 얼굴 각도, 목 굴곡을 **분리**한다. 폰이 고정돼 있으면, 목을 숙일수록 전면 카메라 이미지에서 **코–목 밑점 거리**가 줄어든다.

**특징 (코드 기준 최종 3개)**

| 특징 | 정의 | 출처 |
| --- | --- | --- |
| `ratio` | `(Dist_neck / Dist_eyes) / Norm_ref` — 코–목 밑점 거리 ÷ 두 눈 사이 거리, 그것을 기준 자세 값으로 나눔 | OpenPose 키포인트 (nose, neck, eyes) |
| `phone_tilt` | 폰의 지면 대비 기울기. 코드상 `90 − beta` (수직 0°, 평평 90° — SmartPose CATA 와 같은 기준) | 기기 방향 센서 |
| `face_pitch` | 폰(카메라)에 대한 얼굴 pitch | head pose 추정(6DRepNet) |

- 입력 필터(코드): `−10 ≤ phone_tilt ≤ 100`, `|face_pitch| ≤ 100`, 폰 alpha/gamma, 얼굴 yaw/roll 모두 `|·| ≤ 45°`. 키포인트 신뢰도: 코·눈 ≥ 0.7, 목 ≥ 0.2. 얼굴 pose 신뢰도 < 0.9 제외.
- 기준 자세(캘리브레이션): 폰이 거의 수직(±3°)이고 얼굴이 폰을 정면으로 봄(pitch/roll/yaw ±3°)이며 눈–목 키포인트가 잡힐 때만 `Norm_ref` 저장.
- 모델: `ExtraTreesRegressor(n_estimators=100, max_depth=20)` → **ONNX 로 변환**해 추론. 표준화(StandardScaler) 후 입력.
- 정답: 측면 외부 카메라 + **견봉·이주에 AR 마커** → 선의 수직 대비 기울기. 30 fps 폰 데이터와 15 fps 외부 카메라를 **40 ms 이내** 타임스탬프로 매칭.
- 데이터: 5명(20–22세, 서서), 1인 5세션 × 약 7분, 총 84,374 샘플. 목표 각도 안내를 따라 목·얼굴·폰 각도와 거리를 다양하게 바꿈. 폰이 눈높이보다 높은 경우 제외.
- 결과(세션 단위 5-fold): Random Forest R² 0.61 / MAE 8.7°, **Extra Trees R² 0.70 / MAE 7.9°**, 사용자별 Extra Trees **R² 0.75 / MAE 6.9°**. 0°·60° 극단에서 약함, 0° 가 10° 로 자주 예측됨.
- 한계: 폰이 거의 수평이면 목 밑점을 가슴으로 오검출. pitch 만 사용(roll/yaw 미사용). 실험 중 얼굴 미검출로 게임이 자주 멈춤. 추론은 **GPU 서버**에서 수행(온디바이스 아님). 서 있는 자세만 수집 → 몸통 수직을 암묵적으로 가정.
- 참고: 공개 코드의 교차검증은 테스트 fold 에도 `fit_transform` 으로 표준화한다. 우리는 **학습 fold 로만 fit** 한다.
- 코드 라이선스가 명시되지 않음(NOASSERTION) → **코드는 복사하지 않고 방법만 참고**한다.

---

## 2. 문제 정의와 범위

### 2.1 지표
- **θ_neck**: 귀(이주)–어깨(견봉) 선과 수직선 사이 각도. 0° 중립, 앞으로 숙일수록 증가. Watanabe 와 같은 정의라 그 방법·결과를 그대로 비교할 수 있다.
- 중력 기준 지표이므로 Hansraj(2014) 하중 곡선(제안서 인용, 0°≈5 kg … 60°≈27 kg)과 바로 연결된다.
- 단계 구분(초기값, 문헌 기반): **0–15° 양호**(Namwongsa 등: 0–15° 권장, Watanabe 재인용), **15–30° 주의**, **30–45° 나쁨**, **45°+ 매우 나쁨**. 파일럿 후 조정.

### 2.2 가정과 범위 밖
| 구분 | 내용 |
| --- | --- |
| **가정 A1 (문제 2 보류)** | 몸통이 대략 수직(서기·앉기)이다. SmartPose·Watanabe·Lee 2015 모두 같은 전제(암묵적), Pose-on-the-Go(2021)는 명시적으로 같은 가정 |
| A1 위반 감지 | 5초 윈도우마다 판정(§4.2). 폰 기울기 < −15°(화면이 아래를 향함 → 누워서 위로 봄) → 바로 누움. 화면이 거의 수직(\|기울기\| ≤ 60°)인데 **콘텐츠 roll**(화면 내용의 위쪽과 중력 위쪽 사이 각)이 60–120° → 옆으로 누움. 사이클의 절반 이상이 누운 윈도우면 `SUPINE_LIKE` / `SIDE_LYING_LIKE`. 둘 다 측정 제외, 로그만. 얼굴 roll 은 쓰지 않는다: 옆으로 누운 사용자는 폰을 얼굴에 맞춰 들어서 이미지 속 얼굴 roll 은 ≈ 0° 이다. 엎드림은 앉아서 숙인 자세와 폰 기울기가 겹쳐서 보류(로그 확인 후 결정). 기대어 누움은 바른 자세와 구분 불가 (2026-11-19) |
| 범위 밖 | 몸통 기울기 연속 추정, 몸통 대비 경추 굴곡, 머리 전방 이동(CVA), 폰이 눈높이보다 높은 경우, 백그라운드 카메라 측정, 목 신전(위로 젖힘, θ_neck < 0°) → §10 |

---

## 3. 구현 가능성

### 3.1 모드 A (SmartPose) — 가능

| 구성요소 | 판단 | 구현 |
| --- | --- | --- |
| 5초/60초 주기, CATA | ✅ | 그대로 |
| 폰 기울기 | ⚠️ 수정 | `TYPE_ORIENTATION` deprecated → `TYPE_GRAVITY` 로 `θ = atan2(g_z, √(g_x²+g_y²))`. 세로·가로 구분 불필요 |
| 흔들림(거치대 배제) | ✅ | `TYPE_LINEAR_ACCELERATION` 크기의 5초 최대값 < 임계면 정지(거치대·책상) 구간 |
| 얼굴 검출로 "사용 중" 판정 | ❌ 백그라운드 카메라 불가 | 화면 켜짐 + 잠금 해제 + 통화·근접 제외로 대체 |
| 백그라운드 센서 | ⚠️ | Android 9+ 백그라운드 앱은 연속 센서 수신 불가 → **포그라운드 서비스(type `health`)**. `FOREGROUND_SERVICE_HEALTH` + 기존 `ACTIVITY_RECOGNITION` 권한으로 요건 충족 |
| 임계값 56–75° | ⚠️ | 기본값으로만 사용, 모드 B 결과로 사용자별 보정(§4.5) |

### 3.2 모드 B (Watanabe) — 조건부 가능

| 논문 구성요소 | Android 온디바이스 대응 | 판단 | 비고 |
| --- | --- | --- | --- |
| 카메라 프레임 | CameraX `ImageAnalysis` (전면, 640×480, 5–10 fps) | ✅ | 포그라운드 화면에서만 |
| 얼굴 pitch (6DRepNet) | **ML Kit Face Detection** `headEulerAngleX/Y/Z` (대안: MediaPipe Face Landmarker 변환행렬) | ✅ | 모델이 달라 정확도 차이 가능 → E4 에서 확인 |
| 코·눈 키포인트 | ML Kit Face 랜드마크(NOSE_BASE, LEFT/RIGHT_EYE) 또는 ML Kit Pose(0, 2, 5) | ✅ | |
| **목 밑점 (OpenPose `neck`)** | ML Kit Pose 에는 neck 이 없음 → **양 어깨(11, 12) 중점**으로 대체 (OpenPose neck 도 사실상 어깨 중점) | ⚠️ **최대 위험** | 전면 카메라 화각에 어깨가 잘리면 특징 자체가 없음. 논문도 폰이 수평일 때 오검출 보고. **E0 에서 검출률을 먼저 확인(Go/No-go)** |
| 폰 기울기 | `TYPE_GRAVITY` 로 모드 A 와 같은 θ_phone | ✅ | 논문 정의(90 − beta)와 동일 기준 |
| 기준 자세 `Norm_ref` | 수집 화면의 "기준 자세 측정" 버튼: 3초 동안 조건(폰 기울기 ±15°, 얼굴 pitch ±10°, 양 어깨 신뢰도 ≥ 0.5) 만족 프레임의 중앙값 저장 (§4.4) | ✅ | 논문(±3°)보다 완화 |
| Extra Trees 추론 | Python 학습 → **ONNX** (`skl2onnx`) → **ONNX Runtime Android** | ✅ | 논문 코드도 ONNX 사용. 모델 크기 줄이려면 트리 수·깊이 축소. 대안: 트리 배열을 JSON 으로 내보내 Kotlin 에서 직접 평가 |
| 정답 데이터 (AR 마커 + 측면 카메라) | 두 번째 폰을 삼각대에 고정해 측면 촬영, **ArUco 마커**를 견봉·이주에 부착 → Python/OpenCV 로 각도 계산 | ✅ | 장비 구매 불필요. 동기화 방법 필요(§5.3) |
| 실시간 처리 | ML Kit Face + Pose(base) 각 프레임 수십 ms 수준 예상 | ⚠️ | 5 fps 로 낮춰도 1분 단위 판정엔 충분. E3 에서 확인 |

**카메라 사용 범위**: 전면 카메라는 우리 앱이 화면에 떠 있을 때만 쓴다(캘리브레이션, "자세 측정" 화면, 스트레칭 검증 화면). 다른 앱 사용 중 카메라 측정(카메라 포그라운드 서비스)은 상시 카메라 표시등·배터리·사생활 부담이 커서 **이번 범위에서 제외**한다. 실사용에서 얼굴 전체가 전면 카메라에 보이는 비율이 약 29%라는 보고(Khamis et al., CHI 2018)도 같은 결론을 지지한다.

### 3.3 결론
- 모드 A 는 v1 설계대로 바로 구현 가능.
- 모드 B 는 기술적으로 가능하지만 **(1) 어깨 검출률, (2) 정답 데이터 수집, (3) ML Kit 특징의 품질** 세 가지가 성패를 가른다. 그래서 **E0(어깨 검출률) → 데이터 수집 → 학습** 순서로, 앞 단계 결과를 보고 다음 단계 진행 여부를 결정한다.
- E0 가 실패하면(어깨가 거의 안 잡힘) 대안: `ratio` 를 빼고 `phone_tilt + face_pitch (+ face roll/yaw, 눈 사이 거리)` 만으로 학습. 논문 근거는 없으므로 우리 데이터로 성능을 따로 보고한다.

---

## 4. 설계

### 4.1 전체 구조

```
                         ┌────────────────────────────── 앱 화면 (포그라운드) ──────────────────────────────┐
                         │ CalibrationScreen · PostureCheckScreen · (추후) StretchVerificationScreen          │
                         │   CameraX ─► FaceFeatureExtractor (ML Kit Face) ─┐                               │
                         │          └► BodyFeatureExtractor (ML Kit Pose) ──┼► NeckFeatureBuilder ─► NeckAngleModel (ONNX) ─► θ_neck
                         │   SensorSampler(θ_phone) ────────────────────────┘                     │          │
                         └───────────────────────────────────────────────────────────────────────┼──────────┘
                                                                                                 ▼
                                                                      PersonalMappingStore: (θ_phone, θ_neck) 쌍 → g()
                                                                                                 │
NeckMonitorService (포그라운드 서비스, type=health) ── 모드 A ────────────────────────────────────┤
  ScreenStateMonitor → SensorSampler → PostureWindowAggregator(5s/60s) → CycleResult(CATA) ─► g(CATA) ─► NeckDebtCalculator ─► D_n
                                                                                                 │
                                                                    NeckLogStore (CSV) · NeckStateRepository (SharedPreferences)
```

- 패키지 `com.example.tts.neck`. 하위: `sensing/`, `camera/`, `model/`, `debt/`, `data/`, `ui/`.
- **순수 로직**(Android 의존성 없음, JVM 단위 테스트): `TiltCalculator`, `PostureWindowAggregator`, `NeckFeatureBuilder`, `PersonalMapping`, `NeckDebtCalculator`.
- `SedentaryTracker`(feature/sedentary-detection)는 읽기만 한다.

### 4.2 모드 A 파이프라인 (SmartPose)

**기울기**: `θ_phone = atan2(g_z, √(g_x² + g_y²))` [°]. 수직 0°, 화면이 위로 평평 90°, 화면이 아래를 향하면 음수.

**5초 윈도우**: `screenInUse`(화면 켜짐 && 잠금 해제), `excluded`(통화 중, 근접센서 가림 — 사유 기록), `θ` 평균·표준편차, 선형가속도 크기 평균·최대. 선형가속도 최대 < `T_static`(0.15 m/s²)이면 `static`(거치대·책상) 구간으로 보고 CATA 계산에서 뺀다. 유효 윈도우 = 사용 중 && 제외 아님 && 정지 아님.

**윈도우 자세** (유효 윈도우만): 콘텐츠 roll = 화면 평면의 중력 성분 (g_x, g_y) 를 화면 회전(`Display.rotation` × 90°)만큼 되돌린 뒤 5초 벡터 평균의 `atan2(x, y)`. 0° = 내용이 중력 기준으로 똑바름, ±90° = 옆으로 누움. 회전 잠금·자동 회전·가로 영상 모두 같은 기준이 된다.

| 조건 | 윈도우 자세 |
| --- | --- |
| θ 평균 < −15° | `SUPINE` |
| \|θ 평균\| ≤ 60° && \|콘텐츠 roll\| ∈ [60°, 120°] | `SIDE_LYING` |
| 그 외 | `TRUNK_UPRIGHT` |

CATA 는 `TRUNK_UPRIGHT` 윈도우로만 계산한다(누운 윈도우가 섞여 평균이 끌려가는 것을 막음). 모드 B 카메라 측정도 `TRUNK_UPRIGHT` 윈도우에서만 시작해 `g()` 학습 쌍에 누운 자세가 들어가지 않게 한다.

**60초 사이클 상태**

| 조건 | 상태 |
| --- | --- |
| 유효 윈도우 < `N_valid_min`(6), 정지 구간을 빼서 6 미만이 된 경우 | `STATIC_DEVICE` (거치대·책상) |
| 유효 윈도우 < `N_valid_min`(6), 그 외 | `NOT_IN_USE` |
| `SUPINE` + `SIDE_LYING` 윈도우 ≥ 유효 윈도우의 절반 | 더 많은 쪽: `SUPINE_LIKE` / `SIDE_LYING_LIKE` (같으면 `SUPINE_LIKE`). A1 위반, 제외 |
| `g(CATA)` ≥ 단계 경계 | `BENT_MILD` / `BENT` / `BENT_SEVERE` (§2.1 단계) |
| 그 외 | `UPRIGHT` |

`g()` 가 아직 없으면(캘리브레이션 전) SmartPose 기본 임계 56° 하나로 `BENT` / `UPRIGHT` 만 판정한다. **현재 구현(P1)은 이 방식이다** (`NeckDebtCalculator`).

### 4.3 모드 B 파이프라인 (Watanabe)

1. **프레임 수집**: CameraX 전면, 5–10 fps, 같은 타임스탬프의 `θ_phone`·폰 roll/yaw 를 함께 붙임.
2. **특징 추출**
   - ML Kit Face → `face_pitch`, `face_yaw`, `face_roll`, 두 눈 좌표, 코 좌표.
   - ML Kit Pose → 어깨 11·12 좌표와 `inFrameLikelihood`. `neck = 두 어깨 중점`.
   - `ratio = (|nose − neck| / |eyeL − eyeR|) / Norm_ref`.
3. **필터** (논문 코드 기준): `−10° ≤ θ_phone ≤ 100°`, `|face_pitch| ≤ 100°`, 폰·얼굴 yaw/roll `|·| ≤ 45°`, 키포인트 신뢰도 기준 미달 시 버림. 탈락 사유를 로그에 남긴다(E0 분석용).
4. **추론**: 표준화 → Extra Trees(ONNX) → `θ_neck` [°].
5. **평활화**: 1초 단위 중앙값, 화면 표시는 3초 이동 중앙값(논문 사용자들이 "작은 움직임에 결과가 튄다"고 보고).
6. **세션 요약**: 측정 세션 동안 θ_neck 분포, 단계별 시간 비율. 같은 시간대의 `(θ_phone 1분 평균, θ_neck 1분 중앙값)` 쌍을 `PersonalMappingStore` 에 저장.

### 4.4 캘리브레이션 (모드 B 기준 자세)
1. 벽에 등과 머리를 대고 서서(또는 등받이에 등을 붙이고 앉아서) 폰을 눈높이에 수직으로 든다.
2. 수집 화면의 "기준 자세 측정" 버튼을 누르면 3초 동안 조건을 만족한 프레임만 모은다: 폰 기울기 ±15°, 얼굴 pitch ±10°, 얼굴(코·두 눈) 검출, 양 어깨 신뢰도 ≥ 0.5.
3. 3초 후 모은 프레임의 `코–목 / 눈 사이` 중앙값을 `Norm_ref` 로 SharedPreferences 에 저장. 조건 만족 프레임이 5개 미만이면 실패 토스트를 띄우고 재시도.
4. (미구현) 이어서 "평소처럼 폰 보기" 30초 → 첫 매핑 데이터 쌍과 모드 A 기본 임계를 함께 얻는다.

- 논문 조건과의 차이: 논문은 폰 수직 ±3°, 얼굴 pitch/roll/yaw ±3° 를 요구한다. 손으로 들고 맞추기 쉽도록 완화했고(2026-11-16 결정), 얼굴 roll/yaw 조건은 쓰지 않는다. 보정값이 불안정하면 조건을 다시 조인다.

### 4.5 두 모드 연결: 사용자별 보정 `g()`
- 데이터: 모드 B 세션에서 모인 `(θ_phone, θ_neck)` 1분 쌍.
- 모델: 처음엔 **단순 선형 회귀** `θ_neck ≈ a·θ_phone + b` (SmartPose 도 선형 회귀 사용). 쌍이 20개 이상 쌓이면 구간별 선형(piecewise)으로 교체.
- 적용: 모드 A 의 분 단위 CATA 를 `g(CATA)` 로 바꿔 단계 판정과 `D_n` 가중치에 사용. 회귀의 잔차 표준편차를 **불확실성**으로 함께 저장·표시.
- 한계(명시): 같은 폰 기울기에서도 목 각도는 달라질 수 있다(문제 1의 본질). `g()` 는 "이 사용자의 평균적인 관계"일 뿐이며, 모드 A 결과는 여전히 **추정 노출**로 표기한다.

### 4.6 `D_n` 갱신 (매 사이클)
```
BENT_*      : D_n += w(g(CATA))          # w: Hansraj 하중비. 15°→12/5, 30°→18/5, 45°→22/5, 60°→27/5 를 선형 보간한 뒤 정규화
UPRIGHT     : D_n  = max(0, D_n − r_upright)
NOT_IN_USE  : D_n  = max(0, D_n − r_rest)
STATIC_DEVICE, SUPINE_LIKE, SIDE_LYING_LIKE : 변화 없음 (로그만)
(현재 구현, g() 없음) BENT : D_n += 1
스트레칭 완료 : D_n = D_n × (1 − k_stretch)
```
초기값 `r_upright = 0.25`, `r_rest = 0.5`, `k_stretch = 0.5` (파일럿 후 조정). `D_n` 은 SharedPreferences 에 저장해 측정을 껐다 켜도 이어진다. 스트레칭 완료 항목은 아직 미구현.

### 4.7 파라미터 (`NeckConfig` 한 곳에서 관리)

| 이름 | 초기값 | 근거 | 확정 |
| --- | --- | --- | --- |
| 윈도우 / 사이클 | 5 s / 60 s | SmartPose | 고정 |
| `N_valid_min` | 6/12 | 30 s 이상 사용 | E2 |
| 기본 숙임 임계 (g 없을 때) | CATA 56° | SmartPose | E1 |
| 바로 누움 기울기 | θ < −15° | 0° 부근은 눈높이 바른 자세와 겹침 (2026-11-19) | 로그로 조정 |
| 옆으로 누움 | \|θ\| ≤ 60°, \|콘텐츠 roll\| 60–120° | 폰이 평평하면 roll 불안정 (2026-11-19) | 로그로 조정 |
| 누운 사이클 비율 | 유효 윈도우의 ≥ 1/2 | 다수결 (2026-11-19) | 로그로 조정 |
| `T_static` (5초 선형가속도 최대) | 0.15 m/s² | Galaxy S9 로그: 책상 ≤ 0.14, 손에 들고 정지 ≥ 0.12 (대부분 ≥ 0.15). 0.3 에서는 손에 든 정지 구간이 STATIC_DEVICE 로 판정됨 (2026-11-16) | 다른 기기 확인 필요 |
| 단계 경계 | 15° / 30° / 45° (θ_neck) | §2.1 | 파일럿 |
| 모드 B 프레임 | 5–10 fps | 연산량 | E3 |
| 키포인트 신뢰도 | 코·눈 0.7, 어깨 `inFrameLikelihood` 0.5 | 논문(OpenPose 기준) → ML Kit 로 재조정 | E0 |
| 모델 | ExtraTrees 100 trees, depth 20 | 논문 | 크기·지연 보고 축소 |

### 4.8 권한·매니페스트
| 항목 | 용도 |
| --- | --- |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH` | 모드 A 서비스 (type `health`) |
| `ACTIVITY_RECOGNITION` (기존) | health 타입 런타임 요건 + 정지/걷기 상태 |
| `POST_NOTIFICATIONS` (API 33+) | 서비스 상시 알림 |
| `CAMERA` | 모드 B (앱 화면에서만) |
| 의존성 추가 | CameraX, ML Kit Face Detection, ML Kit Pose Detection, ONNX Runtime Android |

### 4.9 로그
- `windows_yyyy-MM-dd.csv` (모드 A, 5초 1행, 현재 구현): `start_ms, end_ms, screen_in_use, excluded, tilt_n, tilt_mean_deg, tilt_std_deg, linacc_n, linacc_mean, linacc_max, static_device, exclude_reason, content_roll_deg, display_rotation_deg, posture`
- `cycles_yyyy-MM-dd.csv` (모드 A, 1분 1행, 현재 구현): `start_ms, end_ms, windows, valid_windows, cata_deg, tilt_std_deg, linacc_mean, linacc_max, static_windows, state, d_n, g_cata_deg, g_sigma_deg, neck_deg, neck_src, supine_windows, side_lying_windows` (`activity` 는 추후)
- `frames_<session>.csv` (모드 B, 프레임 1행): `ts_ms, theta_phone, phone_roll, face_pitch, face_yaw, face_roll, nose_xy, eyeL_xy, eyeR_xy, shL_xy, shR_xy, sh_likelihood, ratio, theta_neck_pred, drop_reason`
- 데이터 수집 모드에서는 `frames_*.csv` 에 원본 특징만 기록(모델 없이)하고, 정답과 합쳐 학습에 쓴다.

---

## 5. 정답 데이터 수집과 모델 학습

### 5.1 장비
- **피험자 폰**: 우리 앱(데이터 수집 모드) — 특징 + 센서 기록.
- **측면 기록 폰**: 삼각대에 고정해 수평을 맞추고 약 2 m 거리에서 측면 영상(30 fps).
- **마커**: ArUco 마커 2장(약 3 cm)을 견봉과 이주(귀 앞 연골 근처, 피부 테이프)에 부착.
- 분석: Python + OpenCV(`cv2.aruco`) → 프레임별 두 마커 중심 → 선의 수직 대비 각도 = θ_neck 정답.

### 5.2 프로토콜 (Watanabe 축소 + 앉기 추가)
- 참가자: 팀원 2명 + 지인 3–4명.
- 기준 자세 30초 → 안내 세션 4개(서기 2, **앉기 2** — 가정 A1 범위 전체를 덮기 위해) × 약 5분.
- 각 세션에서 목표 θ_neck 0°→60°(10° 단위)를 화면 안내로 천천히 오가고, 폰 거리·높이·얼굴 각도를 바꿔 다양성 확보. 폰이 눈높이보다 높은 자세는 제외.
- 1인 약 25분, 총 약 2–2.5시간 녹화.

### 5.3 동기화
- 세션 시작 시 피험자 폰 화면을 **흰색으로 3회 깜빡임** + 비프음 → 측면 영상에서 플래시 프레임을 찾아 오프셋 계산.
- 이후 타임스탬프로 매칭, 차이 **40 ms 이내** 쌍만 사용(논문 기준).

### 5.4 학습·평가
- 특징: `ratio, θ_phone, face_pitch` (논문) + 비교용 확장 `face_roll, face_yaw, phone_roll`.
- 분할: **세션 단위 교차검증**(논문과 비교) + **참가자 단위 leave-one-subject-out**(새 사용자 일반화, 논문에 없음). 표준화는 학습 fold 로만 fit.
- 비교 기준선: ① SmartPose 식 `θ_neck ≈ a·θ_phone + b`, ② `θ_phone + face_pitch` 선형, ③ Extra Trees 3특징, ④ Extra Trees 확장 특징.
- 지표: MAE, RMSE, R², ±10° 정확도, 10° 구간 혼동행렬.
- 배포: 가장 나은 모델을 ONNX 로 변환해 `assets/` 에 포함.

---

## 6. 검증 계획

| ID | 목적 | 방법 | Go 기준 / 산출물 |
| --- | --- | --- | --- |
| **E0** 키포인트 검출률 (Go/No-go) | 모드 B 성립 여부 | 팀원 2명, 서기·앉기 × 폰 기울기 0/30/60/80° × 거리 25/35/45 cm, 각 20초 | 숙임 자세(θ_phone 30–60°)에서 **어깨 양쪽 검출 ≥ 60% 프레임**이면 Go. 미달 시 §3.3 대안 |
| **E1** 모드 A 기울기–목 각도 관계 | SmartPose 가설 재검증, 기본 임계 결정 | §5 데이터의 θ_phone 과 정답 θ_neck | r, 회귀식, 숙임 판정 F1 |
| **E2** 사용 상태 게이트 | 거치대·책상·주머니·화면 꺼짐 배제, `T_static` 결정 | 상황별 5분 | 상태 분류 정확도 |
| **E3** 성능·배터리 | 모드 A 상시 비용, 모드 B 처리 속도 | 화면 켜고 1시간(서비스 on/off), 모드 B 10분 | 배터리 % 차이, 프레임당 ms |
| **E4** 모드 B 정확도 | Watanabe 재현 수준 확인 | §5.4 | MAE ≤ 10°(목표), 논문 수치와 비교 |
| **E5** 보정 `g()` 효과 | 모드 A 정확도 개선 여부 | 같은 참가자의 모드 B 쌍으로 `g()` 학습 → 별도 세션의 모드 A 에 적용 | g 적용 전후 MAE / 판정 F1 |
| **E6** 실사용 | 하루 노출 패턴, 누락 | 팀원 2명 × 2–3일 | 단계별 시간, `D_n` 곡선, A1 위반 비율 |

---

## 7. 개발 단계와 일정 (로드맵 기준, 2026)

| 기간 | 단계 | 내용 | 산출물 |
| --- | --- | --- | --- |
| 11/16–11/19 | **P0** 기반 | 모드 A 서비스·기울기·게이트·CSV 로그, 모드 B **데이터 수집 화면**(CameraX + ML Kit 특징 실시간 표시·기록) | 로그 파일, 수집 앱 |
| 11/19–11/22 | **P1** 판정 + E0 | `PostureWindowAggregator`, `NeckDebtCalculator`, 단위 테스트, **E0 실행** | 중간 발표: 모드 A 동작 + 모드 B 특징 시연 + E0 결과 |
| 11/23–11/29 | **P2** 데이터 | ArUco 정답 파이프라인(Python), §5 수집, E1·E2 | 학습 데이터셋 |
| 11/30–12/06 | **P3** 모델 | 학습·평가(E4), ONNX 탑재, 캘리브레이션 화면, `g()` (E5) | 온디바이스 θ_neck |
| 12/07–12/15 | **P4** 통합·파일럿 | `D_n` → `D` 연동, 알림 단계 연결, E3·E6 | 파일럿 빌드 |
| 12/16–12/20 | 분석·보고 | 결과 정리, 문제 2 후속 과제 정리 | 최종 보고 |

- E0 결과가 No-go 면 P2 부터 대안 특징 세트로 진행하고 일정은 유지.
- 브랜치: `feature/sedentary-detection` 을 먼저 main 에 병합한 뒤 `feature/neck-posture` 를 분기(같은 `MainActivity`·매니페스트 수정 충돌 방지). 데이터 분석 스크립트는 `analysis/neck/`(Python)에 둔다.

---

## 8. 리스크와 대응

| 리스크 | 영향 | 대응 |
| --- | --- | --- |
| 전면 카메라에 어깨가 안 잡힘 | 모드 B 핵심 특징 소실 | E0 를 가장 먼저 실행, 대안 특징 세트 준비 |
| ML Kit 얼굴 pitch 가 6DRepNet 보다 부정확 | 정확도 하락 | MediaPipe Face Landmarker 와 E4 에서 비교 |
| 정답 마커 위치 오차(어깨 말림 등) | 라벨 잡음 | 논문처럼 어깨 말지 않도록 안내, 기준 자세 영상으로 마커 위치 확인 |
| 참가자 수가 적음(5–6명) | 일반화 불확실 | 세션 단위 + 참가자 단위 평가를 함께 보고 |
| `g()` 로도 문제 1이 완전히 해결되지 않음 | 모드 A 오차 | 불확실성(잔차) 표기, "추정 노출" 용어 유지 |
| 가정 A1 위반(기대기·누움) | 잘못된 각도 | 감지 가능한 경우 제외, 나머지는 E6 에서 빈도 확인 후 후속 과제로 |
| 모드 B 연산·배터리 | 발열, 프레임 저하 | 5 fps, ML Kit Pose base 모델, 측정 세션 시간 제한 |
| 제조사 배터리 최적화가 서비스 종료 | 모드 A 누락 | 로그 공백 감지, 최적화 예외 안내 |

---

## 9. 후속 과제: 문제 2 (몸통 각도)
- 현황: 스마트폰만으로 몸통 기울기를 연속 추정해 목–몸통 각도 차를 구한 검증된 연구는 찾지 못함. Pose-on-the-Go(CHI 2021)는 몸통 yaw 만 추정(오차 15.9–26.1°)하고 pitch 는 수직으로 가정, Arda 2017 은 누움 여부만 이진 판별.
- 후보 접근: ① 자세 맥락 분류(서기·앉기·기대기·누움) 후 맥락별 보정, ② 모드 B 에서 어깨 랜드마크 + 폰 기울기로 상체 기울기 추정, ③ 얼굴 방향(중력 기준)으로 누움 판별.
- 이번 단계에서 할 일: A1 위반 감지 로그(`SUPINE_LIKE`, `SIDE_LYING_LIKE`)와 E6 의 위반 빈도를 근거 자료로 남긴다.

---

## 10. 후속 과제: 목 신전(위로 젖힘) 측정
- 현황: 얼굴 pitch 입력은 양방향(ML Kit `headEulerAngleX`, 위 +, 아래 −)으로 이미 기록된다. 그러나 Watanabe 공개 모델의 출력 θ_neck 은 사실상 0° 이상만 나온다. 트리 leaf 값 65,224개 중 음수 180개(최소 −9.5°, 하위 1% 2.5°) — 학습 데이터(5명, 서서, 숙임 유도, 폰이 눈높이보다 높은 경우 제외)에 신전 자세가 거의 없기 때문. 트리 모델은 학습 범위 밖 값을 낼 수 없어 코드 수정으로는 해결되지 않는다.
- 결정(2026-11-16): 현재는 범위 밖(§2.2)으로 두고, 목 각도는 0° 이상(굴곡)만 다룬다.
- 후속 접근: 신전 자세(폰을 눈높이보다 높게 드는 경우 포함)를 넣은 정답 데이터를 직접 수집(§5)해 모델을 다시 학습한다.

---

## 참고문헌
1. Lee, H., Choi, Y.S., Lee, S., Shim, E. *Smart Pose: Mobile Posture-aware System for Lowering Physical Health Risk of Smartphone Users.* CHI EA 2013, 2257–2266. https://doi.org/10.1145/2468356.2468747
2. Watanabe, K., Nakamura, S. *Can We Prevent "Text Neck" Using Only a Smartphone? Real-Time Neck Angle Estimation and a Serious Game as a Case Study.* OzCHI 2025. https://doi.org/10.1145/3764687.3764720 · PDF https://dl.nkmr-lab.org/papers/605/paper.pdf · 코드 https://github.com/kntWT/posture-correction-backend/tree/ozchi-2025
3. Ahuja, K., Mayer, S., Goel, M., Harrison, C. *Pose-on-the-Go: Approximating User Pose with Smartphone Sensor Fusion and Inverse Kinematics.* CHI 2021. https://doi.org/10.1145/3411764.3445582
4. Arda, F., Nugroho, L.E., Widyawan. *Activity monitoring system in supine position for preventing unhealthy smartphone reading posture.* InAES 2017. https://doi.org/10.1109/INAES.2017.8068541 (초록만 확인)
5. Lawanont, W. et al. *Smartphone posture monitoring system to prevent unhealthy neck postures.* JCSSE 2015 / *Neck posture monitoring system based on image detection and smartphone sensors…* IEEJ 2018. https://doi.org/10.1002/tee.22778 (초록만 확인)
6. Lee, S., Kang, H., Shin, G. *Head flexion angle while using a smartphone.* Ergonomics 58(2), 2015. https://doi.org/10.1080/00140139.2014.967311
7. Khamis, M. et al. *Understanding Face and Eye Visibility in Front-Facing Cameras of Smartphones used in the Wild.* CHI 2018. https://doi.org/10.1145/3173574.3173854
8. Hansraj, K.K. *Assessment of stresses in the cervical spine caused by posture and position of the head.* Surg Technol Int 25, 2014.
9. Android Developers. *Foreground service types.* https://developer.android.com/develop/background-work/services/fg-service-types
