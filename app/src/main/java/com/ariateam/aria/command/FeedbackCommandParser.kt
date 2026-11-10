package com.ariateam.aria.command

/** Offline intents for Aria's own feedback settings. Values are never device media volume. */
sealed interface FeedbackCommand {
    data class SetVolume(val percent: Int) : FeedbackCommand
    data class AdjustVolume(val deltaPercent: Int) : FeedbackCommand
    data class SetFrequency(val frequency: Frequency) : FeedbackCommand
    data class SetCaution(val enabled: Boolean) : FeedbackCommand
    data object ReadSettings : FeedbackCommand
    data object ReadVolume : FeedbackCommand
    data object ReadFrequency : FeedbackCommand
    data object ReadCaution : FeedbackCommand

    enum class Frequency { FREQUENT, NORMAL, QUIET }
}

/** Typed result; malformed local settings requests are clarified locally and never sent to cloud. */
sealed interface FeedbackParseResult {
    data class Command(val value: FeedbackCommand) : FeedbackParseResult
    data class Clarify(val message: String) : FeedbackParseResult
    data object NotFeedback : FeedbackParseResult
}

object FeedbackCommandParser {
    private val volumeWords = listOf("볼륨", "음량", "소리크기", "안내소리크기", "피드백볼륨")
    private val frequencyWords = listOf("경고빈도", "알림빈도", "빈도", "경고", "알림")
    private val cautionWords = listOf("주의단계", "주의알림", "주의")
    private val number = Regex("(?<!\\d)(\\d+)(퍼센트|프로|%|으로)?(?!\\d)")
    private val negations = listOf(
        "하지마", "하지말", "끄지마", "켜지마", "줄이지마", "낮추지마", "높이지마", "하지않",
        "안꺼", "안끄", "안켜", "안높여", "안낮춰", "안줄여", "줄이지않", "낮추지않", "끄지않", "켜지않",
    )
    private val quotedFrames = listOf("라는말", "라는표현", "라는문장", "라는단어", "라는뜻", "의미", "라고하면")
    private data class ParsedAmount(val value: Int, val unit: String)

    fun parse(text: String): FeedbackParseResult {
        val value = RuleCommandInterpreter.normalize(text)
        if (value.isEmpty() || quotedFrames.any(value::contains) || LocalIntentAnalysis.photoRequest(text) != null || !hasFeedbackTarget(value)) return FeedbackParseResult.NotFeedback
        if (negations.any(value::contains)) return FeedbackParseResult.Clarify("설정을 바꾸지 않았어요. 원하는 음량, 경고 빈도, 주의 알림을 다시 말씀해 주세요.")

        if (listOf("현재알림설정", "알림설정알려", "현재설정알려", "현재설정설명", "피드백설정알려").any(value::contains)) {
            return FeedbackParseResult.Command(FeedbackCommand.ReadSettings)
        }
        if (volumeWords.any(value::contains) || listOf("좀더크게", "조금더크게", "좀더작게", "조금더작게").any(value::contains)) {
            val signedOrDecimal = Regex("[+-]\\s*(?:\\d+|[영공일이삼사오육칠팔구십백]+)|\\d+[.,]\\d+|[영공일이삼사오육칠팔구십백]+\\s*점\\s*[영공일이삼사오육칠팔구십백]+")
            if (signedOrDecimal.containsMatchIn(text) || value.contains("마이너스") || value.contains("플러스")) return FeedbackParseResult.Clarify("앱 안내 음량은 정수 0부터 100퍼센트까지예요. 부호나 소수는 사용할 수 없어요.")
            if (value.contains("몇으로")) return FeedbackParseResult.Clarify("앱 안내 음량을 몇 퍼센트로 할까요? 0부터 100까지 말해 주세요.")
            if (listOf("얼마", "몇", "현재음량", "음량어떻게", "볼륨어떻게").any(value::contains)) {
                return FeedbackParseResult.Command(FeedbackCommand.ReadVolume)
            }
            val parsedAmount = parseAmount(value)
            if (parsedAmount != null) {
                val amount = parsedAmount.value
                if (amount !in 0..100) return FeedbackParseResult.Clarify("앱 안내 음량은 0부터 100퍼센트까지예요. 원하는 값을 다시 말해 주세요.")
                val unit = parsedAmount.unit
                if (unit != "으로" && listOf("올려", "높여").any(value::contains)) return FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(amount))
                if (unit != "으로" && listOf("낮춰", "줄여").any(value::contains)) return FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(-amount))
                return FeedbackParseResult.Command(FeedbackCommand.SetVolume(amount))
            }
            if (listOf("절반", "반으로").any(value::contains)) return FeedbackParseResult.Command(FeedbackCommand.SetVolume(50))
            if (listOf("올려", "높여", "크게", "키워").any(value::contains)) return FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(10))
            if (listOf("낮춰", "줄여", "작게").any(value::contains)) return FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(-10))
            return FeedbackParseResult.Clarify("앱 안내 음량을 몇 퍼센트로 할까요? 0부터 100까지 말해 주세요.")
        }
        if (cautionWords.any(value::contains) || value.contains("위험할때만")) {
            if (value.contains("위험할때만")) return FeedbackParseResult.Command(FeedbackCommand.SetCaution(false))
            if (listOf("켜져있", "켜져있어", "꺼져있", "꺼져있어", "켜져", "현재", "알려줘", "알려주세요").any(value::contains) && !value.contains("다시켜") && !value.contains("도알려")) {
                return FeedbackParseResult.Command(FeedbackCommand.ReadCaution)
            }
            return when {
                listOf("꺼", "끄", "위험할때만").any(value::contains) -> FeedbackParseResult.Command(FeedbackCommand.SetCaution(false))
                listOf("다시켜", "켜", "도알려").any(value::contains) -> FeedbackParseResult.Command(FeedbackCommand.SetCaution(true))
                else -> FeedbackParseResult.Clarify("주의 알림을 켤까요, 끌까요?")
            }
        }
        if (frequencyWords.any(value::contains) || value.contains("자주알려") || value.contains("덜자주알려")) {
            if (listOf("어떻게", "현재", "알려줘", "알려주세요", "설정됐").any(value::contains) && !listOf("낮춰", "줄여", "높여", "자주").any(value::contains)) {
                return FeedbackParseResult.Command(FeedbackCommand.ReadFrequency)
            }
            val frequency = when {
                value.contains("기본") || value.contains("보통") -> FeedbackCommand.Frequency.NORMAL
                listOf("덜자주", "줄여", "낮춰", "조용").any(value::contains) -> FeedbackCommand.Frequency.QUIET
                listOf("자주", "높여", "더자주").any(value::contains) -> FeedbackCommand.Frequency.FREQUENT
                else -> null
            }
            return frequency?.let { FeedbackParseResult.Command(FeedbackCommand.SetFrequency(it)) }
                ?: FeedbackParseResult.Clarify("경고 빈도를 자주, 보통, 또는 덜 자주 중에서 말씀해 주세요.")
        }
        return FeedbackParseResult.Clarify("음량은 0부터 100까지, 경고 빈도는 자주·보통·덜 자주, 주의 알림은 켜기·끄기로 말씀해 주세요.")
    }

    private fun hasFeedbackTarget(value: String) =
        volumeWords.any(value::contains) || frequencyWords.any(value::contains) || cautionWords.any(value::contains) ||
        value.contains("현재알림설정") || value.contains("현재설정알려") || value.contains("현재설정설명") || value.contains("피드백설정") ||
            value.contains("자주알려") || value.contains("덜자주알려") || value.contains("위험할때만") ||
            listOf("좀더크게", "조금더크게", "좀더작게", "조금더작게").any(value::contains)

    private fun parseAmount(value: String): ParsedAmount? {
        number.find(value)?.let { match ->
            return ParsedAmount(match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE, match.groupValues[2])
        }
        val target = volumeWords.filter(value::contains).maxByOrNull { it.length } ?: return null
        var tail = value.substring(value.indexOf(target) + target.length)
        tail = tail.removePrefix("을").removePrefix("은").removePrefix("는")
        val match = Regex("^([영공일이삼사오육칠팔구십백]+)(퍼센트|프로|으로)?").find(tail) ?: return null
        val spoken = match.groupValues[1]
        val amount = parseSinoKorean(spoken)
        if (amount == null) return ParsedAmount(Int.MAX_VALUE, match.groupValues[2])
        return ParsedAmount(amount, match.groupValues[2])
    }

    /** Small Sino-Korean integer grammar scoped to a volume target; rejects partial or >100 values. */
    private fun parseSinoKorean(value: String): Int? {
        if (value == "영" || value == "공") return 0
        val digits = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)
        var section = 0
        var pending = 0
        var lastUnit = Int.MAX_VALUE
        for (char in value) {
            val digit = digits[char]
            if (digit != null) {
                if (pending != 0) return null
                pending = digit
                continue
            }
            when (char) {
                '십' -> {
                    if (lastUnit <= 10) return null
                    section += (if (pending == 0) 1 else pending) * 10
                    pending = 0
                    lastUnit = 10
                }
                '백' -> {
                    if (lastUnit <= 100) return null
                    section += (if (pending == 0) 1 else pending) * 100
                    pending = 0
                    lastUnit = 100
                }
                else -> return null
            }
        }
        val result = section + pending
        return result.takeIf { it in 0..100 }
    }
}
