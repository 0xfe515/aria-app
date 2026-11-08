package com.ariateam.aria.command

import com.ariateam.aria.navigation.NavigationCommandParser
import com.ariateam.aria.navigation.NavigationProvider

/** Platform-independent interpretation; only the coordinator may execute these values. */
enum class AppCommand { Home, Settings, SoundOn, SoundOff, HideScreen, ShowScreen, Status, WalkingStatus, ForwardCalibration, Help, Cancel, Stop }

sealed interface Interpretation {
    data class Execute(val command: AppCommand) : Interpretation
    data class NavigateExternal(val provider: NavigationProvider, val destination: String) : Interpretation
    data class CloudTextQuestion(val question: String) : Interpretation
    data class CloudPhotoQuestion(val prompt: String) : Interpretation
    data class Clarify(val question: String) : Interpretation
    data object Unknown : Interpretation
}

data class CommandContext(val lastTarget: String? = null, val expiresAt: Long = 0) {
    fun target(now: Long): String? = lastTarget?.takeIf { now < expiresAt }
}

interface CommandInterpreter {
    fun interpret(text: String, context: CommandContext, now: Long): Interpretation
}

class RuleCommandInterpreter : CommandInterpreter {
    override fun interpret(text: String, context: CommandContext, now: Long): Interpretation {
        val value = normalize(text)
        if (value.isEmpty()) return Interpretation.Unknown
        // Negation must never fall through to positive substring matching.
        if (listOf("하지마", "하지말", "켜지마", "끄지마", "열지마", "가지마", "숨기지마", "보여주지마", "안내하지마", "길찾지마").any(value::contains)) {
            return Interpretation.Clarify("원하는 명령을 하나만 다시 말해 주세요.")
        }
        if (value in setOf("취소", "취소해", "취소해줘", "아니", "아니요", "됐어")) return execute(AppCommand.Cancel)
        if (value in setOf("듣기중지", "듣기중지해", "그만들어", "그만들어줘", "음성인식꺼", "전체중지")) return execute(AppCommand.Stop)
        if (listOf("그리고", "다음에").any(value::contains)) {
            return Interpretation.Clarify("명령을 하나씩 말해 주세요.")
        }

        // Direct visual requests take precedence over navigation/provider substring matches.
        // The cloud parser still owns negation and unsupported-provider handling.
        val visualCue = listOf("앞에", "앞쪽", "전방", "눈앞", "글씨", "글자", "텍스트", "읽어", "표지판", "간판")
            .any(value::contains)
        if (visualCue) {
            val visualInterpretation = com.ariateam.aria.cloud.CloudCommandParser.parse(text, context, now)
            if (visualInterpretation is Interpretation.CloudPhotoQuestion ||
                visualInterpretation is Interpretation.Clarify ||
                visualInterpretation is Interpretation.Execute
            ) {
                val secondCommandAfterHago = Regex(
                    "하고(?:홈|설정|소리|안내|효과음|화면|네이버|카카오|길찾|중지|종료|취소|닫아|열어|꺼줘|켜줘)",
                ).containsMatchIn(value)
                if (value.contains("하고") && secondCommandAfterHago) {
                    return Interpretation.Clarify("명령을 하나씩 말해 주세요.")
                }
                return visualInterpretation
            }
        }

        if (value.contains("하고")) return Interpretation.Clarify("명령을 하나씩 말해 주세요.")

        val navInterpretation = NavigationCommandParser.parse(text, context, now)
        if (navInterpretation != null) {
            return navInterpretation
        }
        val cloudInterpretation = com.ariateam.aria.cloud.CloudCommandParser.parse(text, context, now)
        if (cloudInterpretation != null) {
            return cloudInterpretation
        }
        val candidates = mutableSetOf<AppCommand>()
        if (value in setOf("홈", "홈으로", "홈으로가", "홈으로가줘", "홈열어", "홈화면열어", "홈으로이동")) candidates += AppCommand.Home
        if (value in setOf("설정", "설정열어", "설정열어줘", "설정으로가", "설정으로가줘", "설정으로이동", "설정화면열어")) candidates += AppCommand.Settings
        if (value in setOf("현재상태", "현재상태알려줘", "상태알려줘", "지금상태", "상태")) candidates += AppCommand.Status
        if (value in setOf("보행상태", "보행상태알려줘", "걷기상태", "걷기상태알려줘")) candidates += AppCommand.WalkingStatus
        if (value in setOf("정면보정", "정면보정해", "정면보정해줘", "앞방향보정", "방향보정")) candidates += AppCommand.ForwardCalibration
        if (value in setOf("도움말", "도움말알려줘", "뭐할수있어", "사용법", "사용법알려줘")) candidates += AppCommand.Help
        if (value in setOf("화면숨겨", "화면숨겨줘", "화면숨겨주세요", "화면꺼", "화면꺼줘", "화면끄기")) {
            candidates += AppCommand.HideScreen
        }
        if (value in setOf("화면보여줘", "화면보여주세요", "화면켜", "화면켜줘", "화면켜기")) {
            candidates += AppCommand.ShowScreen
        }
        val sound = value.replace("안내", "").replace("효과음", "소리").replace("피드백", "소리")
        if (sound in setOf("소리켜", "소리켜줘", "소리켜주세요", "소리켜기")) candidates += AppCommand.SoundOn
        if (sound in setOf("소리꺼", "소리꺼줘", "소리꺼주세요", "소리끄기", "조용히해", "조용히해줘")) candidates += AppCommand.SoundOff
        if (value in setOf("그거꺼", "그거꺼줘", "그거켜", "그거켜줘")) {
            if (context.target(now) == "sound") return execute(if (value.contains("꺼")) AppCommand.SoundOff else AppCommand.SoundOn)
            return Interpretation.Clarify("안내 소리를 켤까요, 끌까요? 안내 소리 켜 또는 안내 소리 꺼라고 말해 주세요.")
        }
        return when (candidates.size) {
            0 -> Interpretation.Unknown
            1 -> execute(candidates.single())
            else -> Interpretation.Clarify("명령을 하나씩 말해 주세요.")
        }
    }

    private fun execute(command: AppCommand) = Interpretation.Execute(command)

    companion object {
        fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    }
}

/** Invalidated when new speech begins, not only when its final result arrives. */
internal class CommandGeneration {
    private var value = 0L
    fun advance(): Long = ++value
    fun accepts(ticket: Long): Boolean = value == ticket
}
