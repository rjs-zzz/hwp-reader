## 배포 안내 — HWP 리더

정식 서명 키로 서명한 APK·AAB 를 만들고, 점검한 뒤, 배포하는 절차. PRD 12장(배포 방식)·13장(M3)을 따른다.

---

### 1. 준비물

| 항목 | 버전 | 비고 |
| --- | --- | --- |
| JDK | 17 | `JAVA_HOME` |
| Android SDK | compileSdk 37 · build-tools 36.1.0 · platform-tools | `local.properties` 의 `sdk.dir` |
| Node.js | 22 이상 권장(24에서 시험) | 뷰어 번들 빌드·시험. `npm test` 의 파일 패턴은 Node 21 이상이 필요하다 |
| 서명 키 | 아래 2장 | 저장소 밖에 둔다 |

---

### 2. 서명 키

○ 현재 키 (2026-10-18 생성)

| 항목 | 값 |
| --- | --- |
| 파일 | `C:\Users\rjs07\.hwp-reader-release\hwp-reader-release.jks` (PKCS12) |
| 별칭 | `hwp-reader` |
| 알고리즘 | RSA 4096비트, SHA384withRSA |
| 인증서 | `CN=HWP Reader, O=rjs-zzz`, 유효 12,000일(2059년까지) |
| 인증서 SHA-256 | `DF:E9:AB:B3:A1:1D:16:83:B5:71:5B:92:44:73:D0:FB:9B:CA:D5:F9:F0:58:B9:D4:66:4F:8C:47:66:CA:F1:AD` |

- 비밀번호는 저장소 루트의 `keystore.properties` 에만 있다. 이 파일은 `.gitignore` 로 git 에서 빠져 있다.
- **키 파일과 `keystore.properties` 를 함께 안전한 곳(비밀번호 관리자, 암호화한 저장 장치)에 백업한다.** 키를 잃으면 이미 설치된 앱을 업데이트할 수 없다. 사용자는 앱을 지우고 새로 깔아야 한다.
- 키 파일·비밀번호를 메신저·메일·공유 드라이브에 올리지 않는다.

○ `keystore.properties` 형식 (저장소 루트, git 제외)

```properties
storeFile=C:/Users/<사용자>/.hwp-reader-release/hwp-reader-release.jks
storePassword=<비밀번호>
keyAlias=hwp-reader
keyPassword=<비밀번호>
```

- 경로는 `/` 로 쓴다(역슬래시는 이스케이프가 필요하다).
- 다른 PC 에서 빌드하려면 백업한 키 파일을 옮기고 이 파일을 새로 만든다.
- 파일이 없으면 릴리스 빌드(`assembleRelease`·`bundleRelease`)가 `checkReleaseSigning` 에서 멈춘다. 디버그 키로 서명된 파일이 정식 배포본으로 나가지 않게 하려는 것이다. 시험용으로만 `-PallowDebugSigning=true` 를 붙인다.

---

### 3. 배포 순서

1. **버전 올리기** — `app/build.gradle.kts` 의 `versionCode` 를 1 올리고 `versionName` 을 바꾼다(예: 1.0.1). 같은 `versionCode` 로는 업데이트 설치가 되지 않는다.
2. **시험**

```bash
cd web && npm ci && npm run build && npm test
```

```bash
./gradlew :app:testDebugUnitTest
```

3. **빌드** — APK(직접 설치용)와 AAB(Google Play 용)를 함께 만든다. `checkReleasePermissions` 가 권한이 섞여 들어왔는지 함께 확인한다.

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

산출물: `app/build/outputs/apk/release/app-release.apk`, `app/build/outputs/bundle/release/app-release.aab`

4. **서명 확인** — 인증서 SHA-256 이 2장 표와 같아야 한다.

```bash
"$ANDROID_HOME/build-tools/36.1.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

- Windows 의 build-tools 에는 `apksigner.bat` 만 있다(Git Bash 는 `.bat` 를 붙여 찾지 않는다). macOS·Linux 에서는 `apksigner`.
- PowerShell: `& "$env:ANDROID_HOME\build-tools\36.1.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk`
- `ANDROID_HOME` 이 없으면 `local.properties` 의 `sdk.dir` 경로를 쓴다.

5. **매니페스트 확인** — 버전이 맞고, `uses-permission` 은 `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`(androidx.core 가 넣는 앱 내부용 서명 권한) 하나뿐이고, `application-debuggable` 이 없어야 한다.

```bash
"$ANDROID_HOME/build-tools/36.1.0/aapt2" dump badging app/build/outputs/apk/release/app-release.apk
```

6. **R8 매핑 보관** — `app/build/outputs/mapping/release/mapping.txt` 를 버전별로 보관한다. 난독화된 오류 기록을 읽을 때 필요하다. 저장소에는 넣지 않는다.
7. **실기기 확인** — 4장 목록.
8. **기록** — 커밋에 태그(`v1.0.0` 등)를 달고, 바뀐 점을 적는다.

---

### 4. 실기기 확인 (배포 전)

- [ ] 처음 실행·홈 화면(최근 문서 기록 기본 '끔')
- [ ] 메신저·메일 첨부를 '다른 앱으로 열기' → HWP·HWPX·PDF 가 열린다
- [ ] 앱 안 '파일 열기'로 고른 문서가 열린다
- [ ] 원본 ↔ 읽기 전환, 문서 내 검색, 쪽 이동
- [ ] 암호 문서·손상 문서·구형 .ppt 에서 안내 문구
- [ ] 뒤로 가기·다른 문서 열기·화면 회전·시스템 글꼴 크기·다크 모드
- [ ] 설정 → 오픈소스 라이선스, 한컴 고지

`adb` 로 설치할 때 이미 디버그 빌드가 깔려 있으면 서명이 달라 설치되지 않는다. 디버그 앱을 먼저 지운다(`adb uninstall io.github.rjszzz.hwpreader`).

---

### 5. 배포 방식 (PRD 12장)

| 방식 | 파일 | 절차 |
| --- | --- | --- |
| APK 직접 설치 | `app-release.apk` | 폰으로 파일을 보내 설치한다. 처음에는 '출처를 알 수 없는 앱 설치' 허용이 필요하다. 업데이트도 같은 키로 서명한 새 APK 를 다시 설치한다 |
| Managed Google Play 비공개 앱 | `app-release.aab` | 기관 Play 관리 계정에서 비공개 앱으로 올린다. Play 앱 서명을 쓰면 Google 이 배포용 서명을 맡고, 위 키는 '업로드 키'가 된다 — 업로드 키도 잃지 않게 백업한다 |

- 기관 공식 배포라면 PRD 7장 기준(「모바일 전자정부 서비스 관리 지침」 제26조 보안약점 진단, 제27조 정기 점검)을 일정에 넣는다.

---

### 6. 출시 전 보안 점검 (M3)

출시마다 아래를 확인하고 결과를 PRD 부록 C 에 버전별로 적는다.

| 항목 | 확인 방법 |
| --- | --- |
| SEC-1 권한 없음(INTERNET 포함) | 빌드의 `checkReleasePermissions`, 3장 5번 |
| SEC-2 저장소 권한 없음, SAF·content URI 만 | 매니페스트, `MainActivity.handleIntent`(content:// 만 받음) |
| SEC-3 캐시 사본 삭제, 백업 제외 | `allowBackup=false`, `backup_rules`·`data_extraction_rules`, 문서를 닫은 뒤 `cache/open` 이 비는지 |
| SEC-4 WebView 격리 | 파일·content 접근 끔, 메시지 포트 1개, CSP, 링크 확인 창, WebView 사용 통계 끔 |
| SEC-5 최근 문서 기록 기본 끔 | 처음 실행 홈 화면 문구 |
| SEC-6 로그에 파일명·본문 없음 | 릴리스 빌드에서 `android.util.Log` 제거(R8 규칙). 뷰어 JS 의 console 출력은 `DocumentWebView` 의 `onConsoleMessage` 가 받아 버린다(logcat `chromium` 에 `[INFO:CONSOLE` 줄이 없어야 한다) |
| SEC-7 R8 난독화 | `isMinifyEnabled = true`, 매핑 파일 |
| 고지(11장) | 설정 → 오픈소스 라이선스(kordoc NOTICE·THIRD_PARTY 포함), 한컴 고지 문구 |
