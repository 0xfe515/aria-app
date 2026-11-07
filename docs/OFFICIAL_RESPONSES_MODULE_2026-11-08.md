# ChatGPT Official Responses Client Module (2026-11-08)

## 1. 개요 및 목적

본 모듈은 Aria 음성 안내 시스템에서 ChatGPT 공식 OAuth 플랜 기반 실시간 스트리밍 질의응답을 처리하는 전용 클라이언트 패키지(`com.ariateam.aria.cloud.chatgpt`)입니다.
API 키 방식의 우회 경로나 사설 백엔드(`backend-api`)를 일절 배제하고, OpenAI의 공개 공식 엔드포인트(`https://api.openai.com/v1`)만을 사용합니다.

## 2. 참조 공개 구현 및 라이선스 고지 (License & Reference Citation)

- **공식 DevKit 참조**: `https://github.com/openai/sign-in-with-chatgpt-devkit` (commit `0a36fefeb913055c8c7a1a29b63d82b96b2e841a`).
  - *라이선스 주의사항*: 해당 DevKit은 `SIGN-IN WITH CHATGPT DEVKIT NONCOMMERCIAL LICENSE v1.0`이 적용되어 있습니다. 따라서 DevKit의 소스 코드나 자산을 직접 복사/개작하지 않고, 공식 문서화된 HTTP REST/SSE 프로토콜 명세만을 참조하여 Aria용 Kotlin 모듈로 독립 구현하였습니다.
- **스트리밍 파이프라인 참조**: `openai/openai-python` (Apache License 2.0).
  - SSE 이벤트 생명주기(`response.output_text.delta`, `response.completed`, `response.failed`, `response.incomplete`) 및 텍스트 청크 수집 패턴 참조.
- **표준 JSON 파서 활용**:
  - 프로토콜 페이로드 역직렬화에 자체 문자열 스캐닝을 사용하지 않고, JVM 및 Android 표준인 `org.json.JSONObject` / `org.json.JSONArray`를 사용하여 엄격한 경로와 타입을 검증합니다.
- **W3C / WHATWG SSE 표준**:
  - `data:`, `event:` 파싱, 복수 줄(`\n`) 데이터 결합, 빈 줄(`\n\n`) 이벤트 디스패치 표준 준수.

## 3. 엔드포인트 및 프로토콜 규격

### 3.1 모델 카탈로그 조회
- **엔드포인트**: `GET https://api.openai.com/v1/models`
- **인증 헤더**: `Authorization: Bearer <ACCESS_TOKEN>`
- **모델 결정 규칙**:
  - 임의의 하드코딩 기본 모델(`DEFAULT_FALLBACK_MODEL`)을 두지 않고, 반드시 계정 카탈로그로부터 모델을 도출.
  - 응답 내 `models` 배열(또는 `data` 배열) 파싱
  - `visibility == "list"` 필터링
  - 서버 반환 순서를 엄격히 보존하며, 첫 번째로 노출된 모델을 기본 모델로 결정 (임의 순위/가격 추정 배제)
  - 카탈로그가 비어있거나 실패한 경우 즉시 명확한 오류(`GeminiErrorKind.MODEL_UNAVAILABLE`) 반환 (가용성 조작 금지)
  - 호출자가 명시한 모델이 있는 경우 해당 모델 우선 사용

### 3.2 Responses API 스트리밍 질의
- **엔드포인트**: `POST https://api.openai.com/v1/responses`
- **헤더**:
  - `Authorization: Bearer <ACCESS_TOKEN>`
  - `Content-Type: application/json`
  - `Accept: text/event-stream`
- **요청 본문 필수 플래그**:
  - `"store": false`
  - `"stream": true`
  - `"instructions"`: Aria 음성 비서로서의 간결하고 친절한 한국어 구어 답변 지침 (`"Aria 음성 비서로서 사용자의 질문에 친절하고 간결하게 한국어 구어로 2~3문장 이내로 핵심만 답변하세요."`)
- **텍스트 질의 구조**:
  ```json
  {
    "model": "gpt-6.1-sol",
    "input": [
      {
        "role": "user",
        "content": [
          { "type": "input_text", "text": "오늘 날씨 어때?" }
        ]
      }
    ],
    "instructions": "Aria 음성 비서로서 사용자의 질문에 친절하고 간결하게 한국어 구어로 2~3문장 이내로 핵심만 답변하세요.",
    "store": false,
    "stream": true
  }
  ```
- **사진 질의 구조 (명시적 촬영 질문에 한함)**:
  - 카메라 지속 업로드를 금지하고, 명시적 사진 질문 발생 시에만 1회성 `input_image`(`data:image/jpeg;base64,...`) 첨부
  ```json
  {
    "model": "gpt-6.1-sol",
    "input": [
      {
        "role": "user",
        "content": [
          {
            "type": "input_image",
            "image_url": "data:image/jpeg;base64,<BASE64_JPEG>"
          },
          {
            "type": "input_text",
            "text": "이 물건이 무엇인가요?"
          }
        ]
      }
    ],
    "instructions": "Aria 음성 비서로서 사용자의 질문에 친절하고 간결하게 한국어 구어로 2~3문장 이내로 핵심만 답변하세요.",
    "store": false,
    "stream": true
  }
  ```
- **금지 필드 생략**:
  - `max_output_tokens`, `temperature`, `previous_response_id`, `background`, `conversation`, `top_p` 등 비지원 필드는 완전히 생략.

## 4. 스트리밍 파서 및 안전성 설계 (ChatGptSseParser)

1. **완료 보증 (Completion Requirement) 및 즉시 종료**:
   - `response.completed`, `response.failed`, `response.incomplete` 등 단말 이벤트 수신 즉시 파싱 루프를 종료하고 반환 (불필요한 EOF 대기 제거).
   - `response.completed` 이벤트가 도착하기 전까지는 절대 성공으로 처리하지 않음.
   - 중간 중단, 에러, 네트워크 단절 시 부분 텍스트를 음성 발화하거나 실행하지 않고 에러 반환.
2. **엄격한 경로 추출 및 위조 방지**:
   - `org.json`을 사용하여 `response.output[].content[].text` (type: "message", part type: "output_text")의 엄격한 경로만 추출. 임의의 중첩 텍스트나 도구 호출 텍스트가 단말 출력으로 위조되는 것을 방지.
   - 단말 출력 대체: 델타 없이 `response.completed`가 도착한 경우에만 중복 없이 안전하게 1회 추출.
3. **인식된 이벤트 형식 오류 시 Fail-Closed**:
   - `response.output_text.delta`, `response.completed`, `response.failed` 등 인식된 이벤트의 페이로드가 올바른 JSON이 아니거나 필수 필드가 누락된 경우 fail-closed(`SseParseOutcome.Interrupted`)로 즉시 처리.
4. **버퍼 한도 방어**:
   - 한 줄 읽기 도중 실시간으로 한도를 검사하여 64KB 초과 시 즉시 차단(비정상 라인 대량 버퍼링 방지).
   - 전체 스트림 1MB 한도 제한.
   - TTS 발화를 고려하여 최종 성공 텍스트를 1,000자로 바운딩(`MAX_RESPONSE_CHARS`).

## 5. 오류 처리 및 보안 (Error Mapping & Secret Redaction)

- **HTTP 401 / `subscription_sharing_invalid_user`**: `AUTH_FAILED` (계정 인증 만료 안내)
- **HTTP 403 / `subscription_sharing_user_not_eligible`**: `AUTH_FAILED` (플랜 권한 없음 안내)
- **HTTP 403 / `chatpass_v2_scope_not_authorized`**: `AUTH_FAILED` (직접 사용 권한 미부여 안내)
- **HTTP 429 / `subscription_sharing_usage_limit_exceeded`**: `RATE_LIMIT` (사용 한도 초과 및 설정 안내)
- **HTTP 503 / `subscription_sharing_usage_unavailable`**: `SERVER_ERROR` (일시적 서비스 지연 안내)
- **HTTP 408 / 504**: `TIMEOUT` (응답 시간 초과 안내)
- **HTTP 499 / 취소**: `CANCELLED` (질문 취소)
- **비밀 정보 마스킹 (Secret Redaction)**:
  - `ChatGptHttpRequest.toString()`에서 Bearer 토큰 및 API 키 헤더 마스킹.
  - 에러 로그 및 사용자 반환 메시지에서 JWT, 세션, Bearer 토큰 정규식 치환 (`[REDACTED_TOKEN]`).

## 6. 공유 인터페이스 및 코디네이터 호환성

- `ChatGptClient`는 기존 `CloudQuestionCoordinator`의 블로킹 I/O 패턴과 호환되도록 설계되었습니다.
- 메서드 시그니처:
  `fun generateContent(prompt: String, imageBase64: String?, accessToken: String, cancellationToken: GeminiCancellationToken?): GeminiResult`
- 취소 신호(`GeminiCancellationToken`) 발생 시 활성 `HttpURLConnection`의 `disconnect()` 및 스트림 `close()`가 즉시 연동됩니다.
