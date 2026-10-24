# Orca 병렬 위임 워크플로 (Gemini · Copilot · Luna)

정책 갱신 기준일: **2026-10-22** (기존 Gemini 검증 기록은 2026-10-20 기준)

이 문서는 `aria-app` 저장소에서 **Codex(`Codex/gpt-sol`)** 코디네이터와 **Gemini 3.8 Flash High(`gemini-3.8-flash-high`)** 기본 Gemini 워커 및 **Copilot** 구현 워커의 역할 분리, **Gemini 할당량 소진 시 GPT-6 Luna(`gpt-6-luna`) 예외**, **Orca 감독 오케스트레이션**, 그리고 **직접 Antigravity CLI 헤드리스 호출(저수준 진단 폴백)** 절차를 정의합니다.

- 저장소 기본 지침: [AGENTS.md](../AGENTS.md)
- 공식 Antigravity CLI 헤드리스 문서: <https://www.antigravity.google/docs/cli/headless/>

---

## 1. 역할 분리 및 기본 조율(Orchestration) 원칙

### GPT 계정 한도 소진 시 자동 전환

사용자는 이미 연결된 GPT/Codex 계정 사이의 자동 전환을 승인했습니다. 현재 계정의 실제 공급자 한도 오류 또는 계정별 사용량으로 사용 가능한 한도 소진이 확인되면, Orca가 제공하는 계정 목록에서 시스템 기본 계정과 관리 계정을 확인하고 여유가 있는 연결 계정으로 전환합니다. 재확인은 필요하지 않습니다. 설치된 런타임이 지원하는 계정·세션 선택 기능만 사용하고, 전환 전후 작업 문맥과 변경분을 보존하며 실제 전환 결과를 확인합니다.

계정 전환은 기존 모델·effort 및 구현 역할 제한을 바꾸지 않습니다. 구현 작업을 새 계정에 넘길 때는 기존 시도를 Orca 복구 절차에 따라 정리하거나 차단한 후 파일 소유권을 이전하고, 동시 편집을 방지합니다. 소진 근거와 전환 사실을 작업 결과에 기록하되 자격 증명이나 계정 식별자를 저장소에 기록하지 않습니다. 무응답·시간 초과·인증 실패·일반적인 일시적 속도 제한·다른 모델의 집계 사용량은 소진 근거가 아닙니다.

자격 증명 복사, 유료 사용량 구매, 한도 초기화 크레딧 소비, 지원되지 않는 CLI 플래그 추측은 허용하지 않습니다. 현재 CLI의 `orca account select --help`는 OpenCode/Devin만 지원하므로 Codex 선택 명령으로 오용하지 않습니다. Codex 계정 전환에는 해당 런타임의 지원되는 계정·세션 선택 기능을 사용하고, 자동 선택 경로가 없으면 구체적인 제한을 보고하고 작업을 보존합니다.

### UI 검증 산출물

사용자가 기존 UI 작업 후 스크린샷 요구를 철회했습니다. 스크린샷 제출은 필수가 아니며, 별도 요청이 없으면 빌드·단위 테스트·Lint·관련 기기 UI 테스트와 설치 확인으로 검증 결과를 보고합니다.

1. **기본 감독 워크플로 — Orca Orchestration Skill 필수 사용**
   - 감독이 필요한 모든 구현 위임은 원시(raw) `agy.exe` 프로세스 직접 실행이나 비-Orca 서브에이전트로 대체하지 않고, 반드시 버전이 일치하는 **Orca `orchestration` 스킬**(`orca skills get orchestration --json`)과 실제 Orca 런타임의 `Run` / `Task` / `Dispatch` 상태를 기본 조율 계층으로 사용합니다.
   - **Codex(`Codex/gpt-sol`)**가 코디네이터(Coordinator) 역할을 수행하며, `orca orchestration worker-start --spec $taskSpec --worktree current --agent antigravity --model gemini-3.8-flash-high --json`으로 **Gemini 3.8 Flash High** 구현 워커를 선택 및 기동합니다.
   - 감독 생명주기(`Run`/`Task`/`Dispatch`)가 필요 없는 단순 비감독 핸드오프(unsupervised handoff)나 터미널 전용 제어는 공식 스킬 지침에 따라 `orca-cli`(`orca skills get orca-cli --json`) 영역에 속합니다.
   - 공식 실행 파일(`%LOCALAPPDATA%\agy\bin\agy.exe`) 직접 호출은 기본 감독 워크플로가 아니며, 오직 **저수준 진단 폴백(diagnostic fallback)** 용도로만 유지합니다.
2. **Codex (`Codex/gpt-sol`) — 코디네이터·계획·명세·독립 검증·Git 통합**
   - 요구사항 분석, 작업 계획 수립, 정밀한 작업 명세(Task Specification) 작성, 변경분(`git diff`, `git diff --check`) 검토, 독립적인 빌드·테스트 실행, 최종 검증 및 Git 통합을 담당합니다.
   - 문서(Documentation) 및 작업 명세서는 Codex가 직접 작성하거나, 명시적 작업 명세 하에 Gemini 또는 Copilot 워커에게 위임할 수 있습니다.
   - 구현 위임 전에 반드시 관련 저장소 지침(`AGENTS.md`, 관련 `docs/*.md`)과 Orca `orchestration` 스킬을 먼저 읽고 명세에 반영합니다.
3. **Gemini 3.8 Flash High 및 Copilot — 구현 워커**
   - 모든 프로덕션 소스 코드, 단위/기기 테스트, 빌드 스크립트/설정 코드, 자동화 코드는 **Gemini 3.8 Flash High 또는 Orca Copilot** 워커가 작성하거나 수정합니다. 아래 할당량 소진 예외가 성립할 때만 별도의 **GPT-6 Luna** 워커가 이 역할을 대신합니다. 코디네이터는 구현을 대신하지 않습니다.
   - 별도의 명시적 지시가 없는 한 워커는 `git commit` 또는 `git push`를 실행하지 않습니다.
4. **모델 고정(Pinning) 및 장애 처리 원칙**
   - Gemini 구현 작업에는 반드시 `--agent antigravity --model gemini-3.8-flash-high`를 명시합니다. 할당량 소진 예외의 워커는 `--agent codex --model gpt-6-luna --effort medium`으로 고정합니다.
   - 다른 모델로 임의 대체(silent substitution)하는 것은 금지됩니다.
   - 실제 공급자 오류 또는 명시적 할당량 상태로 Gemini의 사용 가능한 할당량 소진이 확인된 경우에만 GPT-6 Luna로 전환합니다. 오류/상태 근거와 전환 사실을 명세 및 결과에 기록하고 알립니다. 시간 초과, 무응답, 일반적인 일시적 속도 제한, 인증/모델/Orca 런타임/CLI 오류를 할당량 소진으로 간주하지 않습니다. 그 밖의 장애 또는 Luna 사용 불가 시에는 해당 시도의 차단 요인을 보고하고 중단하며 다른 모델을 임의로 고르지 않습니다. 독립적인 다른 승인 작업은 계속할 수 있습니다. Copilot은 평상시 승인된 워커이며 Gemini 할당량 소진 없이도 작업을 배정할 수 있습니다.
   - 기존 Gemini 시도의 부분 변경을 보존하고 오케스트레이션 스킬의 복구 절차로 해당 시도를 정리한 뒤에만 Luna를 같은 파일에 투입합니다. 살아 있거나 생존 여부가 불확실한 Gemini 시도와 동시에 편집하지 않습니다. Gemini 할당량 복구가 확인되면 새 구현 작업부터 Gemini를 다시 기본으로 선택합니다.
   - 사용자가 명시적으로 요청한 Luna 능력 검증은 Gemini 할당량을 소진시키지 않고도 허용됩니다. 검증 실행 자체는 프로덕션 모델 전환의 근거가 아닙니다.
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

# 4. 작은따옴표 Here-String으로 정밀 작업 명세($taskSpec) 정의 후 Gemini 3.8 Flash High 워커 기동
$taskSpec = @'
TASK_ID: ARIA-<DOMAIN>-<NNN>
... (9개 필수 항목 및 Orca 정렬 명세 본문) ...
'@

orca orchestration worker-start --spec $taskSpec --worktree current --agent antigravity --model gemini-3.8-flash-high --json

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

### Copilot 배정 및 독립 구현 병렬 실행

- Copilot은 Gemini와 함께 평상시 구현·검토에 사용할 수 있습니다. Gemini 할당량 소진을 기다릴 필요가 없습니다. 코디네이터는 두 개 이상의 독립 구현 영역을 한 워커에게 몰아서 주지 않고, 각각의 Task로 분리하여 여러 워커를 먼저 기동한 뒤 완료를 기다립니다.
- 기본 Gemini 모델은 `gemini-3.8-flash-high`입니다. Copilot은 Orca에 활성화된 에이전트 ID(`copilot`, 사용자 정의 ID이면 실제 ID 확인)로 실행하며 구성된 모델을 상속합니다. 현재 `worker-start --help`에서 Copilot의 모델/effort override 지원을 확인하지 못했으므로 해당 플래그를 임의로 추가하지 않습니다. 설치된 버전의 스킬·도움말에서 지원이 확인된 경우에만 명시적 옵션을 사용합니다.
- 요청/실효 에이전트를 런치 영수증과 실제 실행 증거로 검증합니다. Copilot의 모델이 영수증에 없으면 확인 가능한 런타임 증거를 기록하고, 끝내 확인할 수 없으면 모델 미확인으로 보고합니다. 특정 모델 고정이 필수인 Task는 그 모델을 증명할 수 없는 실행으로 수용하지 않습니다. 에이전트 등록만으로 연결·구현·생명주기 동작이 검증됐다고 주장하지 않습니다.
- Gemini와 Copilot의 역할은 작업 소유권과 의존성에 따라 정합니다. 예를 들어 센서 로직과 독립 UI, 또는 서로 다른 모듈은 별도 워커에 배정합니다. 하나의 응집된 수정, 실제 선행 의존성, 파일 충돌, 사용 가능한 슬롯 부족으로 직렬화할 때에는 명세에 이유를 기록합니다.

```powershell
# 각 변수는 3절의 9개 항목을 모두 갖춘 별도 명세입니다.
# 동일 checkout에서는 두 명세의 ALLOWED_CHANGES가 겹치지 않아야 합니다.
# 기존/bound Run을 확인하고, 새 작업이면 Run을 만든 뒤 실행합니다.
orca orchestration worker-start --spec $geminiTaskSpec --worktree current --agent antigravity --model gemini-3.8-flash-high --json
orca orchestration worker-start --spec $copilotTaskSpec --worktree current --agent copilot --json
# 두 기동 모두의 영수증을 확인한 후 감독 루프에서 각 Task의 완료를 처리합니다.
orca orchestration check --wait --types 'worker_done,escalation,question' --timeout-ms 30000 --json
```

**병렬 수정 경계와 검증 소유권**:

1. 각 명세에 배타적인 파일 목록, 다른 워커의 소유 경로(`PEERS`), 공유 인터페이스 계약, 실제 의존성(`DEPENDENCIES`), 검증 명령의 실행 주체를 명시합니다. 다른 워커의 파일을 수정하지 않습니다.
2. 동일 파일을 고쳐야 하면 인터페이스/공유 파일 작업을 먼저 끝내거나 Orca 관리 하위 worktree로 분리합니다. 새 worktree는 기존 미커밋·미추적 파일을 자동 상속하지 않으므로 기준 상태를 확인하고 사용자 변경을 보존합니다. 서로의 변경을 덮어쓰지 않습니다.
3. 통합 및 충돌 해결은 선행 Task가 정리된 후 별도 구현 Task에 배정합니다. 코디네이터는 검토·Git 통합을 담당하고 구현 수정을 대신하지 않습니다. 성공한 개별 워커만으로 통합 결과를 통과 처리하지 않습니다.
4. 공유 checkout의 Gradle/build 산출물, 같은 Pixel 3의 설치·계측 테스트·성능 측정에는 한 번에 한 명의 실행 소유자만 둡니다. 병렬 편집 중 불안정한 파일 상태로 통합 빌드하거나, 다른 워커가 기기를 사용하는 동안 벤치마크하지 않습니다. 독립 worktree 빌드는 자원 범위가 겹치지 않을 때만 병렬화합니다.
5. 각 워커의 `worker_done`을 해당 Task/Dispatch와 대조하고 개별 해제/재사용 결정을 내립니다. 최종 통합본의 빌드·단위 테스트·Lint 및 필요한 기기 검증은 코디네이터가 독립 확인합니다. 실행 실패 시 해당 시도는 복구 절차로 정리하며, 활성/생존 미확인 워커의 파일을 다른 워커에게 넘기지 않습니다.

### Gemini 할당량 소진 시 Luna 디스패치

별도 자동 전환 프로그램을 설치하는 규칙이 아니라 코디네이터의 작업자 선택 정책입니다. Gemini를 기본으로 유지하고, 위의 소진 근거가 있을 때 다음 경로를 사용합니다.

```powershell
# 아직 Dispatch가 없는 작업: 9개 필수 항목과 할당량 근거를 포함한 명세
orca orchestration worker-start --spec $taskSpec --worktree current --agent codex --model gpt-6-luna --effort medium --json

# 기존 시도가 복구 절차에 따라 failed/stopped로 확인된 같은 Task 재시도
orca orchestration worker-start --task <task_id> --retry-of <dispatch_id> --worktree current --agent codex --model gpt-6-luna --effort medium --json
```

`<task_id>`와 `<dispatch_id>`는 실제 영수증에서 얻은 값으로 바꿉니다. 복구 절차가 재시도를 허용하지 않으면 새 Task로 우회하지 않습니다. 런치 영수증의 `launch.requested`와 `launch.effective` 모두 `gpt-6-luna` 및 `medium`인지 확인합니다. 동일한 파일 소유권, 하트비트, 질문/응답, 완료 보고, 독립 검증과 Android 검증 기준을 적용합니다. 완료 메시지의 성공 주장만으로 수용하지 않습니다.

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
ROLE/OWNERSHIP: You are the explicitly selected implementation worker: Gemini 3.8 Flash High or Orca Copilot, or GPT-6 Luna only under the documented quota fallback. Record the selected agent, requested model/configuration and observable effective model; do not invent an exact Copilot model when using its configured default. Record quota evidence when invoking the Luna exception. Codex is the coordinator responsible for planning, precise specifications, independent diff/validation review and Git integration; the coordinator does not implement. Follow your live Orca injected Task/Dispatch preamble exactly for lifecycle communication, heartbeats, inbox checks and worker_done.
READ_FIRST: AGENTS.md, docs/ANTIGRAVITY_DELEGATION.md, <추가 필독 파일>
CONTEXT/EVIDENCE:
- <현재 코드/런타임 상태, 선행 검증 증거, 관련 Run/문맥 정보>
PEERS/DEPENDENCIES:
- <다른 Task의 소유 경로, 공유 인터페이스, 실제 선행 조건 또는 독립 실행 근거>
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
- <공유 checkout 빌드 및 기기 사용의 단독 소유자, 통합본 검증 담당자>
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
- **현재 Gemini 작업 모델**: `gemini-3.8-flash-high`. 과거 Argon 연결 검증은 7절의 역사적 기록이며 현재 선택 정책이 아닙니다.

### 직접 CLI 헤드리스 호출 예시 (진단 폴백 전용)

```powershell
$agy = Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe'

$taskSpec = @'
TASK_ID: ARIA-DIAG-001
... (9개 필수 항목을 포함한 진단 작업 명세 본문) ...
'@

& $agy `
  --model gemini-3.8-flash-high `
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
   - `init.model` 값이 정확히 `"gemini-3.8-flash-high"`인지 확인합니다.
   - `init.cwd` 값이 대상 작업 디렉터리(`C:\Users\felice\orca\aria-app`)와 일치하는지 확인합니다.
3. **`stream-json` 최종 결과 이벤트(`result`)**:
   - 최종 `status`가 `"SUCCESS"`인지 확인합니다.
4. **파일 변경 경계 및 독립 검증**: Diff 검사와 검증 명령 실행을 동일하게 수행합니다.

> 미비점이나 오류가 발견되면 코디네이터가 직접 코드를 수정하지 않고 구체적인 수정 명세로 재위임합니다. Gemini 또는 Copilot에 배타적인 작업 소유권에 따라 배정하며, 문서화된 할당량 소진 예외가 적용되는 동안은 `gpt-6-luna`를 고정한 별도 워커가 후속 수정을 담당합니다. 직접 `agy.exe` 진단은 Gemini 전용이며 Luna 폴백은 Orca 감독 경로를 사용합니다.

---

## 6. Android 변경 시 필수 검증 명령 및 작업 구분

- **연결·응답 프로브 및 문서/파일 조작 작업**: 호출 계약, 선택한 에이전트/모델 설정(Gemini `gemini-3.8-flash-high`, Copilot의 구성된 기본 모델, 할당량 소진 예외 또는 명시적 능력 검증의 `gpt-6-luna`), 파일 편집 경계 준수 여부를 확인하기 위한 작업입니다. 문서 전용 작업에서는 불필요한 앱 빌드를 실행하지 않으며, 프로브만으로는 Android 앱의 빌드 무결성이 검증되지 않습니다.
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

### C. GPT-6 Luna 명시적 능력 검증 (2026-10-21)

- **성격**: 사용자가 요청한 별도 검증이며, Gemini 할당량을 소진시키거나 실제 소진을 확인한 실행은 아닙니다. 따라서 이 검증 기록만으로 평상시 Gemini 기본 선택을 바꾸지 않습니다.
- **환경 및 모델**: Orca `1.4.232`, Codex CLI `0.163.0`, ChatGPT 로그인. `launch.requested`와 `launch.effective` 모두 `agent=codex`, `model=gpt-6-luna`, `effort=medium`이었고 작업자 UI에서도 모델을 확인했습니다.
- **Run / Task / Dispatch**: `run_7255e42a9ae6` / `task_d43c6a6cc0b1` / `ctx_de2389f50cde`. 명세 ID는 `ARIA-LUNA-VERIFY-001`, 작업자 핸들은 `term_dad2a7cf-51bc-4cad-98ab-ab36b08d736d`였습니다. 이 식별자는 과거 기록이며 새 작업에서 재사용하지 않습니다.
- **기동 관찰**: 최초 시작은 `turn_start_unobserved` / `outcome_unknown`이었습니다. `worker-read`에서 명세가 실제 미제출 draft로 남아 있음을 확인한 후 기존 터미널에 Enter만 보내 제출했고, 명세를 중복 전송하거나 새 작업자를 띄우지 않았습니다. 이어서 실제 질문/응답, 도구 실행 및 완료 신호를 확인했습니다. 향후 같은 상태에서는 타임아웃만으로 재시도하지 말고 복구 스킬과 실제 draft 증거를 확인합니다.
- **구현 범위**: 저장소 외부 `C:/Users/felice/AppData/Local/Temp/aria-luna-verification-20261021`의 `probe.py`, `test_probe.py`, `result.json` 및 Python 캐시만 작업자 생성 대상으로 허용했습니다. CSV 정수 파서와 요약 함수는 순서·중복·부호·빈 입력·잘못된 토큰 처리를 구현했습니다.
- **도구 및 생명주기**: 실제 셸로 파일 읽기/쓰기, Python 실행, PowerShell 독립 계산 및 보고서 재읽기를 수행했습니다. 자기 핸들로 `ask`를 실행해 질문 `msg_136459d48b55`의 응답 `msg_256f012c114d`를 수신했으며, 코디네이터 제공 단어 `ORCHID-682`를 정확히 보고서에 기록했습니다. 완료 직전 인박스 확인도 관찰했습니다.
- **작업자 및 코디네이터 검증**: 양쪽에서 `python -m unittest discover -s C:/Users/felice/AppData/Local/Temp/aria-luna-verification-20261021 -p test_probe.py -v`의 8개 테스트와 `python -m py_compile C:/Users/felice/AppData/Local/Temp/aria-luna-verification-20261021/probe.py`가 통과했습니다. 코디네이터는 별도로 부호/선행 0, 잘못된 토큰 12종, 음수 요약, 1,000개 입력 및 JSON 일치 검사도 통과시켰습니다. fixture의 실제 결과는 합계 `422`, 최솟값 `-16`, 최댓값 `214`, 최솟값×최댓값 `-3424`였습니다.
- **완료 및 해제**: 정확한 Task/Dispatch와 `outcome=succeeded`인 `worker_done` `msg_ea45d334ef09`를 수신하고 실제 파일·테스트 결과·검증 단어를 독립 대조했습니다. `worker-release`의 `released` 및 transcript `captured` 확인 후 `delivery_2dd5321b1067`를 ack했으며, 해당 Run의 `reclaimable` 작업자 0개를 확인했습니다.
- **수용 범위**: 제한된 코드 작성·검증·Orca 질문/완료 보고 능력은 통과했습니다. Android 구현 품질, 장시간 작업의 하트비트 주기, 실제 Gemini 할당량 소진 상황의 전환, 작업당 구독 할당량 소모를 입증하는 시험은 아닙니다. 앱 소스는 이 검증의 수정 대상이 아니었으므로 Android 빌드·기기 테스트를 실행하지 않았으며, 향후 실제 앱 변경에는 기존 필수 검증을 그대로 적용합니다.
