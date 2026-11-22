# TTS Design System

Apple HIG의 토큰·컴포넌트 구분을 따르고, 보이는 느낌은 iOS 기본 앱(설정)에 맞춘 시스템입니다.

- `styles.css` — 토큰(`:root`, 다크는 `:root[data-theme="dark"]`) + 컴포넌트 클래스. 유일한 원본.
- `index.html` — 리빙 스타일 가이드 (Dev Mode 검사기, Copy as prompt)
- `concept.html` — 홈 + 상세 컨셉 화면
- `icons.html` — 아이콘 스프라이트 (Lucide 계열 오픈 기하, SF Symbols 아님)
- `theme.js` — 라이트/다크 전환 (세 페이지 공유)

## 원칙
- 조작은 강조색(tint) 하나만. 초록·빨강·주황은 상태/의미 전용.
- 모든 채움-글자 쌍은 WCAG 대비를 계산해 정함 (본문 ≥ 4.5:1, UI ≥ 3:1). 값 옆 주석 참고.
- 면은 평면 + 헤어라인. 떠 있는 레이어(알림·메뉴·시트)만 `--elev-2`.
- inset-grouped 목록, 화면 여백 16, 관련 8 < 섹션 24 < 큰 구획 32, 터치 44 이상.

## Android 앱과의 대응
- 색: `TTS/app/src/main/res/values/colors.xml`, `values-night/colors.xml` (같은 토큰 이름을 snake_case로)
- 테마/공통 스타일: `res/values/themes.xml` (버튼, 상단 바, 상태바)
- 메인 화면: `res/layout/activity_main.xml` — `concept.html`의 First view가 기준

## 확장 방법
- **토큰 추가**: `styles.css`의 알맞은 그룹에 변수 추가(라이트·다크 둘 다) → Android `colors.xml` 두 곳에도 추가. 컴포넌트에 값을 직접 쓰지 않기.
- **컴포넌트 추가**: `styles.css`에 클래스 블록 → `index.html`에 `<section id="키">` + `SPECS.키` 항목 (Dev Mode와 Copy as prompt가 자동으로 붙음) → 사이드바 링크.
- **테마 바꾸기**: `:root` 토큰 값만 바꾸면 스타일 가이드와 컨셉 화면이 함께 바뀜.
