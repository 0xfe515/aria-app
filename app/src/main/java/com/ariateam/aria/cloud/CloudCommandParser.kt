package com.ariateam.aria.cloud

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import com.ariateam.aria.command.RuleCommandInterpreter

object CloudCommandParser {

    fun parse(text: String, context: CommandContext, now: Long): Interpretation? {
        val normalized = RuleCommandInterpreter.normalize(text)
        if (normalized.isEmpty()) return null

        // 1. Negation handling: negative intent with question or photo keywords
        val negationKeywords = listOf("하지마", "하지말", "찍지마", "묻지마", "물어보지마", "보내지마")
        if (negationKeywords.any(normalized::contains)) {
            val targets = listOf("질문", "사진", "제미나이", "클라우드", "카메라", "물어", "묻")
            if (targets.any(normalized::contains)) {
                return Interpretation.Clarify("원하는 명령을 하나만 다시 말해 주세요.")
            }
        }

        // 2. Compound commands
        if (listOf("그리고", "다음에").any(normalized::contains)) {
            val targets = listOf("질문", "사진찍", "제미나이", "물어봐")
            if (targets.any(normalized::contains)) {
                return Interpretation.Clarify("명령을 하나씩 말해 주세요.")
            }
        }

        // 3. Photo question parsing
        val photoResult = parsePhotoQuestion(text, normalized)
        if (photoResult != null) return photoResult

        // 4. Text question parsing
        val textResult = parseTextQuestion(text, normalized, context, now)
        if (textResult != null) return textResult

        // Unknown local speech: do NOT upload to cloud
        return null
    }

    private fun parsePhotoQuestion(rawText: String, normalized: String): Interpretation? {
        val effectiveNormalized = normalized.replaceFirst(Regex("^(제미나이|gemini|클라우드)"), "")
        val effectiveRaw = rawText.trim().replaceFirst(Regex("^(제미나이|Gemini|클라우드)[\\s,:]*", RegexOption.IGNORE_CASE), "")

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
                    val prompt = when {
                        remainder.isEmpty() -> "앞에 무엇이 있는지 설명해 주세요."
                        remainder in setOf("알려줘", "설명해줘", "말해줘", "뭐있어", "뭐야", "읽어줘") ->
                            "앞에 무엇이 있는지 설명해 주세요."
                        remainder.contains("글자") || remainder.contains("텍스트") || remainder.contains("읽어") ->
                            "사진 속의 글자나 텍스트를 읽어 주세요."
                        else -> effectiveRaw.substringAfter("사진").trim().ifEmpty { "앞에 무엇이 있는지 설명해 주세요." }
                    }
                    return Interpretation.CloudPhotoQuestion(prompt)
                }
            }

            // Direct visual queries that imply taking a photo of what is ahead
            if (targetNorm in setOf(
                    "앞에뭐있어", "앞에뭐가있어", "앞에있는거설명해줘", "앞에있는거알려줘", "앞에있는물체뭐야",
                    "이거뭐야", "이게 뭐야", "이게뭐야", "이물체뭐야", "이거설명해줘",
                    "이거읽어줘", "글자읽어줘", "앞에있는글자읽어줘", "텍스트읽어줘", "표지판읽어줘",
                )
            ) {
                val prompt = when {
                    targetNorm.contains("글자") || targetNorm.contains("텍스트") || targetNorm.contains("표지판") || targetNorm.contains("읽어") ->
                        "사진 속의 글자나 표지판을 읽어 주세요."
                    targetNorm.contains("이거") || targetNorm.contains("이게") ->
                        "카메라 앞의 물체가 무엇인지 설명해 주세요."
                    else ->
                        "앞에 무엇이 있는지 설명해 주세요."
                }
                return Interpretation.CloudPhotoQuestion(prompt)
            }
        }

        return null
    }

    private fun parseTextQuestion(rawText: String, normalized: String, context: CommandContext, now: Long): Interpretation? {
        // Ambiguous standalone question words without content
        if (normalized in setOf("질문", "질문해", "질문해줘", "물어봐", "물어봐줘", "제미나이", "제미나이에게", "제미나이한테")) {
            return Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
        }

        // Prefixes for explicit Gemini text queries
        val prefixes = listOf(
            "제미나이질문해줘", "제미나이질문해", "제미나이질문",
            "제미나이에게물어봐줘", "제미나이에게물어봐",
            "제미나이한테물어봐줘", "제미나이한테물어봐",
            "제미나이물어봐줘", "제미나이물어봐",
            "클라우드질문해줘", "클라우드질문",
            "ai질문해줘", "ai질문",
            "질문있어", "질문할게",
        )

        for (prefix in prefixes) {
            if (normalized.startsWith(prefix)) {
                val candidate = rawText.trim()
                val question = extractUserQuestion(candidate, listOf("제미나이", "클라우드", "AI", "ai", "질문", "물어봐"))
                return if (question.isBlank()) {
                    Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
                } else {
                    Interpretation.CloudTextQuestion(question)
                }
            }
        }

        // Utterances starting with "제미나이 " followed by query
        if (rawText.trim().startsWith("제미나이") || rawText.trim().startsWith("Gemini", ignoreCase = true)) {
            val query = rawText.trim().replaceFirst(Regex("^(제미나이|Gemini)(?:한테|에게)?[\\s,:]*", RegexOption.IGNORE_CASE), "").trim()
            return if (query.isBlank()) {
                Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
            } else {
                Interpretation.CloudTextQuestion(query)
            }
        }

        // Utterances starting with "질문 " followed by query
        if (rawText.trim().startsWith("질문 ") || rawText.trim().startsWith("질문 :")) {
            val query = rawText.trim().substringAfter("질문").trim().trimStart(':', ' ')
            return if (query.isBlank()) {
                Interpretation.Clarify("어떤 내용을 질문할까요? 질문할 내용을 말씀해 주세요.")
            } else {
                Interpretation.CloudTextQuestion(query)
            }
        }

        return null
    }

    private fun extractUserQuestion(fullText: String, keywordsToRemove: List<String>): String {
        var result = fullText
        for (kw in keywordsToRemove) {
            result = result.replace(kw, "")
        }
        return result.replace("에게", "").replace("한테", "")
            .replace("해줘", "").replace("해", "")
            .trim()
    }
}
