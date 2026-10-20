# Orca 오케스트레이션 및 Antigravity CLI 기반 Gemini 4 Argon 위임 워크플로

작성 및 검증 기준일: **2026-10-20**

이 문서는 `aria-app` 저장소에서 **Codex(`Codex/gpt-sol`)**와 **Gemini 4 Argon(`gemini-4-argon-high`)** 간의 계획·구현 역할 분리 원칙, **Orca 감독 오케스트레이션(기본 워크플로)** 절차, 그리고 **직접 Antigravity CLI 헤드리스 호출(저수준 진단 폴백)** 절차를 정의합니다.

- 저장소 기본 지침: [AGENTS.md](../AGENTS.md)
- 공식 Antigravity CLI 헤드리스 문서: <https://www.antigravity.google/docs/cli/headless/>

---

## 1. 역할 분리 및 기본 조율(Orchestration) 원칙

1. **기본 감독 워크플로 — Orca Orchestration Skill 필수 사용**
   - 감독이 필요한 모든 구현 위임은 원시(raw) `agy.exe` 프로세스 직접 실행이나 비-Orca 서브에이전트로 대체하지 않고, 반드시 버전이 일치하는 **Orca `orchestration` 스킬**(`orca skills get orchestration --json`)과 실제 Orca 런타임의 `Run` / `Task` / `Dispatch` 상태를 기본 조율 계층으로 사용합니다.
   - **Codex(`Codex/gpt-sol`)**가 코디네이터(Coordinator) 역할을 수행하며, `orca orchestration worker-start --spec $taskSpec --worktree current --agent antigravity --model gemini-4-argon-high --json`으로 **Gemini 4 Argon** 구현 워커를 선택 및 기동합니다.
   - 감독 생명주기(`Run`/`Task`/`Dispatch`)가 필요 없는 단순 비감독 핸드오프(unsupervised handoff)나 터미널 전용 제어는 공식 스킬 지침에 따라 `orca-cli`(`orca skills get orca-cli --json`) 영역에 속합니다.
   - 공식 실행 파일(`%LOCALAPPDATA%\agy\bin\agy.exe`) 직접 호출은 기본 감독 워크플로가 아니며, 오직 **저수준 진단 폴백(diagnostic fallback)** 용도로만 유지합니다.
2. **Codex (`Codex/gpt-sol`) — 코디네이터·계획·명세·독립 검증·Git 통합**
   - 요구사항 분석, 작업 계획 수립, 정밀한 작업 명세(Task Specification) 작성, 변경분(`git diff`, `git diff --check`) 검토, 독립적인 빌드·테스트 실행, 최종 검증 및 Git 통합을 담당합니다.
   - 문서(Documentation) 및 작업 명세서는 Codex가 직접 작성하거나, 명시적 작업 명세 하에 Gemini 4 Argon 워커에게 위임할 수 있습니다.
   - 구현 위임 전에 반드시 관련 저장소 지침(`AGENTS.md`, 관련 `docs/*.md`)과 Orca `orchestration` 스킬을 먼저 읽고 명세에 반영합니다.
3. **Gemini 4 Argon (`gemini-4-argon-high`) — 전용 구현 워커**
   - 모든 프로덕션 소스 코드, 단위/기기 테스트, 빌드 스크립트/설정 코드, 자동화 코드는 명시적으로 선택된 **Gemini 4 Argon** 워커만 작성하거나 수정합니다.
   - 별도의 명시적 지시가 없는 한 워커는 `git commit` 또는 `git push`를 실행하지 않습니다.
4. **모델 고정(Pinning) 및 장애 처리 원칙**
   - 모든 구현 작업에는 반드시 `--model gemini-4-argon-high`를 명시하여 모델을 고정합니다.
   - 다른 모델로 임의 대체(silent substitution)하는 것은 금지됩니다.
   - 만약 Orca 런타임 준비 상태, 인증, `gemini-4-argon-high` 모델 가용성, 또는 CLI 실행에 실패하면 즉시 구체적인 차단 요인(blocker)을 보고하고 중단하며, Codex가 대신 구현 코드를 작성하지 않습니다.
5. **프롬프트 계약 범위 제한**
   - 이 문서에 정의된 구조화된 작업 명세 계약은 로컬 환경에서 검증된 위임 인터페이스입니다. 로컬에서 테스트된 계약을 넘어서는 보편적인 모델 특화 프롬프트 최적화를 주장하지 않습니다.

---

## 2. Orca 감독 오케스트레이션 표준 명령 시퀀스 (기본 워크플로)

아래 명령 시퀀스에서 `<objective>`, `<dispatch_id>`, `<delivery_id>`, `<run_id>`, `<worker_terminal_id>`, `<task_id>` 등 꺾쇠(`<>`)로 표기된 값은 **자리표시자(placeholder)**입니다. 코디네이터와 워커는 자리표시자를 문자 그대로 붙여넣어 실행하지 말고, 반드시 실제 런타임 영수증(receipt) 또는 주입된 프리앰블(preamble)의 실측 값으로 치환해야 합니다. 문서에 없는 임의의 플래그를 발명하여 사용하지 않습니다.

### 코디네이터 표준 실행 순서 (PowerShell)

```powershell
# 1. 버전 일치 Orca orchestration 스킬 로드 (필요 시 비감독 터미널용 orca-cli 참조)
orca skills get orchestration --json

# 2. Orca 런타임 상태 확인
orca status --json

# 3. 감독 Run 생성 (<objective>는 실제 작업 목표로 치환; 반환된 <run_id> 기록)
orca orchestration run-create --objective <objective> --json

# 4. 작은따옴표 Here-String으로 정밀 작업 명세($taskSpec) 정의 후 Gemini 4 Argon 워커 기동
$taskSpec = @'
TASK_ID: ARIA-<DOMAIN>-<NNN>
... (9개 필수 항목 및 Orca 정렬 명세 본문) ...
'@

orca orchestration worker-start --spec $taskSpec --worktree current --agent antigravity --model gemini-4-argon-high --json

# 5. 워커 이벤트 대기 (worker_done, escalation, question)
orca orchestration check --wait --types 'worker_done,escalation,question' --timeout-ms 30000 --json

# 6. 수용 전 보고서 및 결과 검증 (Independent Verification)
# - worker-start 영수증의 launch.requested 대 launch.effective 확인
# - worker_done 메시지의 taskId, dispatchId, outcome 및 실제 워커 작업 증거 확인
# - git status --short, git diff --name-only, git diff --check 및 명세된 검증 명령 실행

# 7. 검증 완료 후 워커 재사용/유지/해제 결정 (해제 시 <dispatch_id>는 실제 활성 Dispatch ID)
orca orchestration worker-release --dispatch <dispatch_id> --json

# 8. 워커 처리 결정 완료 후 배달 메시지 확인 응답 (<delivery_id>는 실제 수신 deliveryId)
orca orchestration check --ack <delivery_id> --json

# 9. 해제 후 회수 가능(reclaimable) 상태로 남은 워커가 없는지 확인 (<run_id>는 실제 Run ID)
orca orchestration worker-list --run <run_id> --terminal-state reclaimable --json
```

### 워커 및 코디네이터 생명주기 의무 사항

1. **워커(Worker) 의무 사항**
   - **라이브 프리앰블 기준 실행 파일·플래그·식별자 사용**: 라이브 Orca 프리앰블에 주입된 실행 파일, 명령 플래그, 워커 터미널 핸들(`--from <worker_terminal_id>` / `--terminal <worker_terminal_id>`), `--task-id <task_id>`, `--dispatch-id <dispatch_id>`만 그대로 사용합니다. 식별자나 플래그를 임의로 생성하거나 이전 작업의 값을 재사용하지 않습니다.
   - **프리앰블 주기 기반 하트비트 및 차단 보고**: 작업 중에는 고정된 주기를 임의로 하드코딩하지 않고, **라이브 주입 프리앰블이 지정한 주기(cadence)**에 따라 하트비트(`orca orchestration send --from <worker_terminal_id> --type heartbeat --subject "alive" --task-id <task_id> --dispatch-id <dispatch_id> --phase "<investigating|implementing|reviewing|waiting>"`)를 전송합니다(`check --wait` 또는 `ask` 대기 중일 때만 생략). 질문이 있으면 로컬 프롬프트 대신 `orca orchestration ask`를 사용하고, 선행 해결이 필요한 차단 사항은 `--type escalation`으로 보고합니다.
   - **체크포인트 및 완료 직전 인박스 확인**: 새 파일 작업을 시작하기 전, 테스트 실행 후 등 자연스러운 체크포인트마다, 그리고 **`worker_done`을 전송하기 직전에 반드시 한 번 더** `orca orchestration check --terminal <worker_terminal_id> --json`을 실행하여 코디네이터의 추가 지시를 확인합니다.
   - **단일 `worker_done` 전송 및 유휴(Idle) 전환**: 작업 완료 시 `--outcome succeeded` 또는 `--outcome failed`, 비어 있지 않은 3문장 요약(`--body`), 그리고 실제 변경된 파일(`--files-modified`) 및 실제 생성된 보고서 경로(`--report-path`, 해당 시에만)를 담아 `worker_done`을 **정확히 1회** 전송합니다. 예시 자리표시자를 문자 그대로 전송하지 않습니다. 전송 후에는 폴링/슬립 루프나 추가 `check` 호출 없이 즉시 턴을 종료하고 유휴 프롬프트 상태로 대기합니다(셸은 종료하지 않음).
2. **코디네이터(Coordinator) 의무 사항**
   - **모든 수신 메시지 처리**: 전달된 모든 메시지(`worker_done`, `escalation`, `question`)를 처리하고, 워커의 질문(`question`)에 응답하여 차단을 해제합니다.
   - **활성 Dispatch 대조 및 결과 검증**: 수신된 `worker_done`의 `taskId`와 `dispatchId`가 현재 활성 Dispatch와 일치하는지 확인하고, 산출물과 독립 검증 결과를 먼저 확인합니다.
   - **선(先) 재사용/유지/해제 결정, 후(後) `check --ack`**: 배달 메시지 확인 응답(`check --ack <delivery_id> --json`)을 보내기 전에 반드시 워커의 재사용(reuse), 유지(retention), 또는 해제(`worker-release --dispatch <dispatch_id> --json`)를 먼저 수행합니다.
   - **수동 완료 갱신 금지**: `worker_done` 처리 후 별도의 수동 `task-update` 완료 명령을 실행하지 않습니다.
   - **타임아웃 시 성급한 정리 금지**: `check --wait`가 빈 결과로 반환되거나 생존 여부(liveness)가 불확실하다는 이유만으로 즉시 작업 취소, 재기동, 또는 작업 공간 정리를 수행하지 않으며, 반드시 오케스트레이션 스킬의 복구 영수증(recovery receipts) 절차를 따릅니다.

---

## 3. 재사용 가능한 정밀 작업 명세 템플릿 (9개 필수 항목 + Orca 정렬)

`AGENTS.md`의 9개 필수 명세 항목을 유지하면서 Orca의 **Target / Change / Constraints / Ownership / Observable acceptance** 구조에 정렬합니다. 작업 명세 텍스트(`$taskSpec`)는 변수 확장 왜곡을 방지하기 위해 작은따옴표 Here-String(`@' ... '@`)으로 정의합니다.

```powershell
$taskSpec = @'
TASK_ID: ARIA-<DOMAIN>-<NNN>
TARGET/WORKSPACE: C:\Users\felice\orca\aria-app (<대상 모듈 또는 문서 범위>)
GOAL/CHANGE: <단일하고 명확한 구현 목표 및 변경 요약>
ROLE/OWNERSHIP: You are the Gemini 4 Argon implementation worker; Codex is the coordinator responsible for planning, precise specifications, independent diff/validation review and Git integration. All production code, tests, build scripts, and automation code remain exclusively authored by Gemini 4 Argon. Follow your live Orca injected Task/Dispatch preamble exactly for lifecycle communication, heartbeats, inbox checks and worker_done.
READ_FIRST: AGENTS.md, docs/ANTIGRAVITY_DELEGATION.md, <추가 필독 파일>
CONTEXT/EVIDENCE:
- <현재 코드/런타임 상태, 선행 검증 증거, 관련 Run/문맥 정보>
ALLOWED_CHANGES:
- <워커가 이번 Dispatch에서 배타적으로 수정/생성할 정확한 상대 경로 목록>
FORBIDDEN/CONSTRAINTS:
- <수정 금지 경로 및 금지 행위>
- Do not modify files outside ALLOWED_CHANGES.
- Do not run git commit or git push unless separately instructed.
- Do not alter global PATH, CLI global settings, or credentials.
REQUIREMENTS:
1. <정상 동작 요구사항>
2. <경계 조건 및 에지 케이스(edge cases) 처리 요구사항>
VALIDATION_COMMANDS/OWNER:
- <워커 또는 코디네이터가 실행할 정확한 검증 명령과 실행 주체 명시>
ACCEPTANCE_CRITERIA:
- <코디네이터가 수용 판정에 사용할 관찰 가능하고 구체적인 기준(Observable acceptance)>
OUTPUT_FORMAT:
Three-sentence executive completion summary (what you did, what you found/validated, what remains for coordinator verification); send it through worker_done exactly once with explicit --outcome (succeeded or failed) and real --files-modified entries as the live preamble instructs, then end your turn and idle.
'@
```

---

## 4. 저수준 진단 폴백: 공식 Antigravity CLI 직접 호출

Orca 런타임 외부에서 CLI 연결성이나 모델 응답을 단독으로 분리 진단해야 하는 경우에만 공식 실행 파일을 직접 호출합니다. 이 경로는 **저수준 진단 폴백**이며 기본 감독 구현 워크플로를 대체하지 않습니다.

### 로컬 CLI 실행 파일 정보

- **공식 실행 파일 경로**: `%LOCALAPPDATA%\agy\bin\agy.exe` (PowerShell: `"$env:LOCALAPPDATA\agy\bin\agy.exe"`)
- **설치 버전**: `1.4.3`
- **PATH 주의사항**: 전역 `PATH`나 CLI 전역 설정을 임의로 변경하지 말고 항상 **전체 경로(full path)**로 호출합니다.
- **확인된 Argon 모델 목록**: `gemini-4-argon-low`, `gemini-4-argon-medium`, `gemini-4-argon-high` *(구현 작업 필수 고정 모델)*

### 직접 CLI 헤드리스 호출 예시 (진단 폴백 전용)

```powershell
$agy = Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe'

$taskSpec = @'
TASK_ID: ARIA-DIAG-001
... (9개 필수 항목을 포함한 진단 작업 명세 본문) ...
'@

& $agy `
  --model gemini-4-argon-high `
  --mode accept-edits `
  --output-format stream-json `
  --print-timeout 180s `
  -p $taskSpec
```

- `--mode accept-edits` 헤드리스 실행 중에는 CLI 최종 상태가 `SUCCESS`로 끝나더라도 워커 내부의 셸 명령 실행이 정책에 의해 거부(denied)될 수 있으므로, 검증 명령은 Codex가 독립적으로 실행합니다.
- 직접 CLI 폴백 실행 시 워커에게 구조화된 텍스트 보고가 필요한 경우 `CHANGED_FILES`, `REQUIREMENT_MAPPING`, `COMMANDS_RUN`, `BLOCKERS_AND_UNVERIFIED` 4개 항목으로 구성된 간결한 완료 보고 형식을 사용합니다.

---

## 5. 수용(Acceptance) 및 독립 검증 기준

Codex는 위임 경로(기본 Orca 감독 실행 vs. 저수준 직접 CLI 폴백)에 맞는 증거를 독립적으로 확인한 뒤 작업을 수용합니다.

### A. Orca 감독 실행 검증 기준 (기본 워크플로)

1. **런치 영수증 모델 검증 (`launch.requested` 대 `launch.effective`)**:
   - 요청 모델(`launch.requested`)만으로는 증거가 되지 않습니다. Codex는 `worker-start` 영수증의 `launch.requested`와 `launch.effective`를 대조하고 실제 워커 실행 증거를 함께 확인합니다(Orca 터미널 디스패치에서는 직접 CLI 전용 `stream-json` `init` 이벤트를 요구하지 않음).
2. **`worker_done` 생명주기 영수증 확인**:
   - 수신된 `worker_done` 메시지가 활성 `taskId` 및 `dispatchId`와 정확히 일치하는지, `outcome`이 `succeeded`인지, 요약 본문과 `--files-modified` 목록이 실제 변경분과 일치하는지 확인합니다.
3. **파일 변경 경계 및 Diff 검사**:
   - `git status --short`, `git diff --name-only`, `git diff`, `git diff --check`를 통해 `ALLOWED_CHANGES`에 지정된 경로만 수정되었고 공백/포맷 오류나 `FORBIDDEN` 위반이 없는지 검사합니다.
4. **독립 검증 및 워커 해제 확인**:
   - 명세에 정의된 검증 명령을 실행하고 수용 기준 충족 여부를 확인한 뒤, `worker-release --dispatch <dispatch_id> --json` 및 `check --ack <delivery_id> --json`을 수행하고 `worker-list --run <run_id> --terminal-state reclaimable --json`으로 미해제 회수 가능 워커가 남아 있지 않음을 확인합니다.

### B. 직접 `agy.exe` CLI 실행 검증 기준 (저수준 진단 폴백 전용)

직접 헤드리스 CLI 폴백을 실행한 경우에는 `stream-json` 초기화 및 결과 이벤트 검사가 적용됩니다.

1. **프로세스 종료 코드**: CLI 종료 코드(`$LASTEXITCODE`)가 `0`인지 확인합니다.
2. **`stream-json` 초기화 이벤트(`init`)**:
   - `init.model` 값이 정확히 `"gemini-4-argon-high"`인지 확인합니다.
   - `init.cwd` 값이 대상 작업 디렉터리(`C:\Users\felice\orca\aria-app`)와 일치하는지 확인합니다.
3. **`stream-json` 최종 결과 이벤트(`result`)**:
   - 최종 `status`가 `"SUCCESS"`인지 확인합니다.
4. **파일 변경 경계 및 독립 검증**: Diff 검사와 검증 명령 실행을 동일하게 수행합니다.

> 두 경로 모두에서 미비점이나 오류가 발견되면 Codex가 직접 코드를 수정하지 않고, 구체적인 수정 명세를 작성하여 `gemini-4-argon-high` 워커에 후속 수정 작업을 재위임합니다.

---

## 6. Android 변경 시 필수 검증 명령 및 작업 구분

- **연결·응답 프로브 및 문서/파일 조작 작업**: 호출 계약, 모델 고정(`gemini-4-argon-high`), 파일 편집 경계 준수 여부를 확인하기 위한 작업입니다. 문서 전용 작업에서는 불필요한 앱 빌드를 실행하지 않으며, 프로브만으로는 Android 앱의 빌드 무결성이 검증되지 않습니다.
- **실제 Android 기능·코드·빌드 설정 구현**: `app/` 하위의 Kotlin 코드, 리소스, 매니페스트, 테스트, 또는 Gradle 설정을 변경하는 실제 작업에서는 반드시 아래의 Android 빌드·단위 테스트·Lint 검증을 수행해야 합니다.

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

## 7. 검증된 위임 증거 및 상태 기록 (2026-10-20)

### A. 저수준 직접 CLI 프로브 기록 (Direct-CLI-Only, 비-Orca 감독 실행)

선행 커밋(`eadf38d`, 2026-10-20)에서 기록된 아래 두 프로브는 공식 Antigravity CLI(`%LOCALAPPDATA%\agy\bin\agy.exe`, 버전 `1.4.3`)를 직접 호출한 **저수준 직접 CLI 전용(direct-CLI-only) 테스트**이며, Orca 감독 `Run` / `Task` / `Dispatch` 작업이 아닙니다.

1. **CLI 모델 목록 조회 성공 (Direct CLI)**
   - 공식 실행 파일을 통해 `gemini-4-argon-low`, `gemini-4-argon-medium`, `gemini-4-argon-high` 모델 목록을 조회했습니다.
2. **연결성 프로브 (Direct-CLI Connectivity Probe)**
   - **Conversation ID**: `c81976f2-9d30-47ad-8eb6-b6761300d09e`
   - **관찰된 `init.model`**: `gemini-4-argon-high` / **최종 상태(`result.status`)**: `SUCCESS` / **응답 출력**: `AGY_ARGON_HANDOFF_OK`
3. **코드 작성 프로브 (Direct-CLI Temp-Workspace Code Probe)**
   - **Conversation ID**: `0d900ceb-c514-4bb9-8366-f445952ac4de`
   - **관찰된 `init.model`**: `gemini-4-argon-high` / **최종 상태(`result.status`)**: `SUCCESS`
   - **작업 범위 및 독립 검증**: 저장소 외부 일회성 임시 작업 공간에서 `delegation-probe.cjs`만 생성했으며, Codex가 독립적으로 `node`를 실행하여 종료 코드 `0` 및 `ARGON_CODE_PROBE_PASS` 출력을 확인했습니다. Aria 저장소 프로덕션 코드는 변경되지 않았습니다.

### B. Orca 감독 오케스트레이션 실행 기록 (2026-10-20)

- **Orca 런타임 버전**: `1.4.232` (`orchestration` 및 `orca-cli` 스킬 로드 확인)
- **Run ID**: `run_2e3831ec98fc`
- **Task ID**: `task_f93a965f977a` (작업 명세 ID: `ARIA-ORCA-ORCHESTRATION-001`)
- **Dispatch ID**: `ctx_7c7177c65852`
- **코디네이터 터미널 핸들**: `term_22f43597-99e6-436e-8812-7091bf6d0be8`
- **워커 터미널 핸들**: `term_0628b3da-36e0-41a7-ada8-f104fb3ae31c`
- **워커 기동 명령**: `orca orchestration worker-start --worktree current --agent antigravity --model gemini-4-argon-high`
- **최종 검증 결과**: 두 문서만 변경됐고 기존 빌드·테스트 지침이 보존됐으며 `git diff --check`가 통과했습니다. 코디네이터는 실행 영수증의 요청·적용 모델이 모두 `gemini-4-argon-high`임을 확인했고, Task `task_f93a965f977a` / Dispatch `ctx_7c7177c65852`의 공식 `worker_done` 보고(`outcome: succeeded`)를 수용했습니다. `worker-release`는 `released`와 터미널 기록 보관(`captured`)을 반환했고, 완료 Delivery `delivery_fac563304a0c` 확인 응답 후 미정리 `reclaimable` 워커 0개를 확인했습니다. 이번 문서 전용 작업에서는 앱 코드 변경이나 Android 빌드 재실행이 없었습니다.
