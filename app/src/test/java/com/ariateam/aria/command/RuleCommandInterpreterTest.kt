package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCommandInterpreterTest {
    private val interpreter = RuleCommandInterpreter()
    private fun parse(text: String, context: CommandContext = CommandContext(), now: Long = 0) =
        interpreter.interpret(text, context, now)

    @Test fun recognizesSupportedCommandsAndSpacingVariants() {
        mapOf(
            "홈으로 가줘!" to AppCommand.Home, "설정 열어" to AppCommand.Settings,
            "안내 소리 켜 줘" to AppCommand.SoundOn, "효과음 꺼" to AppCommand.SoundOff,
            "조용히 해" to AppCommand.SoundOff, "현재 상태 알려줘" to AppCommand.Status,
            "뭐 할 수 있어?" to AppCommand.Help, "아니요" to AppCommand.Cancel,
            "그만 들어" to AppCommand.Stop,
        ).forEach { (text, command) -> assertEquals(text, Interpretation.Execute(command), parse(text)) }
    }

    @Test fun negationNeverBecomesAPositiveCommand() {
        listOf("소리 켜지 마", "설정 열지 마", "홈으로 가지 마", "듣기 중지 하지 마").forEach {
            assertTrue(it, parse(it) is Interpretation.Clarify)
        }
    }

    @Test fun compoundCommandsRequireSeparateRequests() {
        assertTrue(parse("설정 열고 그리고 소리 꺼") is Interpretation.Clarify)
        assertTrue(parse("소리 켜고 다음에 홈으로 가") is Interpretation.Clarify)
    }

    @Test fun pronounOnlyResolvesALiveSoundTarget() {
        assertEquals(Interpretation.Execute(AppCommand.SoundOff), parse("그거 꺼", CommandContext("sound", 30_000), 29_999))
        assertTrue(parse("그거 꺼", CommandContext("sound", 30_000), 30_000) is Interpretation.Clarify)
        assertTrue(parse("그거 꺼") is Interpretation.Clarify)
        assertTrue(parse("그거 꺼", CommandContext("settings", 30_000)) is Interpretation.Clarify)
    }

    @Test fun unsupportedRequestsDoNotMatchSubstrings() {
        listOf("설정 열어라는 말의 뜻", "휴대폰 소리 꺼", "날씨 알려줘", "", "네").forEach {
            assertEquals(it, Interpretation.Unknown, parse(it))
        }
    }

    @Test fun aNewInputInvalidatesALateModelResponse() {
        val generation = CommandGeneration()
        val old = generation.advance()
        assertTrue(generation.accepts(old))
        val current = generation.advance()
        assertFalse(generation.accepts(old))
        assertTrue(generation.accepts(current))
    }
}
