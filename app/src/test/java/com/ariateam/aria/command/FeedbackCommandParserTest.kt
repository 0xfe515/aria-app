package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackCommandParserTest {
    @Test fun parsesVolumeFormsAndBoundsRequests() {
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(50)), FeedbackCommandParser.parse("볼륨 50퍼센트"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(50)), FeedbackCommandParser.parse("볼륨을 50으로 올려줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(50)), FeedbackCommandParser.parse("소리 크기 절반"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(70)), FeedbackCommandParser.parse("음량 70프로"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(50)), FeedbackCommandParser.parse("볼륨 오십 퍼센트"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(70)), FeedbackCommandParser.parse("음량 칠십으로"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(20)), FeedbackCommandParser.parse("볼륨 이십 퍼센트"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(2)), FeedbackCommandParser.parse("음량 이 프로 올려"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(20)), FeedbackCommandParser.parse("음량은 이십으로"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(0)), FeedbackCommandParser.parse("볼륨 영 퍼센트"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetVolume(100)), FeedbackCommandParser.parse("볼륨 백"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(10)), FeedbackCommandParser.parse("볼륨 십 퍼센트 올려"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(10)), FeedbackCommandParser.parse("볼륨 좀 더 크게"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(10)), FeedbackCommandParser.parse("볼륨 10퍼센트 올려"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(-5)), FeedbackCommandParser.parse("음량 5프로 낮춰"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.AdjustVolume(-10)), FeedbackCommandParser.parse("소리크기 낮춰"))
        assertTrue(FeedbackCommandParser.parse("볼륨 130퍼센트") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("음량 1000") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 -10") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 10.5") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("음량 오십 점 오") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 -십") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 10000000000") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 백오십") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 이백") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 오십십") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨 마이너스 십") is FeedbackParseResult.Clarify)
        assertTrue(FeedbackCommandParser.parse("볼륨을 몇으로") is FeedbackParseResult.Clarify)
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadVolume), FeedbackCommandParser.parse("볼륨 몇이야"))
    }

    @Test fun parsesFrequencyCautionAndReadbackOffline() {
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetFrequency(FeedbackCommand.Frequency.QUIET)), FeedbackCommandParser.parse("경고 빈도 낮춰"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetFrequency(FeedbackCommand.Frequency.QUIET)), FeedbackCommandParser.parse("경고 덜 자주"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetFrequency(FeedbackCommand.Frequency.FREQUENT)), FeedbackCommandParser.parse("자주 알려줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetFrequency(FeedbackCommand.Frequency.NORMAL)), FeedbackCommandParser.parse("빈도 기본으로"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetCaution(false)), FeedbackCommandParser.parse("위험할 때만 알려줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetCaution(false)), FeedbackCommandParser.parse("주의 알림 끄기"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetCaution(false)), FeedbackCommandParser.parse("주의 단계는 아예 꺼"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetCaution(true)), FeedbackCommandParser.parse("주의 다시 켜"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.SetCaution(true)), FeedbackCommandParser.parse("주의도 알려줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadSettings), FeedbackCommandParser.parse("현재 알림 설정 알려줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadSettings), FeedbackCommandParser.parse("현재 설정 설명해줘"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadVolume), FeedbackCommandParser.parse("음량 얼마야"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadFrequency), FeedbackCommandParser.parse("경고 빈도 어떻게 돼"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadCaution), FeedbackCommandParser.parse("주의 알림 켜져 있어"))
        assertEquals(FeedbackParseResult.Command(FeedbackCommand.ReadCaution), FeedbackCommandParser.parse("주의 알림 꺼져 있어"))
        assertEquals(FeedbackParseResult.NotFeedback, FeedbackCommandParser.parse("볼륨 50으로 해라는 말의 뜻"))
        assertEquals(FeedbackParseResult.NotFeedback, FeedbackCommandParser.parse("사진 속 주의 문구 읽어줘"))
        assertEquals(FeedbackParseResult.NotFeedback, FeedbackCommandParser.parse("앞에 경고 표지판 읽어줘"))
    }

    @Test fun negativeSettingsRequestsNeverBecomePositiveMutations() {
        listOf("주의 단계 끄지 마", "알림 줄이지 마", "경고 빈도 낮추지 마").forEach {
            assertTrue("$it", FeedbackCommandParser.parse(it) is FeedbackParseResult.Clarify)
            assertTrue("$it", RuleCommandInterpreter().interpret(it, CommandContext(), 0) is Interpretation.Clarify)
        }
        listOf("주의 알림 안 꺼", "주의 단계 안 켜", "볼륨 안 높여", "볼륨 줄이지 않아").forEach {
            assertTrue("$it", RuleCommandInterpreter().interpret(it, CommandContext(), 0) is Interpretation.Clarify)
        }
    }
}
