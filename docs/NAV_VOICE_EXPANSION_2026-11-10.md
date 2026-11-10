# Navigation transport mode regression review

Review found that substring-based checks confused mode words with destination names and that a generic `가` match could classify information requests. The guard recognizes explicit unsupported transport grammar, including `자동차로`, `자동차를 타고`, `버스로`, and `대중교통으로`, and runs before pending navigation context resolution. It only clarifies when navigation is requested or a destination followup is active; the wording asks the user to repeat the destination with walking guidance instead of implying a yes/no followup is supported.

| Utterance | Expected result |
| --- | --- |
| 버스터미널까지 안내해줘 | Walking route to `버스터미널` |
| 자동차박물관 안내해줘 | Walking route to `자동차박물관` |
| 택시승강장 길안내 | Walking route to `택시승강장` |
| 버스가 뭐야 | No navigation interpretation |
| 버스로 충주시청까지 안내해줘 | Clarify that walking is supported; do not silently route walking |
| 대중교통으로 충주시청까지 안내해줘 | Clarify that walking is supported |
| 자동차를 타고 충주시청 가줘 | Clarify that walking is supported |
| 버스로 안내해줘 with pending `충주시청` | Clarify that walking is supported; do not resolve as walking |

Earlier natural request, road-name, provider, negation and cancellation coverage remains in the parser-focused tests. Worker validation is `git diff --check`; the coordinator owns Gradle validation. No build or device checks were run here.
