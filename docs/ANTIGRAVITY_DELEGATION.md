# Antigravity CLI 기반 Gemini 4 Argon 위임 워크플로

작성 및 검증 기준일: **2026-10-20**

이 문서는 `aria-app` 저장소에서 **Codex(`Codex/gpt-sol`)**와 **Gemini 4 Argon(`gemini-4-argon-high`)** 간의 계획·구현 역할 분리 원칙과 공식 Antigravity CLI 헤드리스(headless) 위임 절차를 정의합니다.

- 공식 CLI 헤드리스 문서: <https://www.antigravity.google/docs/cli/headless/>
- 저장소 기본 지침: [AGENTS.md](../AGENTS.md)

---

## 1. 역할 분리 및 거버넌스 원칙

1. **Codex (`Codex/gpt-sol`) — 계획·명세·검토·독립 검증**
   - 요구사항 분석, 작업 계획 수립, 정밀한 작업 명세(Task Specification) 작성, 변경분(`git diff`, `git diff --check`) 검토, 독립적인 빌드·테스트 실행 및 최종 검증을 담당합니다.
   - 문서(Documentation) 및 작업 명세서는 Codex가 직접 작성할 수 있습니다.
   - 구현 위임 전에 반드시 관련 저장소 지침(`AGENTS.md`, 관련 `docs/*.md`)을 먼저 읽고 명세에 반영합니다.
2. **Gemini 4 Argon (`gemini-4-argon-high`) — 전용 구현 워커**
   - 모든 프로덕션 소스 코드, 단위/기기 테스트, 빌드 스크립트/설정 코드, 자동화 코드는 공식 Antigravity CLI를 통해 명시적으로 선택된 **Gemini 4 Argon** 워커만 작성하거나 수정합니다.
   - 별도의 명시적 지시가 없는 한 워커는 `git commit` 또는 `git push`를 실행하지 않습니다.
3. **모델 고정(Pinning) 및 장애 처리 원칙**
   - 모든 구현 작업에는 반드시 `--model gemini-4-argon-high`를 명시하여 모델을 고정합니다.
   - 다른 모델로 임의 대체(silent substitution)하는 것은 금지됩니다.
   - 만약 CLI 실행, 인증, 또는 `gemini-4-argon-high` 모델 가용성에 실패하면 즉시 구체적인 차단 요인(blocker)을 보고하고 중단하며, Codex가 대신 구현 코드를 작성하지 않습니다.
4. **프롬프트 계약 범위 제한**
   - 이 문서에 정의된 구조화된 작업 명세 계약은 로컬 환경에서 검증된 위임 인터페이스입니다. 로컬에서 테스트된 계약을 넘어서는 보편적인 모델 특화 프롬프트 최적화를 주장하지 않습니다.

---

## 2. 공식 CLI 실행 파일 및 로컬 환경 정보

- **공식 실행 파일 경로**: `%LOCALAPPDATA%\agy\bin\agy.exe` (PowerShell: `"$env:LOCALAPPDATA\agy\bin\agy.exe"`)
- **설치 버전**: `1.4.3`
- **PATH 주의사항**: 현재 로컬 환경에서 `agy.exe`는 전역 `PATH`로 자동 해석되지 않습니다. 시스템 전역 `PATH`나 CLI 전역 설정을 임의로 변경하지 말고, 항상 **전체 경로(full path)**로 호출합니다.
- **확인된 Argon 모델 목록**: CLI에서 조회된 사용 가능 모델 목록에 `gemini-4-argon-{low,medium,high}`가 포함되어 있음을 확인했습니다.
  - `gemini-4-argon-low`
  - `gemini-4-argon-medium`
  - `gemini-4-argon-high` *(구현 작업 필수 고정 모델)*

---

## 3. PowerShell 헤드리스 호출 규칙

구현 위임 시에는 광범위한 권한 우회 플래그를 사용하지 않고, `--mode accept-edits`와 허용 파일 경계를 조합하여 실행합니다. 작업 명세 텍스트(`$taskSpec`)는 변수 확장이나 이스케이프 왜곡을 방지하기 위해 반드시 **작은따옴표 Here-String(`@' ... '@`)**으로 정의합니다.

```powershell
$agy = Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe'

$taskSpec = @'
TASK_ID: ARIA-EXAMPLE-001
... (정밀 작업 명세 본문) ...
'@

& $agy `
  --model gemini-4-argon-high `
  --mode accept-edits `
  --output-format stream-json `
  --print-timeout 180s `
  -p $taskSpec
```

### 헤드리스 셸 명령 제한 및 검증 책임

- `--mode accept-edits` 헤드리스 실행 중에는 CLI 최종 상태가 `SUCCESS`로 끝나더라도 워커 내부의 셸 명령 실행이 정책에 의해 거부(denied)될 수 있습니다.
- 따라서 워커는 허용된 파일 읽기/쓰기 및 코드 수정에 집중하고, **Codex가 독립적으로 검증 명령(Gradle 빌드, 단위 테스트, Lint 등)을 실행**하여 결과를 확인합니다.

---

## 4. 수용(Acceptance) 및 독립 검증 기준

CLI 출력에서 단순히 `status: "SUCCESS"`만 확인하는 것으로는 작업을 수용할 수 없습니다. Codex는 모든 위임 실행 후 다음 항목을 독립적으로 검증해야 합니다.

1. **프로세스 종료 코드**: CLI 프로세스 종료 코드(`$LASTEXITCODE`)가 `0`인지 확인합니다.
2. **`stream-json` 초기화 이벤트(`init`)**:
   - `init.model` 값이 정확히 `"gemini-4-argon-high"`인지 확인합니다.
   - `init.cwd` 값이 대상 작업 디렉터리(`C:\Users\felice\orca\aria-app`)와 일치하는지 확인합니다.
3. **`stream-json` 최종 결과 이벤트(`result`)**:
   - 최종 `status`가 `"SUCCESS"`인지 확인합니다.
4. **파일 변경 경계 및 Diff 검사**:
   - `git status --short`, `git diff`, `git diff --check`를 통해 `ALLOWED_CHANGES`에 지정된 경로만 수정되었고 `FORBIDDEN` 제약이 준수되었는지 검사합니다.
5. **독립 검증 및 수정 재위임**:
   - 명세에 정의된 검증 명령을 실행하고 수용 기준 충족 여부를 확인합니다.
   - 미비점이나 오류가 발견되면 Codex가 직접 코드를 수정하지 않고, 구체적인 수정 명세를 작성하여 `gemini-4-argon-high` 워커에 후속 수정 작업을 재위임합니다.

---

## 5. 재사용 가능한 정밀 작업 명세 템플릿

`AGENTS.md` 요구사항에 따라 모든 위임 작업 명세는 아래 9개 필수 항목을 포함해야 합니다.

```powershell
$taskSpec = @'
TASK_ID: ARIA-<DOMAIN>-<NNN>
ROLE: You are Gemini 4 Argon, the implementation worker. Codex/gpt-sol plans, writes precise task specifications, inspects results, and independently verifies; all source code, tests, build scripts, and automation code must be authored or modified by Gemini 4 Argon only.
WORKSPACE: C:\Users\felice\orca\aria-app
GOAL: <단일하고 명확한 구현 목표 요약>
READ_FIRST: <작업 전 반드시 읽어야 할 파일 목록 (예: AGENTS.md, 관련 소스/문서)>
CONTEXT:
- <현재 코드 상태, 관련 증거, 선행 분석 내용>
ALLOWED_CHANGES:
- <수정 또는 생성이 허용된 정확한 상대 경로 목록>
FORBIDDEN:
- <수정 금지 경로 및 행위>
- Do not modify files outside ALLOWED_CHANGES.
- Do not run git commit or git push unless separately instructed.
- Do not alter global PATH, CLI settings, or credentials.
REQUIREMENTS:
- <정상 동작 요구사항>
- <경계 조건 및 예외/에지 케이스(edge cases) 처리 요구사항>
VALIDATION_COMMANDS:
- <Codex 또는 허용된 환경에서 실행할 정확한 검증 명령>
ACCEPTANCE_CRITERIA:
- <수용 판정을 위한 구체적이고 검증 가능한 기준>
OUTPUT_FORMAT:
Return a compact completion report with:
1. CHANGED_FILES: Exact list of created/modified files.
2. REQUIREMENT_MAPPING: Brief mapping of each requirement to the change made.
3. COMMANDS_RUN: Exact commands actually executed with exit codes and results (or explicitly state that no commands were run).
4. BLOCKERS_AND_UNVERIFIED: Any blockers encountered and any claims requiring Codex independent verification.
'@
```

### 워커 완료 보고 형식 (Compact Completion Format)

워커는 작업 종료 시 아래와 같은 간결한 구조로 응답해야 하며, 자신이 직접 수행하지 않은 검증을 수행했다고 주장해서는 안 됩니다.

```text
CHANGED_FILES:
- <변경된 파일 경로 목록>

REQUIREMENT_MAPPING:
- <요구사항 항목>: <반영 내용 요약>

COMMANDS_RUN:
- <실제 실행한 명령 및 종료 코드/결과, 또는 "No commands were run">

BLOCKERS_AND_UNVERIFIED:
- Blockers: <차단 요인 또는 None>
- Unverified by worker: <Codex가 독립 검증해야 할 항목>
```

---

## 6. Android 변경 시 필수 검증 명령 및 작업 구분

### 응답/파일 프로브와 실제 Android 기능 구현의 구분

- **연결·응답 프로브 및 파일 조작 테스트**: CLI 호출 계약, 모델 고정(`gemini-4-argon-high`), 파일 생성/편집 경계 준수 여부를 확인하기 위한 경량 검증입니다. 이것만으로는 Android 앱의 빌드 무결성이나 런타임 동작이 검증되지 않습니다.
- **실제 Android 기능·코드·빌드 설정 구현**: `app/` 하위의 Kotlin 코드, 리소스, 매니페스트, 테스트, 또는 Gradle 설정을 변경하는 실제 작업에서는 반드시 아래의 Android 빌드·단위 테스트·Lint 검증을 수행해야 합니다.

### Android 표준 검증 명령 (Windows PowerShell)

`AGENTS.md`의 빌드/테스트 권한 및 격리 환경 지침에 따라 Android Studio 번들 JDK(`jbr`)를 지정하여 실행합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

승인되고 연결된 Android 기기 또는 에뮬레이터가 준비된 경우에만 기기 계측 테스트를 추가로 실행합니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:connectedDebugAndroidTest
```

---

## 7. 검증된 위임 증거 기록 (2026-10-20)

2026-10-20 로컬 환경에서 공식 Antigravity CLI(`%LOCALAPPDATA%\agy\bin\agy.exe`, 버전 `1.4.3`)를 사용하여 다음 항목을 실측 검증했습니다.

1. **CLI 모델 목록 조회 성공**
   - 공식 실행 파일을 통해 모델 목록을 정상 조회했으며, `gemini-4-argon-low`, `gemini-4-argon-medium`, `gemini-4-argon-high`가 포함되어 있음을 확인했습니다.
2. **연결성 프로브 (Connectivity Probe)**
   - **Conversation ID**: `c81976f2-9d30-47ad-8eb6-b6761300d09e`
   - **관찰된 `init.model`**: `gemini-4-argon-high`
   - **최종 상태(`result.status`)**: `SUCCESS`
   - **정확한 응답 출력**: `AGY_ARGON_HANDOFF_OK`
3. **코드 작성 프로브 (Code Probe)**
   - **Conversation ID**: `0d900ceb-c514-4bb9-8366-f445952ac4de`
   - **관찰된 `init.model`**: `gemini-4-argon-high`
   - **최종 상태(`result.status`)**: `SUCCESS`
   - **작업 범위**: 저장소 외부의 일회성 임시 작업 공간(disposable temp workspace)에서 워커가 오직 `delegation-probe.cjs` 파일만 작성했습니다.
   - **구현 및 검증 로직**: 생성된 프로그램은 `taskId`, `goal`, `allowedFiles`, `acceptanceCriteria` 필드를 검증하며, 유효 입력(valid)과 누락(missing)·공백(blank)·빈 배열(empty)·비문자열(non-string) 입력에 대한 단언(assertions)을 포함합니다.
   - **Codex 독립 검증 결과**: Codex가 생성된 소스를 독립적으로 검사한 뒤 `node`로 해당 파일을 실행하여 종료 코드 `0` 및 정확한 출력 `ARGON_CODE_PROBE_PASS`를 확인했습니다.
4. **저장소 위생(Hygiene) 유지**
   - 두 프로브 실행 동안 Aria 프로덕션 코드는 일절 변경되지 않았습니다.
   - 임시 로그와 프로브 산출물은 저장소 외부에 위치하며, 인증 정보(credentials)나 로컬 머신 상태를 저장소에 복사하지 않습니다.
