package com.ariateam.aria.cloud

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCommandParserTest {

    private fun parse(text: String, context: CommandContext = CommandContext(), now: Long = 0L): Interpretation? {
        return CloudCommandParser.parse(text, context, now)
    }

    @Test
    fun parsesExplicitTextQuestionsWithChatGptAliases() {
        val q1 = parse("챗지피티 질문 오늘 날씨 어때")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertTrue((q1 as Interpretation.CloudTextQuestion).question.contains("오늘 날씨 어때"))

        val q2 = parse("ChatGPT에게 서울역 어디야 물어봐")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertTrue((q2 as Interpretation.CloudTextQuestion).question.contains("서울역 어디야"))

        val q3 = parse("챗지피티한테 물어봐 프랑스 수도가 어디야")
        assertTrue(q3 is Interpretation.CloudTextQuestion)
        assertTrue((q3 as Interpretation.CloudTextQuestion).question.contains("프랑스 수도가 어디야"))

        val q4 = parse("질문 있어 피자의 유래가 뭐야")
        assertTrue(q4 is Interpretation.CloudTextQuestion)
        assertTrue((q4 as Interpretation.CloudTextQuestion).question.contains("피자의 유래가 뭐야"))

        val q5 = parse("챗지피티 오늘 무슨 요일이야")
        assertTrue(q5 is Interpretation.CloudTextQuestion)
        assertEquals("오늘 무슨 요일이야", (q5 as Interpretation.CloudTextQuestion).question)

        val q6 = parse("질문 오늘 몇 시야")
        assertTrue(q6 is Interpretation.CloudTextQuestion)
        assertEquals("오늘 몇 시야", (q6 as Interpretation.CloudTextQuestion).question)
    }

    @Test
    fun ambiguousStandaloneTriggerClarifies() {
        val standaloneWords = listOf("질문", "질문해", "물어봐", "물어봐줘", "챗지피티", "챗지피티에게", "chatgpt")
        for (word in standaloneWords) {
            val result = parse(word)
            assertTrue("Expected Clarify for '$word'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertTrue(clarify.question.contains("어떤 내용을 질문할까요"))
        }
    }

    @Test
    fun explicitTargetingUnsupportedGeminiClarifies() {
        val targetedPhrases = listOf(
            "제미나이로 질문해줘 오늘 날씨",
            "제미나이에게 물어봐",
            "제미나이한테 물어봐줘 서울역 가는 법",
            "제미나이 질문해줘",
            "제미나이",
            "gemini",
        )
        for (phrase in targetedPhrases) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertTrue(clarify.question.contains("공식 ChatGPT 로그인만 지원해요"))
        }
    }

    @Test
    fun explicitTargetingUnsupportedClaudeClarifies() {
        val targetedPhrases = listOf(
            "클로드로 질문해줘",
            "클로드한테 물어봐줘 오늘 뉴스",
            "클로드에게 질문",
            "클로드",
            "claude",
        )
        for (phrase in targetedPhrases) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertTrue(clarify.question.contains("공식 ChatGPT 로그인만 지원해요"))
        }
    }

    @Test
    fun ordinaryQuestionsMerelyMentioningGeminiOrClaudeArePreserved() {
        // Ordinary questions where the provider is the topic, not an explicit targeting command
        val q1 = parse("질문 제미나이가 뭐야?")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertTrue((q1 as Interpretation.CloudTextQuestion).question.contains("제미나이"))

        val q2 = parse("챗지피티 클로드와 챗지피티 차이 알려줘")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertTrue((q2 as Interpretation.CloudTextQuestion).question.contains("클로드"))
    }

    @Test
    fun preservedKoreanVocabularyContainingHae() {
        val q1 = parse("챗지피티야 해양 생태계에 대해 설명해줘")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertEquals("해양 생태계에 대해 설명해줘", (q1 as Interpretation.CloudTextQuestion).question)

        val q2 = parse("ChatGPT에게 해외 여행 준비물 알려줘")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertEquals("해외 여행 준비물 알려줘", (q2 as Interpretation.CloudTextQuestion).question)
    }

    @Test
    fun subjectQuestionsMentioningChatGptPreserveSubjectOrNullForCoordinatorFallback() {
        // When ChatGPT is the subject/topic of the question, it must not be stripped or trigger clarification
        val q1 = parse("질문 ChatGPT가 뭐야?")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertEquals("ChatGPT가 뭐야?", (q1 as Interpretation.CloudTextQuestion).question)

        val q2 = parse("질문 ChatGPT의 특징은 뭐야?")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertEquals("ChatGPT의 특징은 뭐야?", (q2 as Interpretation.CloudTextQuestion).question)
    }

    @Test
    fun explicitPhotoTargetingUnsupportedProviderClarifies() {
        val photoPhrases = listOf(
            "제미나이에게 사진 보내줘",
            "제미나이로 사진 찍어줘",
            "클로드에게 사진 보내줘",
        )
        for (phrase in photoPhrases) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertTrue(clarify.question.contains("공식 ChatGPT 로그인만 지원해요"))
        }
    }

    @Test
    fun parsesExplicitPhotoQuestions() {
        val photoPhrases = listOf(
            "사진 찍어서 알려줘",
            "사진 찍고 설명해줘",
            "사진 찍어줘",
            "앞에 뭐 있어",
            "앞에 뭐가 있어",
            "앞에 있는 거 설명해줘",
            "이거 뭐야",
            "이게 뭐야",
            "글자 읽어줘",
            "앞에 있는 글자 읽어줘",
            "카메라로 봐줘",
            "카메라로 뭐 보여",
            "챗지피티 사진 찍어줘",
        )

        for (phrase in photoPhrases) {
            val result = parse(phrase)
            assertTrue("Expected CloudPhotoQuestion for '$phrase'", result is Interpretation.CloudPhotoQuestion)
            val photoQ = result as Interpretation.CloudPhotoQuestion
            assertTrue(photoQ.prompt.isNotBlank())
        }
    }

    @Test
    fun photoQuestionExtractsSpecificPrompt() {
        val result1 = parse("사진 찍어서 글자 읽어줘")
        assertTrue(result1 is Interpretation.CloudPhotoQuestion)
        assertTrue((result1 as Interpretation.CloudPhotoQuestion).prompt.contains("글자"))

        val result2 = parse("이거 뭐야")
        assertTrue(result2 is Interpretation.CloudPhotoQuestion)
        assertTrue((result2 as Interpretation.CloudPhotoQuestion).prompt.contains("물체"))

        val result3 = parse("앞에 뭐 있어")
        assertTrue(result3 is Interpretation.CloudPhotoQuestion)
        assertTrue((result3 as Interpretation.CloudPhotoQuestion).prompt.contains("앞에 무엇이 있는지"))
    }

    @Test
    fun negationsReturnClarifyAndDoNotUpload() {
        val negations = listOf(
            "질문 하지마",
            "챗지피티 묻지마",
            "물어보지마",
            "사진 찍지마",
            "카메라로 보지마",
            "질문 보내지마",
        )

        for (phrase in negations) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun compoundCommandsReturnClarify() {
        val compounds = listOf(
            "질문하고 그리고 사진 찍어줘",
            "사진 찍고 다음에 질문해줘",
        )

        for (phrase in compounds) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("명령을 하나씩 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun unrelatedSpeechReturnsNullForLocalProcessing() {
        assertNull(parse("안녕"))
        assertNull(parse("오늘 날씨"))
        assertNull(parse("불 켜줘"))
        assertNull(parse("소리 켜"))
    }
}
