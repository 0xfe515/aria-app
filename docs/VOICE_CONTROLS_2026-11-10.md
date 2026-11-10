# Local voice controls

Aria handles the following app feedback controls offline through `FeedbackSettingsClient`, which is shared with the foreground voice service. The volume setting controls Aria's own audible feedback; it does not change Android media volume. Mutations announce the persisted value returned by the settings provider, and provider failures are reported without claiming success.

| Category | Example phrases |
| --- | --- |
| App feedback volume | “볼륨 50퍼센트”, “음량을 70으로”, “소리 크기 절반”, “볼륨 올려”, “음량 낮춰” |
| Warning frequency | “경고 빈도 낮춰”, “알림 좀 줄여”, “자주 알려줘”, “빈도 기본으로” |
| Caution alert | “주의 단계 꺼”, “위험할 때만 알려줘”, “주의 다시 켜”, “주의 알림 켜져 있어?” |
| Readback | “현재 알림 설정 알려줘”, “음량 얼마야?”, “경고 빈도 어떻게 돼?”, “현재 설정 설명해줘” |
| Navigation and app controls | “홈으로 돌아가”, “설정 화면 열어주세요”, “보행 상태 알려줘”, “정면 다시 보정해줘”, “명령어 뭐 있어?” |
| Photos | “앞에 뭐가 있는지 알려줘”, “표지판 글씨 읽어줘” |

Volume changes use ten percentage points for relative requests and clamp at 0–100. Arabic numerals and bounded Sino-Korean numbers such as 오십 and 칠십 are supported; native Korean number words such as 쉰 are not currently parsed. Omitted, malformed, signed, decimal, or out-of-range values are clarified locally. Warning frequency selects FREQUENT, NORMAL, or QUIET and scales same-severity repeat cooldowns by 0.5×, 1×, or 2×. The coordinator's duplicate-message guard uses the matching warning/caution base cooldowns, while a higher severity bypasses that guard. Caution controls toggle only the separate caution alert setting; WARNING and URGENT tiers remain independent. Existing “소리 켜/꺼” controls whether feedback is audible and remains separate from the app feedback volume level.

Independent requests combined in one utterance require clarification before mutation. Negative settings requests do not become positive commands. Photo requests, cancellation, stop, and ordinary conversational questions retain their separate routing behavior.
