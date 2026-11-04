package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFallbackPolicyTest {

    @Test
    fun cancellationUtterances_areRejectedBeforeCloudFallback() {
        val cancellations = listOf(
            "취소",
            "취소해",
            "취소해줘",
            "취소하기",
            "취소할래",
            "그만",
            "그만해",
            "그만해줘",
            "그만들어",
            "그만들어줘",
            "멈춰",
            "멈춰줘",
            "중지",
            "중지해",
            "중지해줘",
            "종료",
            "종료해",
            "안해",
            "안할래",
            "싫어",
            "됐어",
            "아니",
            "아니요",
            "듣기중지",
            "음성인식꺼",
            "전체중지",
        )

        cancellations.forEach { utterance ->
            assertTrue("Expected '$utterance' to be recognized as cancellation", LocalFallbackPolicy.isCancellation(utterance))
            assertTrue("Expected '$utterance' to be rejected before cloud fallback", LocalFallbackPolicy.shouldRejectBeforeCloudFallback(utterance))
            assertEquals("취소했어요.", LocalFallbackPolicy.rejectionMessage(utterance))
        }
    }

    @Test
    fun negationUtterances_areRejectedBeforeCloudFallback() {
        val negations = listOf(
            "하지마",
            "하지 마",
            "켜지마",
            "켜지 마",
            "끄지마",
            "끄지 마",
            "열지마",
            "열지 마",
            "가지마",
            "가지 마",
            "숨기지마",
            "숨기지 마",
            "보여주지마",
            "보여주지 마",
            "안내하지마",
            "안내하지 마",
            "길찾지마",
            "길찾지 마",
            "검색하지마",
            "검색하지 마",
            "안돼",
            "안 돼",
            "아냐",
        )

        negations.forEach { utterance ->
            assertTrue("Expected '$utterance' to be recognized as negation", LocalFallbackPolicy.isNegation(utterance))
            assertTrue("Expected '$utterance' to be rejected before cloud fallback", LocalFallbackPolicy.shouldRejectBeforeCloudFallback(utterance))
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", LocalFallbackPolicy.rejectionMessage(utterance))
        }
    }

    @Test
    fun incompleteLocalIntents_areRejectedBeforeCloudFallback() {
        val incompleteWords = listOf(
            "홈", "설정", "화면", "소리", "안내", "지도", "길안내", "내비", "내비게이션", "명령", "상태", "도움말"
        )

        incompleteWords.forEach { utterance ->
            assertTrue("Expected '$utterance' to be recognized as incomplete local intent", LocalFallbackPolicy.isIncompleteLocalIntent(utterance))
            assertTrue("Expected '$utterance' to be rejected before cloud fallback", LocalFallbackPolicy.shouldRejectBeforeCloudFallback(utterance))
            assertEquals(
                "명령을 구체적으로 말씀해 주세요. 홈으로 가, 설정 열어, 또는 길안내 등을 말할 수 있어요.",
                LocalFallbackPolicy.rejectionMessage(utterance)
            )
        }
    }

    @Test
    fun conversationalUtterances_areAllowedToFallBackToGemini() {
        val conversationalQueries = listOf(
            "오늘 서울 날씨 어때?",
            "세종대왕에 대해 알려줘",
            "경복궁이 어디에 있어?",
            "초콜릿 케이크 레시피 알려줘",
            "인공지능이란 뭐야?",
            "근처 편의점 몇 시까지 해?",
            "What is the capital of France?",
        )

        conversationalQueries.forEach { utterance ->
            assertFalse("Expected '$utterance' NOT to be rejected as cancellation", LocalFallbackPolicy.isCancellation(utterance))
            assertFalse("Expected '$utterance' NOT to be rejected as negation", LocalFallbackPolicy.isNegation(utterance))
            assertFalse("Expected '$utterance' NOT to be rejected as incomplete intent", LocalFallbackPolicy.isIncompleteLocalIntent(utterance))
            assertFalse("Expected '$utterance' to pass through to Gemini cloud fallback", LocalFallbackPolicy.shouldRejectBeforeCloudFallback(utterance))
        }
    }

    @Test
    fun blankUtterance_isSafelyRejected() {
        assertTrue(LocalFallbackPolicy.shouldRejectBeforeCloudFallback(""))
        assertTrue(LocalFallbackPolicy.shouldRejectBeforeCloudFallback("   "))
    }
}
