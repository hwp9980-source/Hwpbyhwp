# hwpbyhwp

안드로이드용 HWP/HWPX 문서 뷰어.

## 구조

| 모듈 | 내용 | 빌드 |
|---|---|---|
| `core` | 문서 모델, HWP/HWPX 변환기, 레이아웃 계산 (순수 Kotlin/JVM) | 아무 JVM에서 `./gradlew :core:test` |
| `app` | Android 앱 (Jetpack Compose) | Android SDK 필요: `./gradlew -PwithApp :app:assembleDebug` |

- 파싱: [hwplib](https://github.com/neolord0/hwplib)(HWP 5.x), [hwpxlib](https://github.com/neolord0/hwpxlib)(HWPX). 둘 다 Apache-2.0.
- 두 형식을 `core/model/Document.kt`의 공통 모델로 변환한 뒤 렌더링한다. 길이 단위는 HWPUNIT(1/7200 inch).
- `app` 모듈은 `-PwithApp`을 줄 때만 포함된다. Android SDK 없이도 core를 빌드·테스트할 수 있게 하기 위함.
- CI(GitHub Actions)는 core 테스트 후 디버그 APK를 빌드해 아티팩트로 올린다.

## 단위 메모

- HWP 바이너리의 문단 여백·들여쓰기·문단 간격은 실제 값의 2배로 저장된다. 변환기에서 2로 나눈다.
- HWPX는 같은 값을 `<hp:switch>` 안에 두 벌 저장한다. `HwpUnitChar` case는 실제 HWPUNIT, default는 HWP처럼 2배 값이다.

## 로드맵

1. [x] 프로젝트 구조, 파일 열기(SAF·외부 앱 공유), 파서 연결, CI
2. [x] 공통 문서 모델 (문단·글자 모양·문단 모양·표·그림·테두리/배경)
3. [x] 페이지 레이아웃 엔진: 한글이 저장한 줄 배치(LineSeg)를 따라 줄·쪽 나눔 재현, 없으면 직접 계산. 표 쪽 넘김·제목 행 반복, 그림, 떠 있는 개체
4. [x] 페이지 렌더러 (Canvas, 두 손가락 확대/축소). 읽기 모드 전환 가능
5. [ ] 남은 요소
   - [x] 다단, 머리말/꼬리말, 쪽 번호
   - [ ] 각주/미주, 도형(선·사각형·타원·다각형·글상자), 수식, 문단 테두리/배경, 글머리표·문단 번호
   - [ ] 글꼴: 함초롬체 등 번들 또는 대체 규칙 (현재 시스템 Serif/Sans로 대체, 폭이 넘치면 줄 안에서 압축)
6. [ ] 실제 문서 샘플로 한컴 뷰어와 비교 검증

## 개발용 테스트 도구

- `HWP_SAMPLES=<폴더> ./gradlew :core:test`: 폴더 안의 모든 문서를 읽고, 저장된 줄 배치로 여백 단위 변환을 교차 검증한다.
- 여기에 `HWP_PREVIEW_OUT=<폴더>`를 더하면 각 문서 앞 3쪽을 Java2D로 그려 PNG로 저장한다(`HWP_PREVIEW_FILTER`로 파일명 필터).
