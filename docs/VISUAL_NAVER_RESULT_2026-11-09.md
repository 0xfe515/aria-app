# 이미지 질문 및 네이버 도보 연결 결과 — 2026-11-09

## 적용 결과

- CloudCommandParser: 앞에 있는 거 뭐야, 앞에 무슨 글씨야, 앞쪽/전방/눈앞/주변의 물체·간판 질문, ChatGPT 호출·질문 접두사와 존댓말 표현을 사진 질문으로 분류하는 범위를 확장했다. 사진 프롬프트는 질문의 색상·대상·위치 등 원래 내용을 유지한다.
- 추상적인 카메라 질문, 일반 주변 맛집 질문, 문구 인용·가정, 부정·복합 명령이 사진 전송으로 오인되는 경우를 회귀 테스트에 포함했다. 의미를 무제한 이해하는 모델 분류기가 아니라 범위가 정해진 문맥·문장 형태 판별이다.
- 일반 길안내 명령은 네이버를 기본으로 사용한다. 길안내해줘 → 강남역의 후속 입력도 기존 30초 문맥으로 연결한다. 명시적인 구글·카카오 요청은 유지한다.
- Android Geocoder에서 유효한 목적지가 하나로 확인되면 nmap://route/walk에 목적지 좌표와 원래 질문의 장소 이름을 전달한다. 출발지는 네이버가 현재 위치로 처리하도록 생략한다. Aria에 새 위치 권한이나 API 키는 추가하지 않았다.
- 조회는 UI에서 4초 제한을 적용하며, 이전 Android의 동기 조회도 제한된 실행기와 취소 가능한 브리지로 감싼다. 모호함·조회 실패·미설치에는 원래 목적지 검색으로 연결한다. 화면 전환, 새 음성 입력, 취소, 중복·대체 요청을 확인해 오래된 결과의 앱 실행을 차단한다.

## 지원 범위와 한계

네이버 공식 URL Scheme은 도보 경로 탐색을 정의하며 완전히 숨긴 도보 안내 자동 시작을 보장하지 않는다. 따라서 네이버 화면이 열리며 앱에서 안내 시작이 필요할 수 있다. 출처: https://guide-gov.ncloud-docs.com/docs/naveropenapiv3-maps-url-scheme-url-scheme

외부 지도 연결 시 Aria 음성·비전 세션을 명시적으로 중지하지 않는다. 기존 ON_STOP 미리보기 분리 및 foreground service 수명 주기를 유지했다. 실제 기기의 동시 실행·안내 성능은 검증하지 않았다.

새 ChatGPT 사진 질문 실행은 기존 MainScreen의 screenVisible 조건을 따른다. 네이버가 전면인 동안의 새 사진 질문 실행은 이번 변경 범위에 포함되지 않는다. ChatGPT 로그인과 실행 중인 카메라가 필요하며 실제 서버·카메라의 종단 간 동작은 기기에서 확인하지 않았다.

## 독립 검증

Android Studio JDK와 기존 ignored local.properties SDK를 사용했다.

명령: .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain

- 최종 종료 코드 0, BUILD SUCCESSFUL in 1m 40s.
- JVM XML: 91 suites, 1,059 tests, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 197 warnings, 3 hints. 기존 대비 경고 증감은 측정하지 않았다.
- git diff --check: 종료 코드 0 (CRLF 변환 경고만 출력).
- 최종 로그: C:\Users\felice\AppData\Local\Temp\aria-visual-naver-20261109-final-gradle.log
- 앞선 통합본도 동일 명령으로 종료 코드 0, 1,056 tests 통과했으며 최종 작은 수정 후 다시 검증했다.
- APK: app/build/outputs/apk/debug/app-debug.apk (281,877,300 bytes).
- 실기기 테스트·설치, 커밋·푸시는 수행하지 않았다.

## 구현 및 감독 기록

Orca orchestration Run: run_452ee440164a. Gemini CLI는 로그인 필요로 모델 조회 불가였고, Copilot 두 시도는 실제 월간 무료 요청 소진 오류로 구현을 시작하지 못했다. 해당 시도를 정확한 터미널 종료 확인 후 정리하고, 정책에서 허용한 gpt-6-luna / medium 대체 워커를 사용했다. 최초 요청/실효 모델·effort를 확인했고 후속 작업은 동일하게 입증된 터미널을 새 Dispatch로 재사용했다. Codex는 명세·검토·독립 검증·문서화를 수행했다.

Task 결과: 이미지 범위 확장, 이미지 오분류 검토 수정, 네이버 연결, 목적지 이름 보존 검토 수정, 목적지 후속 입력 보완 모두 succeeded 후 독립 검증 통과. 마지막 워커를 release하고 완료 메시지를 ack한 뒤 Run의 reclaimable worker 목록이 비어 있음을 확인했다. 관련 개별 보고서는 visual-intent-20261109.md, visual-intent-review-20261109.md, naver-walk-20261109.md, naver-walk-review-20261109.md, naver-followup-20261109.md에 있다.

후속 사용자 배포 요청으로 버전 11 / 1.0.0-preview.20261109.1을 생성하고 Pixel 3 설치 및 관련 기기 테스트 13개를 검증했다. 앞선 실기기 미실행 기록은 최초 구현 단계의 상태이며, 후속 결과와 설치 참고는 [릴리즈 기록](RELEASE_PREVIEW_2026-11-09_1.md)을 참조한다.
