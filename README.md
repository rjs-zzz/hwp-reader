# HWP 리더

안드로이드 경량 문서 뷰어. HWP·HWPX·PDF·PPTX·MD 를 오프라인으로 연다. 편집 기능은 없다.
요구사항과 설계는 [docs/PRD.md](docs/PRD.md) 참고.

- **구조**: Kotlin(Compose) 셸 + WebView 안의 [kordoc](https://github.com/chrisryugj/kordoc) 브라우저 번들. PDF 는 안드로이드 `PdfRenderer`.
- **보안**: 어떤 권한도 선언하지 않는다(INTERNET 없음). 문서는 인텐트·SAF 로 받은 URI 로만 읽고, 앱 전용 캐시에 사본을 두었다가 닫을 때 지운다.

## 폴더

| 경로 | 내용 |
| --- | --- |
| `app/` | 안드로이드 앱 (Kotlin, Jetpack Compose) |
| `web/` | kordoc 뷰어 번들 — `src/viewer.js`(화면·읽기 모드·검색), `src/engine.js`(kordoc, Web Worker), `shims/`(Node 내장 모듈 대체), `build.mjs`(esbuild) |
| `web/dev/` | 데스크톱 브라우저 시험 도구 — 네이티브와 같은 메시지 포트 방식으로 뷰어를 구동한다 |
| `web/test/` | 엔진 번들·zlib 대체 모듈 시험 (`npm test`) |
| `docs/` | PRD |

## 개발 환경

- JDK 17, Android SDK (platform 37 컴파일 · targetSdk 36 · minSdk 26), Node.js 20 이상
- `local.properties` 에 `sdk.dir` 지정 (저장소에 넣지 않음)

```bash
cd web && npm ci
```

## 빌드

`preBuild` 가 `web/` 의 `npm run build` 를 먼저 돌려 `app/src/main/assets/viewer/` 를 만든다.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:assembleRelease
```

릴리스는 R8 난독화·축소를 켠다. 저장소 루트에 `keystore.properties`(storeFile·storePassword·keyAlias·keyPassword)가 있으면 그 키로, 없으면 디버그 키로 서명한다. 키 파일과 설정은 저장소에 넣지 않는다.

## 시험

```bash
./gradlew :app:testDebugUnitTest
```

```bash
cd web && npm run build && npm test
```

`SAMPLES_DIR` 환경변수로 표본 폴더를 주면 그 안의 hwp·hwpx 도 모두 렌더해 본다.

## 뷰어만 데스크톱에서 시험하기

표본 문서 폴더를 지정해 시험 서버를 띄우고 `http://localhost:5173/` 을 연다. '전체 실행'으로 표본 전체의 원본 보기 성공 여부와 시간을 잰다.

```bash
cd web && node dev/serve.mjs samples
```

표본 문서(`web/samples/`)는 저장소에 넣지 않는다.

## 네이티브 ↔ 뷰어 ↔ 엔진

- 네이티브와 뷰어는 `WebMessagePort` 하나로만 주고받는다. 형식은 [web/src/viewer.js](web/src/viewer.js) 머리말 참고.
- 문서 바이트는 `{type:"open", size}` 뒤에 3MB 조각으로 나눠 보내고 `{type:"end"}` 로 끝낸다. 조각은 ArrayBuffer(1안), 지원하지 않는 WebView 에서는 Base64(2안). 다크 모드·시스템 글꼴 배율은 따로 `theme`·`fontScale` 메시지로 보낸다.
- 뷰어와 kordoc 엔진([web/src/engine.js](web/src/engine.js))은 Web Worker 로 주고받는다. 엔진은 문서를 한 번만 배치하고, 쪽 SVG 의 그림은 문서마다 한 벌만 Blob 으로 넘긴다.
- WebView 렌더러가 죽으면 앱은 죽지 않는다. 한 번 새 WebView 로 다시 열고, 또 죽으면 오류로 닫는다. Chrome 80 미만 WebView 는 업데이트 안내를 띄운다.

## 고지

본 제품은 한글과컴퓨터의 한/글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.

오픈소스 라이선스 원문은 빌드 때 `assets/viewer/licenses.txt` 로 모이고, 앱의 설정 → 오픈소스 라이선스에서 볼 수 있다.
