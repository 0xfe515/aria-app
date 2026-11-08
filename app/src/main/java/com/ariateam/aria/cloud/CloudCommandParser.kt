package com.ariateam.aria.cloud

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import com.ariateam.aria.command.RuleCommandInterpreter

object CloudCommandParser {

    private const val UNSUPPORTED_PROVIDER_CLARIFY =
        "현재는 공식 ChatGPT 로그인만 지원해요. ChatGPT로 질문해 주세요."

    fun parse(text: String, context: CommandContext, now: Long): Interpretation? {
        val normalized = RuleCommandInterpreter.normalize(text)
        if (normalized.isEmpty()) return null

        // 1. Negation handling: negative intent with question, photo, or provider keywords
        val negationKeywords = listOf(
            "하지마", "하지말", "찍지마", "찍지말", "묻지마", "묻지말", "물어보지마", "보내지마", "보내지말",
            "보내면안돼", "전송하지마", "올리지마", "보지마", "보지말", "읽지마", "읽지말",
            "읽어주지마", "읽어주지말", "촬영하지마", "촬영하지말", "찍으면안돼", "촬영하면안돼",
        )
        if (negationKeywords.any(normalized::contains)) {
            val targets = listOf(
                "질문", "사진", "챗지피티", "chatgpt", "제미나이", "클로드", "클라우드", "카메라", "물어", "묻",
                "앞에", "앞쪽", "전방", "눈앞", "주변", "이거", "이게", "글자", "글씨", "텍스트", "문구",
                "표지판", "간판", "읽어", "사진", "카메라",
            )
            if (targets.any(normalized::contains)) {
                return Interpretation.Clarify("원하는 명령을 하나만 다시 말해 주세요.")
            }
        }

        // 2. Compound commands
        if (listOf("그리고", "다음에").any(normalized::contains)) {
            val targets = listOf(
                "질문", "사진찍", "챗지피티", "chatgpt", "제미나이", "클로드", "물어봐",
                "앞에", "앞쪽", "전방", "눈앞", "주변", "카메라", "이거", "글자", "표지판", "간판",
            )
            if (targets.any(normalized::contains)) {
                return Interpretation.Clarify("명령을 하나씩 말해 주세요.")
            }
        }

        // 3. Explicit requests targeting unsupported providers (Gemini, Claude) including photo receiver phrases
        val unsupportedTargetResult = checkUnsupportedProviderTargeting(text, normalized)
        if (unsupportedTargetResult != null) {
            return unsupportedTargetResult
        }

        // 4. Photo question parsing
        val photoResult = parsePhotoQuestion(text, normalized)
        if (photoResult != null) return photoResult

        // 5. Text question parsing (with ChatGPT aliases)
        val textResult = parseTextQuestion(text, normalized, context, now)
        if (textResult != null) return textResult

        // Unknown local speech: do NOT upload to cloud
        return null
    }

    /**
     * Identifies requests that explicitly target unsupported providers (Gemini, Claude) as the assistant/handler.
     * Includes text asking directives and photo receiver phrases (e.g. "Gemini에게 사진 보내줘").
     * Ordinary questions merely mentioning them (e.g. "제미나이가 뭐야?", "클로드 최신 모델 설명해줘") are preserved as questions.
     */
    private fun checkUnsupportedProviderTargeting(rawText: String, normalized: String): Interpretation? {
        val trimmed = rawText.trim()

        // Standalone provider invocations without query
        if (normalized in setOf(
                "제미나이", "제미나이에게", "제미나이한테", "gemini",
                "클로드", "클로드에게", "클로드한테", "claude",
            )
        ) {
            return Interpretation.Clarify(UNSUPPORTED_PROVIDER_CLARIFY)
        }

        // Explicit targeting prefixes with question/ask/photo directives
        val targetingPrefixes = listOf(
            // Text query directives
            "제미나이로질문", "제미나이로물어봐", "제미나이에게물어봐", "제미나이한테물어봐",
            "제미나이에게질문", "제미나이한테질문", "제미나이질문해줘", "제미나이질문해", "제미나이질문",
            "gemini에게물어봐", "gemini한테물어봐", "gemini로질문", "gemini질문",
            "클로드로질문", "클로드로물어봐", "클로드에게물어봐", "클로드한테물어봐",
            "클로드에게질문", "클로드한테질문", "클로드질문해줘", "클로드질문해", "클로드질문",
            "claude에게물어봐", "claude한테물어봐", "claude로질문", "claude질문",
            // Photo receiver directives
            "제미나이에게사진", "제미나이한테사진", "제미나이로사진", "제미나이사진찍어",
            "gemini에게사진", "gemini한테사진", "gemini로사진",
            "클로드에게사진", "클로드한테사진", "클로드로사진", "클로드사진찍어",
            "claude에게사진", "claude한테사진", "claude로사진",
        )

        for (prefix in targetingPrefixes) {
            if (normalized.startsWith(prefix)) {
                return Interpretation.Clarify(UNSUPPORTED_PROVIDER_CLARIFY)
            }
        }

        // Utterance starting with provider receiver + vocative/delimiter directing action
        val lower = trimmed.lowercase()
        if (lower.startsWith("제미나이") || lower.startsWith("gemini") ||
            lower.startsWith("클로드") || lower.startsWith("claude")
        ) {
            val stripped = trimmed.replaceFirst(Regex("^(제미나이|gemini|클로드|claude)(?:에게|한테|로)?[\\s,:]*", RegexOption.IGNORE_CASE), "").trim()
            val strippedNorm = RuleCommandInterpreter.normalize(stripped)
            if (strippedNorm.startsWith("물어봐") || strippedNorm.startsWith("질문") ||
                strippedNorm.startsWith("사진찍") || strippedNorm.startsWith("사진보내") || strippedNorm.startsWith("사진")
            ) {
                return Interpretation.Clarify(UNSUPPORTED_PROVIDER_CLARIFY)
            }
        }

        return null
    }

    private fun parsePhotoQuestion(rawText: String, normalized: String): Interpretation? {
        val effectiveNormalized = normalized.replaceFirst(Regex("^(챗지피티|chatgpt|챗gpt|클라우드)(에게|한테|야|아|로)?"), "")
        val effectiveRaw = rawText.trim().replaceFirst(
            Regex("^(챗지피티|chatgpt|챗gpt|클라우드)(?:에게|한테|야|아|로)?[\\s,:]*", RegexOption.IGNORE_CASE),
            "",
        )
        val effectiveQuestionRaw = effectiveRaw.replaceFirst(
            Regex("^(?:질문있어|질문할게|질문해줘|질문해|물어봐줘|물어봐|질문)[\\s,:]*", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        val questionRaw = effectiveQuestionRaw
        val questionNorm = RuleCommandInterpreter.normalize(questionRaw)

        // Explicit photo capture trigger phrases
        val photoTriggers = listOf(
            "사진찍어서", "사진찍고", "사진찍어줘", "사진찍어",
            "사진촬영해서", "사진촬영하고", "사진촬영해줘", "사진촬영",
            "카메라로봐줘", "카메라로봐", "카메라로뭐보여",
        )

        for (targetNorm in listOf(normalized, effectiveNormalized)) {
            for (trigger in photoTriggers) {
                if (targetNorm.startsWith(trigger)) {
                    val remainder = targetNorm.removePrefix(trigger).trim()
                    val photoDetails = effectiveRaw.replaceFirst(
                        Regex("^(?:사진\\s*)?(?:찍어서|찍고|찍어줘?|촬영해서|촬영하고|촬영해줘?|촬영)\\s*"),
                        "",
                    ).trim()
                    val prompt = when {
                        remainder.isEmpty() -> "앞에 무엇이 있는지 설명해 주세요."
                        remainder in setOf("알려줘", "설명해줘", "말해줘", "뭐있어", "뭐야", "읽어줘") ->
                            "앞에 무엇이 있는지 설명해 주세요."
                        photoDetails.isNotEmpty() && remainder !in setOf("알려줘", "설명해줘", "말해줘") ->
                            "사진을 보고 다음 질문에 답해 주세요: $photoDetails"
                        remainder.contains("글자") || remainder.contains("텍스트") || remainder.contains("읽어") ->
                            "사진 속의 글자나 텍스트를 읽어 주세요."
                        else -> effectiveRaw.substringAfter("사진").trim().ifEmpty { "앞에 무엇이 있는지 설명해 주세요." }
                    }
                    return Interpretation.CloudPhotoQuestion(prompt)
                }
            }

        }

        if (isNaturalVisualQuestion(questionRaw, questionNorm)) {
            val promptLead = when {
                questionNorm.contains("글자") || questionNorm.contains("글씨") || questionNorm.contains("표지판") ||
                    questionNorm.contains("간판") || questionNorm.contains("읽어") || questionNorm.contains("텍스트") ->
                    "사진 속 글자나 표지판을 읽고 다음 질문에 답해 주세요"
                questionNorm.contains("앞에") || questionNorm.contains("앞쪽") || questionNorm.contains("전방") ||
                    questionNorm.contains("눈앞") || questionNorm.contains("주변") ->
                    "앞에 무엇이 있는지 확인하고 다음 질문에 답해 주세요"
                else -> "카메라 앞의 물체가 무엇인지 확인하고 다음 질문에 답해 주세요"
            }
            return Interpretation.CloudPhotoQuestion("$promptLead: $questionRaw")
        }

        return null
    }

    private fun isNaturalVisualQuestion(rawText: String, normalized: String): Boolean {
        if (normalized.isEmpty()) return false
        // Explicit visual vocabulary has high recall by design. A quoted or abstract phrase
        // still routes to vision when the user also says a direct visual cue.
        val visualCues = listOf("앞에", "앞쪽", "전방", "눈앞", "글씨", "글자", "텍스트", "읽어", "표지판", "간판")
        if (visualCues.any(normalized::contains)) return true
        if (isPhraseDiscussion(rawText, normalized)) return false

        // Keep the older demonstrative shorthand, but do not generalize its token to arbitrary topics.
        if (normalized in setOf("이거설명해줘", "이거알려줘")) return true

        val questionEnding = Regex(
            "(?:뭐야(?:요)?|뭐예요|무엇(?:이야|이에요|인가요|이?있(?:어|어요|나요)|이?보여(?:요)?)|" +
                "뭐(?:가)?있(?:어|어요|나요)?|뭐가보여(?:요)?|" +
                "보여(?:요)?|설명해줘(?:요)?|설명해주세요|알려줘(?:요)?|알려주세요|읽어줘(?:요)?|읽어주세요|" +
                "뭐라고(?:써|적혀)(?:있)?(?:어|어요|나요)?|무슨(?:글씨|글자|문구)(?:야|예요|가보여(?:요)?|가있(?:어|어요|나요)?))$",
        )
        val locativePrefixes = listOf("앞에", "앞쪽", "전방", "눈앞", "주변", "이거", "이게", "저것", "이물체")
        for (prefix in locativePrefixes) {
            if (normalized.startsWith(prefix)) {
                val body = normalized.removePrefix(prefix).removePrefix("에")
                if (questionEnding.containsMatchIn(body) && isVisualQuestionShape(body)) return true
            }
        }

        val cameraOrImagePrefixes = listOf("카메라앞", "카메라에보이는", "카메라로보이는", "사진속", "화면속")
        for (prefix in cameraOrImagePrefixes) {
            val body = normalized.removePrefix(prefix).removePrefix("에")
            if (normalized.startsWith(prefix) && questionEnding.containsMatchIn(body) && isVisualQuestionShape(body)) return true
        }

        val referredObjectPrefixes = listOf("저간판", "저표지판", "저물체")
        for (prefix in referredObjectPrefixes) {
            val body = normalized.removePrefix(prefix).removePrefix("에")
            if (normalized.startsWith(prefix) && questionEnding.containsMatchIn(body) && isVisualQuestionShape(body)) return true
        }

        val ocrSubjects = listOf("간판", "표지판", "글자", "글씨", "텍스트", "문구")
        val ocrEnding = Regex("(?:뭐라고(?:써|적혀)(?:있)?(?:어|어요|나요)?|읽어줘(?:요)?|읽어주세요)$")
        return ocrSubjects.any { normalized.startsWith(it) && ocrEnding.containsMatchIn(normalized.removePrefix(it).removePrefix("에")) }
    }

    private fun isVisualQuestionShape(body: String): Boolean {
        val objectReference = listOf(
            "있는", "보이는", "뭐", "무엇", "어떤게", "어떤것", "어떤거", "이름", "색", "모양",
            "물건", "물체", "사물", "표지판", "간판", "글자", "글씨", "텍스트",
        )
            .any(body::contains)
        val descriptiveEnding = listOf("설명해줘", "설명해주세요", "알려줘", "알려주세요")
            .any(body::contains)
        return !descriptiveEnding || objectReference
    }

    private fun isPhraseDiscussion(rawText: String, normalized: String): Boolean {
        val mentionFrames = listOf(
            "라는표현", "라는문장", "라는말", "라는단어", "라는문구", "문장에서", "라고하면", "라고가정하면",
            "라고물어보면", "라고말하면", "라고묻는", "라는예시",
        )
        if (mentionFrames.any(normalized::contains)) return true
        val hasQuotes = rawText.any { it in "\"'‘’“”「」『』" }
        if (!hasQuotes) return false
        val trimmed = rawText.trim()
        val startsQuoted = trimmed.firstOrNull()?.let { it in "\"'‘“「『" } == true
        val endsQuoted = trimmed.lastOrNull()?.let { it in "\"'’”」』" } == true
        if (startsQuoted && endsQuoted) return true
        return listOf("표현", "문장", "말", "단어", "뜻", "의미", "가정", "라고하면", "라고물어보면", "라고묻는")
            .any(normalized::contains)
    }

    private fun parseTextQuestion(rawText: String, normalized: String, context: CommandContext, now: Long): Interpretation? {
        val trimmed = rawText.trim()

        // 1. Ambiguous standalone question words without content
        if (normalized in setOf(
                "질문", "질문해", "질문해줘", "물어봐", "물어봐줘",
                "챗지피티", "챗지피티에게", "챗지피티한테", "chatgpt", "챗gpt",
            )
        ) {
            return Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
        }

        // 2. Anchored explicit request prefixes (e.g. "챗지피티에게 물어봐줘", "ChatGPT 질문:")
        // Matches anchored command prefix and extracts remainder verbatim without global token deletion
        val explicitCommandPattern = Regex(
            "^(?:챗지피티|ChatGPT|챗gpt|클라우드|ai)(?:에게|한테)?\\s*(?:질문해줘|질문해|질문|물어봐줘|물어봐)[\\s,:]*",
            RegexOption.IGNORE_CASE,
        )
        val commandMatch = explicitCommandPattern.find(trimmed)
        if (commandMatch != null) {
            val payload = trimmed.substring(commandMatch.range.last + 1).trim()
            return if (payload.isEmpty()) {
                Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
            } else {
                Interpretation.CloudTextQuestion(payload)
            }
        }

        // 3. Anchored vocative prefixes with delimiter or receiver particle (e.g. "챗지피티야, ...", "ChatGPT에게 ...", "챗지피티 ...")
        // Does NOT match "ChatGPT가 뭐야?", "ChatGPT의 특징", "ChatGPT는..." where provider is the subject/topic
        val vocativePattern = Regex(
            "^(?:챗지피티|ChatGPT|챗gpt)(?:에게|한테|야|아)?[\\s,:]+",
            RegexOption.IGNORE_CASE,
        )
        val vocativeMatch = vocativePattern.find(trimmed)
        if (vocativeMatch != null) {
            val payload = trimmed.substring(vocativeMatch.range.last + 1).trim()
            return if (payload.isEmpty()) {
                Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
            } else {
                Interpretation.CloudTextQuestion(payload)
            }
        }

        // 4. Anchored "질문 " or "질문:" prefix
        val questionPrefixPattern = Regex("^(?:질문있어|질문할게|질문)[\\s,:]+", RegexOption.IGNORE_CASE)
        val qPrefixMatch = questionPrefixPattern.find(trimmed)
        if (qPrefixMatch != null) {
            val payload = trimmed.substring(qPrefixMatch.range.last + 1).trim()
            return if (payload.isEmpty()) {
                Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
            } else {
                Interpretation.CloudTextQuestion(payload)
            }
        }

        return null
    }
}
