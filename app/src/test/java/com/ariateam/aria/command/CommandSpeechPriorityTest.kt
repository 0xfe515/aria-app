package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSpeechPriorityTest {

    @Test
    fun speechPriorityOrderingIsCorrect() {
        assertTrue(SpeechPriority.HazardWarning.rank > SpeechPriority.NormalCommand.rank)
        assertTrue(SpeechPriority.NormalCommand.rank > SpeechPriority.CloudResponse.rank)
        assertTrue(SpeechPriority.HazardWarning.rank > SpeechPriority.CloudResponse.rank)
    }

    @Test
    fun snapshotDefaultsToNoCloudQuestion() {
        val snapshot = CommandSnapshot()
        assertEquals(0L, snapshot.cloudQuestionId)
        assertEquals("", snapshot.cloudQuestionType)
        assertEquals("", snapshot.cloudQuestionPrompt)
    }

    @Test
    fun snapshotPreservesCloudQuestionAndExternalNavProperties() {
        val snapshot = CommandSnapshot(
            message = "사진 질문 처리 중",
            processing = true,
            cloudQuestionId = 987654321L,
            cloudQuestionType = "photo",
            cloudQuestionPrompt = "앞에 무엇이 있는지 설명해 주세요.",
            externalNavId = 123456789L,
            externalNavProvider = "naver",
            externalNavDestination = "대학로",
        )

        val copy = snapshot.copy(message = "완료")

        assertEquals(987654321L, copy.cloudQuestionId)
        assertEquals("photo", copy.cloudQuestionType)
        assertEquals("앞에 무엇이 있는지 설명해 주세요.", copy.cloudQuestionPrompt)
        assertEquals(123456789L, copy.externalNavId)
        assertEquals("naver", copy.externalNavProvider)
        assertEquals("대학로", copy.externalNavDestination)
        assertTrue(copy.processing)
    }
}
