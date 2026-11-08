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
            "화면 숨겨" to AppCommand.HideScreen, "화면 꺼" to AppCommand.HideScreen,
            "화면 보여줘" to AppCommand.ShowScreen, "화면 켜" to AppCommand.ShowScreen,
            "조용히 해" to AppCommand.SoundOff, "현재 상태 알려줘" to AppCommand.Status,
            "보행 상태" to AppCommand.WalkingStatus, "정면 보정" to AppCommand.ForwardCalibration,
            "뭐 할 수 있어?" to AppCommand.Help, "아니요" to AppCommand.Cancel,
            "그만 들어" to AppCommand.Stop,
        ).forEach { (text, command) -> assertEquals(text, Interpretation.Execute(command), parse(text)) }
    }

    @Test fun negationNeverBecomesAPositiveCommand() {
        listOf(
            "소리 켜지 마", "설정 열지 마", "홈으로 가지 마", "듣기 중지 하지 마",
            "화면 켜지 마", "화면 끄지 마", "화면 숨기지 마", "화면 보여주지 마",
        ).forEach {
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

    @Test fun recognizesExternalNavigationCommands() {
        val nav = parse("네이버 지도로 강남역 안내해줘")
        assertTrue(nav is Interpretation.NavigateExternal)
        val result = nav as Interpretation.NavigateExternal
        assertEquals(com.ariateam.aria.navigation.NavigationProvider.Naver, result.provider)
        assertEquals("강남역", result.destination)
    }

    @Test fun recognizesCloudTextQuestionCommands() {
        val result = parse("챗지피티 질문 오늘 서울 날씨 어때")
        assertTrue(result is Interpretation.CloudTextQuestion)
        val q = result as Interpretation.CloudTextQuestion
        assertTrue(q.question.contains("오늘 서울 날씨 어때"))
    }

    @Test fun recognizesCloudPhotoQuestionCommands() {
        val result = parse("사진 찍어서 알려줘")
        assertTrue(result is Interpretation.CloudPhotoQuestion)
    }

    @Test fun directVisualCuePrecedesNavigationMatching() {
        listOf(
            "앞에 뭐 있어",
            "표지판 글씨 읽어줘",
            "전방 간판 텍스트 읽어 주세요",
            "앞에 뭐가 있는지 확인하고 알려줘",
            "앞에 텍스트 확인하고 읽어줘",
        ).forEach { utterance ->
            assertTrue(utterance, parse(utterance) is Interpretation.CloudPhotoQuestion)
        }
        assertTrue("앞에 뭐야 하고 설정 열어", parse("앞에 뭐야 하고 설정 열어") is Interpretation.Clarify)
        listOf("텍스트 읽지 마", "글씨 읽어주지 마", "앞에 사진 찍으면 안 돼").forEach { utterance ->
            assertTrue(utterance, parse(utterance) is Interpretation.Clarify)
        }
    }
}
