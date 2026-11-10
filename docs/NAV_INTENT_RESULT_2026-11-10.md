# Navigation intent review — 2026-11-10

Addressed independent review findings in `NavigationCommandParser`: destination extraction removes the trailing connective in forms such as `충주시청 가고 길안내 해줘`, while `가고 + sound/settings/home/etc.` is classified as a compound and clarified before navigation. Expanded spaced request variants including `충주시청 길 안내 해 줘` and `길 안내 해 주세요 충주시청`. Restored the previously supported road ending set (including 퇴계로, 백범로, 동호로, 다산로) alongside current examples; cleanup remains limited to phrase boundaries to avoid deleting action-like substrings inside names.

Updated only release metadata to versionCode 15 and versionName `1.0.0-preview.20261110.1`.

Validation: `git diff --check` passed. Gradle build/test/lint/androidTest compilation are coordinator-owned and were not run here; no device validation was performed.
