package com.ariateam.aria.command

/** Lightweight, offline evidence extraction shared by local commands and cloud routing. */
object LocalIntentAnalysis {
    data class PhotoRequest(val target: String, val capturesImage: Boolean)

    private val politeEnding = "(?:해줘요?|해주세요|주세요|줘요?|십시오)?"
    private val commandPatterns = listOf(
        AppCommand.Home to listOf("홈(?:으로)?(?:가|이동|열어)$politeEnding", "홈화면열어$politeEnding"),
        AppCommand.Settings to listOf("설정(?:을|으로)?(?:좀)?(?:가|이동|열어|열어주세요|열어줘)$politeEnding", "설정화면열어$politeEnding"),
        AppCommand.Status to listOf("(?:현재|지금)?상태(?:를)?(?:좀)?(?:알려|확인)(?:해)?$politeEnding", "상태$politeEnding"),
        AppCommand.WalkingStatus to listOf("(?:보행|걷기)(?:상태)?(?:를)?(?:좀)?(?:알려|확인)(?:해)?$politeEnding"),
        AppCommand.ForwardCalibration to listOf("(?:정면|앞방향|방향)보정(?:을)?(?:해)?$politeEnding"),
        AppCommand.Help to listOf("(?:도움말|사용법)(?:을)?(?:좀)?(?:알려|설명)?$politeEnding", "뭐할수있어$politeEnding"),
        AppCommand.HideScreen to listOf("화면(?:을)?(?:좀)?(?:숨겨|꺼)$politeEnding"),
        AppCommand.ShowScreen to listOf("화면(?:을)?(?:좀)?(?:보여줘|보여주세요|보여주|켜)$politeEnding"),
        AppCommand.SoundOn to listOf("(?:안내|효과)?소리(?:를)?(?:좀)?켜$politeEnding", "효과음켜$politeEnding", "피드백켜$politeEnding"),
        AppCommand.SoundOff to listOf("(?:안내|효과)?소리(?:를)?(?:좀)?(?:꺼|끄)$politeEnding", "효과음(?:꺼|끄)$politeEnding", "피드백(?:꺼|끄)$politeEnding", "조용히(?:해)?$politeEnding"),
    ).map { (command, patterns) -> command to patterns.map { Regex("^$it$") } }

    private val executableActionPatterns = listOf(
        Regex("설정(?:을|으로)?(?:좀)?(?:열어|열어주세요|열어줘|열고|열)"),
        Regex("(?:홈|홈화면)(?:으로)?(?:가|이동|열어)"),
        Regex("(?:안내|효과)?소리(?:를)?(?:좀)?(?:켜|꺼|끄)"),
        Regex("효과음(?:켜|꺼|끄)"),
        Regex("화면(?:을)?(?:좀)?(?:숨겨|숨기|보여줘|보여주|켜|꺼)"),
        Regex("(?:현재|지금)?상태(?:를)?(?:좀)?(?:알려|확인)"),
        Regex("(?:보행|걷기)(?:상태)?(?:를)?(?:좀)?(?:알려|확인)"),
        Regex("(?:정면|앞방향|방향)보정(?:을)?(?:해)?"),
    )
    private val negationPatterns = listOf("하지마", "하지말", "하지않", "안찍", "찍지", "안읽", "읽지마", "읽지말", "안보내", "보내지마", "보내지말")
    private val quotedCommandFrames = listOf("라는말", "라는표현", "라는문장", "라는단어", "라는뜻", "의미", "라고하면")
    private val captureQuestionPattern = Regex(
        "^\\s*(?:사진\\s*)?(?:찍어서|찍고|찍어(?:줘|주세요|줘요)?|촬영해서|촬영하고|촬영(?:해줘|해주세요|해줘요)?)\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val cameraQuestionPattern = Regex("^카메라로(?:봐줘|봐|뭐보여)$")
    private val captureActionPattern = Regex("(?:사진|카메라)(?:을|를)?(?:좀)?(?:찍어서|찍고|찍어(?:줘|주세요)?|촬영해서|촬영하고|촬영(?:해줘|해주세요)?)")
    private val leadingProviderPattern = Regex("^\\s*(?:챗지피티|지피티|chatgpt|챗gpt|gpt|클라우드)(?:에게|한테|야|아|로|(?=[,:]))[\\s,:]*", RegexOption.IGNORE_CASE)
    private val leadingPhotoProviderPattern = Regex(
        "^\\s*(?:챗지피티|지피티|chatgpt|챗gpt|gpt|클라우드)(?:(?:에게|한테|야|아|로)[\\s,:]*|[,，:][\\s]*|\\s+(?=사진\\s*(?:찍|촬영)|카메라로))",
        RegexOption.IGNORE_CASE,
    )
    private val leadingQuestionWrapperPattern = Regex("^\\s*(?:질문있어|질문할게|질문해줘|질문해|물어봐줘|물어봐|질문)[\\s,:]*", RegexOption.IGNORE_CASE)

    fun command(text: String): AppCommand? {
        val value = RuleCommandInterpreter.normalize(text).replaceFirst(
            Regex("^(홈|설정|상태|보행상태|걷기상태|정면보정|화면|안내소리|효과음|소리)좀"),
            "$1",
        )
        val matches = commandPatterns.filter { (_, patterns) -> patterns.any { it.matches(value) } }.map { it.first }.distinct()
        return matches.singleOrNull()
    }

    fun photoRequest(text: String): PhotoRequest? {
        val value = RuleCommandInterpreter.normalize(text)
        val capture = captureQuestion(text) != null
        val hasVisualReferent = listOf(
            "앞에", "앞쪽", "전방", "눈앞", "주변", "글씨", "글자", "텍스트", "문구", "표지판", "간판",
            "이거", "이게", "사진속", "카메라앞", "화면속",
        ).any(value::contains)
        val hasQuestionShape = listOf("알려", "설명", "뭐야", "뭔지", "읽어", "보여", "어떤", "무엇").any(value::contains)
        if (!capture && !(hasVisualReferent && hasQuestionShape)) return null
        return PhotoRequest(
            target = if (listOf("글씨", "글자", "텍스트", "문구", "표지판", "간판", "읽어").any(value::contains)) "text" else "scene",
            capturesImage = capture,
        )
    }

    /** Returns only the user's question after an explicit capture verb, retaining its wording and punctuation. */
    fun captureQuestion(text: String): String? {
        if (cameraQuestionPattern.matches(RuleCommandInterpreter.normalize(text))) return ""
        val match = captureQuestionPattern.find(text.trim()) ?: return null
        return match.groupValues[1]
            .replaceFirst(leadingProviderPattern, "")
            .replaceFirst(leadingQuestionWrapperPattern, "")
            .trim()
    }

    fun stripLeadingPhotoProvider(text: String): String = text.replaceFirst(leadingPhotoProviderPattern, "")

    /** True only when independent executable local/photo actions occur in one utterance. */
    fun hasCompoundCommandConflict(text: String): Boolean {
        val value = RuleCommandInterpreter.normalize(text)
        if (negationPatterns.any(value::contains) || quotedCommandFrames.any(value::contains)) return false
        val spans = executableActionPatterns.flatMap { pattern ->
            pattern.findAll(value).map { it.range }.toList()
        }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (span in spans) {
            val previous = merged.lastOrNull()
            if (previous != null && span.first <= previous.last) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, span.last)
            } else {
                merged += span
            }
        }
        val photoEvidence = photoRequest(text)
        val hasCapture = photoEvidence?.capturesImage == true || captureActionPattern.containsMatchIn(value)
        if (hasCapture && (value.contains("그리고") || value.contains("다음에"))) return true
        if (merged.size > 1 || (hasCapture && merged.isNotEmpty())) return true

        // A visual question followed by an executable local command is also independent work.
        val connector = Regex("(?:하고|그리고|다음에|열고|켜고|꺼고|찍고|촬영하고)")
        return photoEvidence != null && merged.any { action ->
            connector.find(value)?.range?.last?.let { it < action.first } == true
        }
    }
}
