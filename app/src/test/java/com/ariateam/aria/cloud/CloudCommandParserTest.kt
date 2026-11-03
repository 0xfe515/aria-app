package com.ariateam.aria.cloud

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCommandParserTest {

    private fun parse(text: String, context: CommandContext = CommandContext(), now: Long = 0L): Interpretation? {
        return CloudCommandParser.parse(text, context, now)
    }

    @Test
    fun parsesExplicitTextQuestions() {
        val q1 = parse("제미나이 질문 오늘 날씨 어때")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertTrue((q1 as Interpretation.CloudTextQuestion).question.contains("오늘 날씨 어때"))

        val q2 = parse("제미나이에게 서울역 어디야 물어봐")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertTrue((q2 as Interpretation.CloudTextQuestion).question.contains("서울역 어디야"))

        val q3 = parse("제미나이한테 물어봐 프랑스 수도가 어디야")
        assertTrue(q3 is Interpretation.CloudTextQuestion)
        assertTrue((q3 as Interpretation.CloudTextQuestion).question.contains("프랑스 수도가 어디야"))

        val q4 = parse("질문 있어 피자의 유래가 뭐야")
        assertTrue(q4 is Interpretation.CloudTextQuestion)
        assertTrue((q4 as Interpretation.CloudTextQuestion).question.contains("피자의 유래가 뭐야"))

        val q5 = parse("제미나이 오늘 무슨 요일이야")
        assertTrue(q5 is Interpretation.CloudTextQuestion)
        assertEquals("오늘 무슨 요일이야", (q5 as Interpretation.CloudTextQuestion).question)

        val q6 = parse("질문 오늘 몇 시야")
        assertTrue(q6 is Interpretation.CloudTextQuestion)
        assertEquals("오늘 몇 시야", (q6 as Interpretation.CloudTextQuestion).question)
    }

    @Test
    fun ambiguousStandaloneTriggerClarifies() {
        val standaloneWords = listOf("질문", "질문해", "물어봐", "물어봐줘", "제미나이", "제미나이에게")
        for (word in standaloneWords) {
            val result = parse(word)
            assertTrue("Expected Clarify for '$word'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertTrue(clarify.question.contains("어떤 내용을 질문할까요"))
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
            "제미나이 묻지마",
            "물어보지마",
            "사진 찍지마",
            "사진 보내지마",
        )

        for (phrase in negations) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for negation '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun compoundStatementsReturnClarify() {
        val compounds = listOf(
            "사진 찍고 그리고 홈으로 가",
            "제미나이 질문하고 다음에 소리 꺼",
        )

        for (phrase in compounds) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for compound '$phrase'", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("명령을 하나씩 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun unknownLocalSpeechReturnsNullAndNeverUploads() {
        val unknownPhrases = listOf(
            "치킨 먹고 싶다",
            "산책 가자",
            "오늘 너무 덥다",
            "배고파",
            "안녕",
            "멍멍",
            "홈",
            "설정",
            "소리 꺼",
            "네이버 지도로 강남역 안내해줘",
        )

        for (phrase in unknownPhrases) {
            val result = parse(phrase)
            assertNull("Expected null for '$phrase' so local logic handles it", result)
        }
    }

    @Test
    fun visualQuestionsWithGeminiPrefixParseAsPhotoQuestions() {
        val visualPhrases = listOf(
            "제미나이 앞에 뭐 있어",
            "제미나이 이거 뭐야",
            "제미나이 사진 찍어줘",
            "제미나이 카메라로 봐줘",
            "클라우드 앞에 뭐 있어",
        )

        for (phrase in visualPhrases) {
            val result = parse(phrase)
            assertTrue("Expected CloudPhotoQuestion for '$phrase'", result is Interpretation.CloudPhotoQuestion)
            val photoQ = result as Interpretation.CloudPhotoQuestion
            assertTrue(photoQ.prompt.isNotBlank())
        }
    }

    @Test
    fun textQuestionsWithKoreanParticlesStripParticles() {
        val q1 = parse("제미나이한테 오늘 날씨 알려줘")
        assertTrue(q1 is Interpretation.CloudTextQuestion)
        assertEquals("오늘 날씨 알려줘", (q1 as Interpretation.CloudTextQuestion).question)

        val q2 = parse("제미나이에게 서울 날씨 알려줘")
        assertTrue(q2 is Interpretation.CloudTextQuestion)
        assertEquals("서울 날씨 알려줘", (q2 as Interpretation.CloudTextQuestion).question)
    }
}
