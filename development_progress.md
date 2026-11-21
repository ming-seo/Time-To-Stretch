# 개발 진행 기록 (Development Progress)

> 마지막 갱신: 2026-11-19
> 대상 브랜치: `feature/forward-head-detection` (거북목 자세 측정)
> 관련 문서: [설계 문서 — 거북목 자세 측정](docs/design/neck-posture-detection.md) · [제안서](docs/proposal/Proposal.pdf)

---

## 1. 한눈에 보기

| 구분 | 상태 |
| --- | --- |
| 설계 문서 (v2) | ✅ 작성 및 구현에 맞춰 갱신 |
| **P0** 기반 (모드 A 서비스·로그, 모드 B 수집 화면) | ✅ 완료 |
| **P1** 판정 (사이클 상태 판정, `D_n` 갱신) | ✅ 완료 (단위 테스트는 미작성) |
| 모드 B 목 각도 추론 (Watanabe 공개 모델) | ✅ 온디바이스 추론 동작 (모델 파일은 git 제외) |
| **E0** 어깨 검출률 실험 (Go/No-go) | ⏳ 미실시 (비공식 테스트 로그만 있음, §5) |
| P2 이후 (정답 데이터 수집, 자체 모델 학습, 보정식 `g()`, `D` 통합, 알림) | ⏳ 미착수 |

- 테스트 기기: Galaxy S9 (SM-G960N, Android 10 / API 29)
- 이 브랜치는 `main`(0adb968)에서 분기했고, 팀원의 `feature/sedentary-detection`(좌식 시간 감지)은 **포함하지 않는다**. 두 기능은 아직 따로 있다.

---

## 2. 프로젝트 이력 (브랜치별)

| 날짜 | 브랜치 | 내용 |
| --- | --- | --- |
| 2026-10-19 | `main` | README, 제안서 추가 |
| 2026-10-22 | `main` | Android 프로젝트(TTS) 생성, 기존 원격 이력 병합 |
| 2026-11-16 | `feature/sedentary-detection` (팀원) | Activity Recognition STILL/WALKING 전환 기반 좌식 시간 감지 (`SedentaryTracker`, `ActivityTransitionReceiver`, `MainActivity`) |
| 2026-11-16 | `feature/forward-head-detection` | 거북목 측정 설계 → 모드 A / 모드 B 구현 (아래 §3) |

---

## 3. 거북목 측정 개발 과정 (`feature/forward-head-detection`)

모든 작업은 2026-11-16에 진행했고, 단계마다 커밋을 남겼다.

### 3.1 설계 — `1ce9e05`
- 방향 결정
  - **SmartPose(2013)**를 베이스로 한 상시 측정(모드 A)
  - **Watanabe & Nakamura(2025)** 방식의 카메라 기반 목 각도 추정(모드 B)으로 "폰 각도 ≠ 목 각도" 문제(문제 1)를 보완
  - 몸통 각도(문제 2)는 "몸통은 대략 수직" 가정(A1)으로 두고 후속 과제로 미룸
- 목 각도 정의: 귀(이주)–어깨(견봉) 선과 수직선 사이 각도 (Watanabe와 같은 정의)
- 구현 가능성 판단
  - 모드 A: 가능
  - 모드 B: 조건부 가능 — 전면 카메라에 어깨가 잡히는지가 최대 위험이라 E0를 Go/No-go로 둠
- 일정을 P0–P4 단계로 나눔 (설계 문서 §7)

### 3.2 P0 — 모드 A 기반 (백그라운드 센싱)

| 커밋 | 내용 |
| --- | --- |
| `6a4aed3` | `TiltCalculator`: 중력 벡터로 폰 기울기 계산 (`atan2(g_z, √(g_x²+g_y²))`, 수직 0° / 평평 90°, 세로·가로 무관). `PostureWindowAggregator`: 5초 윈도우 → 60초 사이클 집계 (Android 의존성 없는 순수 로직) |
| `839c9ef` | `NeckSensingService`: 포그라운드 서비스(type `health`). 화면 켜짐 + 잠금 해제일 때만 중력·선형가속도·근접 센서를 등록. 통화 중이거나 근접센서가 가려지면 해당 윈도우를 제외. `NeckCsvLogger`로 윈도우/사이클 CSV를 날짜별 기록 |
| `605d82b` | `NeckActivity`: 런처 디버그 화면 (측정 시작/중지, 현재 기울기, 화면 사용 여부, 마지막 사이클, 로그 경로) |

- 결정 사항
  - deprecated된 `TYPE_ORIENTATION` 대신 `TYPE_GRAVITY`를 쓰고, 없으면 가속도계에 저역통과 필터를 적용
  - SmartPose의 "얼굴 검출로 사용 중 판정"은 백그라운드에서 카메라를 쓸 수 없어서 **화면 켜짐 + 잠금 해제** 조건으로 대체
  - 로그는 앱 전용 외부 저장소(`Android/data/com.example.tts/files/neck_logs/`)에 저장해 `adb pull`로 가져옴

### 3.3 P0 — 모드 B 데이터 수집 화면 (전면 카메라)

| 커밋 | 내용 |
| --- | --- |
| `fea2428` | `NeckCollectActivity`: 세로 고정, CameraX 1.6.2 전면 미리보기, CAMERA 권한 |
| `6246a10` | ML Kit Face Detection 16.1.7: 얼굴 pitch/yaw/roll, 코·눈 랜드마크. 640×480(4:3) 분석 프레임. `KeypointOverlayView`로 미리보기 위에 점 표시 (전면 카메라라 좌우 반전) |
| `7fce770` | ML Kit Pose Detection 17.0.0 (base, stream 모드)를 같은 프레임에 실행. 양 어깨 좌표·신뢰도 표시. **목 밑점 = 두 어깨 중점** (ML Kit에는 neck 키포인트가 없음). 코–목 / 눈 사이 비율 표시 |
| `91ddfda` | `FrameCsvRecorder`: 기록 버튼을 누르면 프레임마다 `frames_<시각>.csv`에 1행 기록 (폰 기울기·roll, 얼굴 각도, 코·눈·어깨 좌표, 어깨 신뢰도). 화면을 벗어나면 기록 중지 |
| `c1bbc1f` | 얼굴 검출을 ACCURATE에서 **FAST 모드로 변경** — ACCURATE 얼굴 검출 + 포즈 조합이 Galaxy S9에서 약 2.5 fps로 너무 느렸음. 변경 후 약 6–7 fps (§5) |

### 3.4 모드 B — 기준 자세 측정과 목 각도 추론

| 커밋 | 내용 |
| --- | --- |
| `f6bfb44` | `WatanabeNeckModel`: 저자 공개 Extra Trees(100 트리) ONNX 모델을 `tools/convert_watanabe_model.py`로 자체 바이너리(`WNM1`)로 변환하고 Kotlin에서 직접 평가. StandardScaler 값은 저자의 scaler에서 가져옴 |
| `4c30cbf` | "기준 자세 측정" 버튼: 3초 동안 조건(폰 기울기 ±15°, 얼굴 pitch ±10°, 양 어깨 신뢰도 ≥ 0.5)을 만족한 프레임의 비율 중앙값을 `Norm_ref`로 저장. 5프레임 미만이면 실패 |
| `86b6917` | 모델·`Norm_ref`·기울기·얼굴·양 어깨가 모두 있으면 프레임마다 목 각도를 추론해 화면에 표시하고 CSV에 `norm_ref`, `neck_pred_deg` 열로 기록 |

- 결정 사항
  - **ONNX Runtime 대신 직접 구현한 트리 평가기 사용**: 의존성과 APK 크기를 줄이기 위해. 설계 문서 §3.2에 "트리 배열을 직접 평가" 대안으로 적어 두었던 방법이다.
  - **모델 파일은 git에 넣지 않음**: 저자 코드에 라이선스가 명시되어 있지 않아서. `TTS/app/src/main/assets/`는 gitignore 대상이다. 파일이 없으면 화면에 "모델 파일 없음"으로 표시되고 특징 수집만 된다.
  - **캘리브레이션 조건 완화**: 논문은 폰 수직 ±3°, 얼굴 각도 ±3°를 요구하지만, 손으로 들고 맞추기 쉽도록 폰 ±15°, pitch ±10°로 완화했다. 얼굴 roll/yaw 조건은 쓰지 않는다.

### 3.5 설계 문서 갱신
- `4968116`: **목 신전(위로 젖힘)을 범위 밖으로 둠** (설계 문서 §10). Watanabe 모델은 학습 데이터에 신전 자세가 거의 없어서 사실상 0° 이상만 출력한다. 트리 leaf 값 65,224개 중 음수는 180개뿐이다. 신전을 다루려면 직접 데이터를 수집해 다시 학습해야 한다.
- `ca753c3`: 캘리브레이션 절차(§4.4)를 구현과 맞춤.

### 3.6 P1 — 모드 A 판정과 `D_n`

| 커밋 | 내용 |
| --- | --- |
| `da852a9` | 5초 동안 선형가속도 최대값이 임계 미만인 윈도우를 정지(책상·거치대, `static_device`)로 보고 CATA 계산에서 제외. 윈도우 CSV에 `static_device`, `exclude_reason`(proximity/call) 열을, 사이클 CSV에 `static_windows` 열을 추가 |
| `3846dcf` | `NeckDebtCalculator`: 60초 사이클마다 상태를 판정하고 `D_n`을 갱신. SharedPreferences에 저장해 재시작해도 유지. 사이클 CSV에 `state`, `d_n` 열 추가, 디버그 화면에 `D_n` 표시 |
| `94fa59c` | 기기에서 받은 `neck_logs/`를 gitignore에 추가 |
| `3857db1` | **정지 임계값을 0.3 → 0.15 m/s²로 낮춤** (§4) |
| `4bf9b50` | 설계 문서 모드 A 부분(§4.2, §4.6, §4.7, §4.9)을 P1 구현과 맞춤 |

사이클 판정 규칙 (보정식 `g()`가 없는 현재 방식):

| 조건 | 상태 | `D_n` |
| --- | --- | --- |
| 유효 윈도우 < 6, 정지 윈도우를 포함하면 6 이상 | `STATIC_DEVICE` | 변화 없음 |
| 유효 윈도우 < 6 | `NOT_IN_USE` | −0.5 (최소 0) |
| CATA < 0° | `SUPINE_LIKE` | 변화 없음 |
| CATA ≥ 56° (SmartPose 기본 임계) | `BENT` | +1 |
| 그 외 | `UPRIGHT` | −0.25 (최소 0) |

### 3.7 누운 자세 세분화

| 커밋 | 내용 |
| --- | --- |
| `c279a8d` | 윈도우마다 **콘텐츠 roll**(화면 내용의 위쪽과 중력 위쪽 사이 각)과 화면 회전을 기록. 윈도우 CSV에 `content_roll_deg`, `display_rotation_deg` 열 추가 |
| `9ff4b53` | 유효 윈도우를 `TRUNK_UPRIGHT` / `SUPINE` / `SIDE_LYING`으로 라벨링. 사이클의 절반 이상이 누운 윈도우면 `SUPINE_LIKE` / `SIDE_LYING_LIKE`(`D_n` 변화 없음), 아니면 `TRUNK_UPRIGHT` 윈도우로만 CATA 계산. 기존 `CATA < 0° → SUPINE_LIKE` 규칙은 제거. 윈도우 CSV에 `posture`, 사이클 CSV에 `supine_windows`, `side_lying_windows` 열 추가 |
| `4573981` | 카메라 측정을 `TRUNK_UPRIGHT` 윈도우에서만 시작 (`g()` 학습 쌍에서 누운 자세 제외) |

- 임계값(θ < −15°, \|θ\| ≤ 60° && \|콘텐츠 roll\| 60–120°, 절반)은 **초기값**이다. S9에서 자세별 로그를 받아 조정한다.
- 엎드림(`PRONE_LIKE`)은 보류: 앉아서 많이 숙인 자세와 폰 기울기가 겹친다. 로그를 본 뒤 추가 여부를 정한다.
- 기대어 누움은 바른 자세와 구분할 수 없어서 `UPRIGHT`로 판정된다.

### 3.8 모드 C — 얼굴만으로 머리 숙임 각도 (2026-11-21~22, 설계 문서 §11)

모드 B가 양 어깨를 필요로 하는 한계 때문에, 얼굴만 쓰는 모드 C를 수집 화면에 추가했다. 모드 B와 같은 프레임에서 함께 계산해 비교한다.

| 커밋 | 내용 |
| --- | --- |
| `8b5981e` | 설계 문서 §11: 사용자별 얼굴 3D 모델 + PnP, 9자세 캘리브레이션, 비교 실험 |
| `fc29936` | `FaceFeatures`에 입 좌·우·아래, 볼 좌·우, 얼굴 영역 중심 추가 (모드 B 동작은 그대로) |
| `b4a6f73` | `ModeCGeometry`(좌표·각도 정의, LM 최소제곱), `FacePoseSolver`(PnP), `CameraIntrinsics`(전면 카메라 초점 거리) + 단위 테스트 |
| `26ba644` | `FaceModelCalibrator`: 9자세 뷰에서 랜드마크 3D 위치 복원 (회전은 센서 값 고정), `FaceModel` 저장 + 단위 테스트 |
| `0e86d5b` | 수집 화면 "모드 C 캘리브레이션": 9단계 안내·자동 수집 (`TYPE_GAME_ROTATION_VECTOR`), 복원, `calib_c_*.csv` 기록 |
| `e9f3e43` | 프레임마다 PnP → `pitch_C`, `yaw_C`, 거리, θ_C 표시, frames CSV 열 추가 |
| `56ec679` | `analysis/neck/compare_b_c.py`: B/C 비교(`compare`), 얼굴 각도 검증 스윕(`sweep`, A·B·D 안 비교) |

- 결정 사항 (2026-11-21 논의)
  - 출력은 **머리 숙임 각도 θ_C** 그대로 (목 각도로 변환하지 않음)
  - 캘리브레이션 9자세: 정면, 위·아래 22.5°/45°, 좌·우 22.5°/45°
  - 방법: 비율 보간(A) 대신 **사용자별 3D 모델 + PnP(B)** — 근거리 원근 왜곡과 거리 변화를 직접 반영. A안과 ML Kit pitch + 개인 보정(D안)은 분석 스크립트에서 오프라인 비교
  - 수집 화면에서만 동작 (서비스·`g()`·`D_n` 영향 없음), 브랜치는 분기하지 않음
- 구현 중 정한 세부: 복원 실패 판정을 전체 RMS가 아니라 **단계별 RMS 최대값 > 5 px**로 함 (합성 데이터에서 한 단계 라벨 15° 오차가 전체 RMS 2.1 px로 묻힘)
- 기기 확인 전: S9에서 캘리브레이션·추정 동작, 영상 비반전 가정(좌우 단계 재투영 오차로 확인), 초점 거리 출처(`calib_c_*_model.csv`의 `focal_px`)

---

## 4. 파라미터 조정 기록

| 날짜 | 파라미터 | 변경 | 근거 |
| --- | --- | --- | --- |
| 2026-11-16 | 얼굴 검출 모드 | ACCURATE → FAST | Galaxy S9에서 얼굴+포즈 약 2.5 fps → FAST로 약 6–7 fps |
| 2026-11-16 | 캘리브레이션 조건 | 논문 ±3° → 폰 ±15°, pitch ±10° | 손으로 들고 맞추기 어려움 |
| 2026-11-16 | `T_static` (정지 판정 선형가속도 최대값) | 0.3 → **0.15 m/s²** | 0.3에서는 손에 들고 가만히 있는 경우(최대값 0.12–0.30)도 `STATIC_DEVICE`로 판정됨. 이전 로그에서 책상 위 윈도우는 0.15 미만이었음. 다른 기기에서 다시 확인 필요 |

---

## 5. 기기 테스트 기록 (비공식)

Galaxy S9에서 동작을 확인하면서 받은 로그(`neck_logs/`, git 제외)의 요약이다. 정식 실험(E0–E6)이 아니라 개발 중 확인용이다.

> 참고: 테스트 기기의 시스템 시계가 2021년으로 되어 있어서 로그 파일명과 타임스탬프가 2021-11-05/06로 찍혀 있다. 실제 테스트 날짜는 2026-11-16이다.

**모드 B 수집 화면** (`frames_*.csv` 8개 세션)

| 세션 | 프레임 | fps | 얼굴 검출 | 양 어깨 ≥ 0.5 | 목 각도 추론 | 폰 기울기 범위 |
| --- | --- | --- | --- | --- | --- | --- |
| ACCURATE 모드 (3개) | 8–133 | 약 2.5 | 98–100% | 0–63% | — (모델 추가 전) | −30° ~ 53° |
| FAST 모드, 모델 추가 전 | 67 | 6.8 | 100% | 97% | — | 4° ~ 34° |
| FAST 모드, 모델 추가 후 (4개) | 146–304 | 6.3–7.2 | 93–100% | 85–100% | 83–100% | −1° ~ 51° |

- FAST 모드로 바꾼 뒤 처리 속도가 약 2.5배 빨라졌다.
- 폰 기울기가 0–51° 범위인 짧은 세션에서는 양 어깨가 85% 이상 잡혔다. 다만 E0 조건(서기·앉기 × 기울기 0/30/60/80° × 거리 25/35/45 cm)으로 체계적으로 잰 것은 아니므로, **E0는 아직 미실시**로 본다.

**모드 A** (`cycles_*.csv`)
- 첫 로그(2021-11-05 날짜, 판정 기능 추가 전): CATA는 계산됐지만 상태 열이 없었다. 폰을 책상에 둔 구간에서 CATA ≈ 89°, 선형가속도 최대값 ≈ 0.1로, 정지 판정이 필요하다는 것을 확인했다.
- 두 번째 로그(2021-11-06 날짜, 임계 0.3 적용 상태): `BENT`, `UPRIGHT`, `NOT_IN_USE`, `STATIC_DEVICE`가 모두 나왔고 `D_n`이 규칙대로 증감했다. 다만 손에 든 상태에서도 `STATIC_DEVICE`가 자주 나와서 임계값을 0.15로 낮췄다 (`3857db1`). **0.15에서의 재확인 로그는 아직 없다.**

---

## 6. 현재 코드 구조

```
TTS/app/src/main/java/com/example/tts/neck/
├─ NeckActivity.kt             런처 디버그 화면 (모드 A 시작/중지, 상태·D_n 표시)
├─ NeckSensingService.kt       모드 A 포그라운드 서비스 (센서 수신, 화면 게이트, 제외 사유)
├─ TiltCalculator.kt           중력 → 폰 기울기 / roll
├─ PostureWindowAggregator.kt  5초 윈도우 · 60초 사이클 집계, 정지 판정 (순수 로직)
├─ NeckDebtCalculator.kt       사이클 상태 판정, D_n 갱신 (순수 로직)
├─ NeckCsvLogger.kt            windows_*.csv, cycles_*.csv
├─ NeckLiveState.kt            서비스 → 화면 최신 값 공유
├─ NeckCollectActivity.kt      모드 B 수집 화면 (CameraX + ML Kit, 캘리브레이션, 추론, 기록)
├─ WatanabeNeckModel.kt        Extra Trees 추론기 (WNM1 바이너리)
├─ FrameCsvRecorder.kt         frames_*.csv
└─ KeypointOverlayView.kt      미리보기 위 키포인트 표시
tools/convert_watanabe_model.py  ONNX → WNM1 변환
```

권한: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `CAMERA`

**로그 가져오기**

```bash
adb pull /sdcard/Android/data/com.example.tts/files/neck_logs/ .
```

**모델 파일 넣기** (저자 공개 저장소 `kntWT/posture-correction-backend`, 브랜치 `ozchi-2025`의 `extra_trees.onnx`)

```bash
python tools/convert_watanabe_model.py extra_trees.onnx TTS/app/src/main/assets/neck/watanabe_extra_trees.bin
```

---

## 7. 남은 일

설계 문서 §7 일정 기준:

- [ ] **E0** 어깨 검출률 실험 (Go/No-go) — P1 단계 마지막 항목
- [ ] `T_static` 0.15 m/s² 재확인 로그 (손에 든 정지 / 책상 / 거치대)
- [ ] 순수 로직(`TiltCalculator`, `PostureWindowAggregator`, `NeckDebtCalculator`) 단위 테스트
- [ ] P2: ArUco 마커 정답 파이프라인(Python), 데이터 수집, E1·E2
- [ ] P3: 자체 모델 학습·평가(E4), 보정식 `g()`(E5), 숙임 단계 세분화(`BENT_MILD` / `BENT` / `BENT_SEVERE`)
- [ ] P4: `feature/sedentary-detection`과 통합, `D = w_n·D_n + w_s·D_s`, 알림, E3·E6
- [ ] 누운 자세 임계값 조정: S9에서 자세별 로그(앉기 숙임/바름/가로 영상, 바로 누움, 기대어 누움, 옆으로 누움 좌·우 × 세로/가로, 엎드림) 수집 후 확정, `PRONE_LIKE` 추가 여부 결정
- [ ] 미구현 설계 항목: 스트레칭 완료 시 `D_n` 감소, 모드 B 평활화(1초/3초 중앙값)
- [ ] 모드 C: S9에서 캘리브레이션·추정 확인 → B/C 비교 실험, 얼굴 각도 검증 스윕(25/35/45 cm) → 결과 기록 (설계 문서 §11.7)
