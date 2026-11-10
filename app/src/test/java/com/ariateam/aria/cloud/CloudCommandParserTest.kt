package com.ariateam.aria.cloud

import com.ariateam.aria.command.AppCommand
import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation
import com.ariateam.aria.command.RuleCommandInterpreter
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
            "앞에",
            "앞에 뭐야",
            "앞에 뭐 있어",
            "앞쪽",
            "전방",
            "글씨",
            "글자",
            "텍스트",
            "읽어줘",
            "읽어 주세요",
            "표지판",
            "간판",
        )

        for (phrase in photoPhrases) {
            val result = parse(phrase)
            assertTrue("Expected CloudPhotoQuestion for '$phrase'", result is Interpretation.CloudPhotoQuestion)
            val photoQ = result as Interpretation.CloudPhotoQuestion
            assertTrue(photoQ.prompt.isNotBlank())
        }
    }

    @Test
    fun recognizesPhotoQuestionWrappersAndPreservesQuestionText() {
        val phrases = listOf(
            "사진 찍어서 GPT에게 이거 뭐야 알려줘" to "이거 뭐야",
            "사진 찍어서 지피티한테 이게 뭔지 알려줘" to "이게 뭔지 알려줘",
            "사진 찍어서 ChatGPT 주변 장면의 색 알려줘" to "주변 장면의 색",
            "사진 찍고 앞에 있는 문구 읽어줘" to "앞에 있는 문구 읽어줘",
            "지피티 사진 찍어서 약 봉투 내용 읽어줘!" to "약 봉투 내용 읽어줘!",
            "사진 찍어서 GPT에게 이게 뭔지 알려줘?" to "이게 뭔지 알려줘?",
            "GPT 사진 찍어서 약 봉투 내용 읽어줘!" to "약 봉투 내용 읽어줘!",
            "사진 찍어서 GPT 모델이 뭔지 알려줘?" to "GPT 모델이 뭔지 알려줘?",
        )
        phrases.forEach { (utterance, expected) ->
            val result = parse(utterance)
            assertTrue("Expected photo question for '$utterance', got $result", result is Interpretation.CloudPhotoQuestion)
            assertTrue("Prompt should preserve '$expected': ${(result as Interpretation.CloudPhotoQuestion).prompt}", result.prompt.contains(expected))
        }
        assertNull("Text topic question should not become a photo upload", parse("지피티 피자가 뭐야"))
        assertNull("Abstract color question should not become a photo upload", parse("색의 의미 알려줘"))
        listOf("사진 찍고 설정 열어", "사진 찍고 확인하고 설정 열어", "설정 열고 소리 꺼").forEach {
            assertTrue("Expected clarify for independent commands '$it'", parse(it) is Interpretation.Clarify)
        }
        assertTrue(parse("사진 찍고 다음에 질문해줘") is Interpretation.Clarify)
        assertTrue(parse("앞에 있는 거 뭐야 그리고 사진 찍어줘") is Interpretation.Clarify)
        listOf("사진 찍고 확인하고 설명해줘", "앞에 확인하고 알려줘", "질문 사과하고 배의 차이 알려줘").forEach {
            val result = parse(it)
            assertTrue("Expected a single workflow or text question for '$it', got $result", result is Interpretation.CloudPhotoQuestion || result is Interpretation.CloudTextQuestion)
        }
    }

    @Test
    fun directDemonstrativeQuestionsUseSharedPhotoEvidenceAndPreserveWording() {
        val phrases = listOf(
            "이게 뭔지 알려줘" to "이게 뭔지 알려줘",
            "지피티, 이게 뭔지 알려줘" to "이게 뭔지 알려줘",
            "GPT 이거 무슨 색인지 알려줘" to "이거 무슨 색인지 알려줘",
        )
        phrases.forEach { (utterance, expected) ->
            val result = parse(utterance)
            assertTrue("Expected photo question for '$utterance', got $result", result is Interpretation.CloudPhotoQuestion)
            assertTrue("Prompt should preserve '$expected': ${(result as Interpretation.CloudPhotoQuestion).prompt}", result.prompt.contains(expected))
        }
        assertNull("Abstract color question should not become a photo upload", parse("색의 의미 알려줘"))
        assertNull("Provider topic question should not become a photo upload", parse("지피티 피자가 뭐야"))
        assertNull("Provider model topic must stay a text question", parse("GPT 모델이 뭔지 알려줘"))
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

        val detailedCapture = parse("사진 찍어서 앞에 있는 빨간 표지판 글자 읽어줘")
        assertTrue(detailedCapture is Interpretation.CloudPhotoQuestion)
        assertTrue((detailedCapture as Interpretation.CloudPhotoQuestion).prompt.contains("빨간 표지판 글자"))
    }

    @Test
    fun routesNaturalKoreanObjectAndOcrQuestionsWithTheirDetails() {
        val phrases = listOf(
            "앞에 있는 거 뭐야?" to listOf("앞에 있는 거", "뭐야"),
            "챗지피티야, 앞에 무슨 글씨야?" to listOf("앞에 무슨 글씨야"),
            "질문: 앞쪽에 빨간 표지판 뭐라고 써 있어?" to listOf("빨간 표지판", "뭐라고 써 있어"),
            "ChatGPT, 주변에 어떤 물건이 보여요?" to listOf("주변에 어떤 물건", "보여요"),
            "눈앞에 있는 파란 상자 설명해 줘." to listOf("파란 상자", "설명해 줘"),
            "전방 간판에 뭐라고 적혀 있어?" to listOf("전방 간판", "뭐라고 적혀 있어"),
            "앞에 있는 식당 이름 알려줘" to listOf("앞에 있는 식당 이름", "알려줘"),
            "앞에 QR 코드 뭐야?" to listOf("앞에 QR 코드", "뭐야"),
            "저 간판에 뭐라고 써 있어?" to listOf("저 간판", "뭐라고 써 있어"),
            "카메라 앞에 보이는 물체가 뭐예요?" to listOf("카메라 앞에", "뭐예요"),
            "카메라에 보이는 QR 코드 뭐야?" to listOf("카메라에 보이는 QR 코드", "뭐야"),
            "사진 속에 있는 간판에 뭐라고 써 있어?" to listOf("사진 속에 있는 간판", "뭐라고 써 있어"),
        )
        for ((phrase, expectedParts) in phrases) {
            val result = parse(phrase)
            assertTrue("Expected CloudPhotoQuestion for '$phrase', got $result", result is Interpretation.CloudPhotoQuestion)
            val prompt = (result as Interpretation.CloudPhotoQuestion).prompt
            for (part in expectedParts) assertTrue("Expected '$part' in '$prompt'", prompt.contains(part))
        }
    }

    @Test
    fun invokesNaturalPhotoQuestionsThroughTopLevelInterpreterPrefixes() {
        val interpreter = RuleCommandInterpreter()
        val utterances = listOf(
            "챗지피티에게 앞에 무슨 글씨야?" to "앞에 무슨 글씨야",
            "챗지피티한테 물어봐 앞에 있는 거 뭐야?" to "앞에 있는 거 뭐야",
            "질문해줘: 앞에 무슨 글씨야" to "앞에 무슨 글씨야",
        )
        for ((utterance, expectedQuestion) in utterances) {
            val result = interpreter.interpret(utterance, CommandContext(), 0L)
            assertTrue("Expected CloudPhotoQuestion for '$utterance', got $result", result is Interpretation.CloudPhotoQuestion)
            assertTrue((result as Interpretation.CloudPhotoQuestion).prompt.contains(expectedQuestion))
        }
    }

    @Test
    fun explicitVisualCuesWinEvenInQuotedOrAbstractPhrasing() {
        listOf(
            "앞에 있는 거 뭐야라는 표현의 의미가 뭐야?",
            "무슨 글씨체를 쓰면 좋을까?",
            "‘앞에 있는 거 뭐야’라는 표현은 어떤 뜻이야?",
            "앞에 있는 거 뭐야라고 가정하면 질문이 성립해?",
            "앞에 있는 거 뭐야? 라고 물어보면 어떤 질문이 돼?",
            "‘앞에 있는 거 뭐야?’",
        ).forEach { phrase ->
            assertTrue("Expected visual cue to route '$phrase'", parse(phrase) is Interpretation.CloudPhotoQuestion)
        }
    }

    @Test
    fun doesNotRouteUnrelatedQuestionsWithoutVisualCuesToPhoto() {
        val unrelated = listOf(
            "앞으로 계획은 어떻게 세워?",
            "카메라 해상도 알려줘",
            "사진 파일 형식 설명해줘",
            "주변 맛집 알려줘",
            "화면 설정 설명해줘",
            "이거 코틀린 코드 설명해줘",
            "카메라 렌즈는 뭐야?",
        )
        for (phrase in unrelated) {
            assertNull("Unexpected cloud/photo routing for '$phrase'", parse(phrase))
        }
    }

    @Test
    fun negationsReturnClarifyAndDoNotUpload() {
        val negations = listOf(
            "질문 하지마",
            "챗지피티 묻지마",
            "물어보지마",
            "사진 찍지마",
            "카메라로 보지마",
            "앞에 있는 거 뭐야 하지마",
            "표지판 글자 읽지 말아줘",
            "질문 보내지마",
            "텍스트 읽지 마",
            "글씨 읽어주지 마",
            "앞에 사진 찍으면 안 돼",
            "표지판 사진 전송하지 마",
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
            "앞에 있는 거 뭐야 그리고 사진 찍어줘",
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

    @Test
    fun reproductionNegationAndCancelDoNotRouteToPhotoQuestion() {
        // Static reproduction cases specifically named in task
        val reproNeg1 = parse("앞에 글씨 읽으면 안 돼")
        assertTrue("Expected Clarify or Cancel for '앞에 글씨 읽으면 안 돼', got $reproNeg1", reproNeg1 is Interpretation.Clarify)

        val reproNeg2 = parse("앞에 사진 안 찍어줘")
        assertTrue("Expected Clarify or Cancel for '앞에 사진 안 찍어줘', got $reproNeg2", reproNeg2 is Interpretation.Clarify)

        val reproCancel = parse("앞에 글씨 읽기 취소")
        assertTrue("Expected Execute(Cancel) for '앞에 글씨 읽기 취소', got $reproCancel", reproCancel is Interpretation.Execute && reproCancel.command == AppCommand.Cancel)
    }

    @Test
    fun explicitNegativeActionFormsVetoPhotoAndTextQuestionDispatch() {
        val negativeForms = listOf(
            "앞에 글씨 읽으면 안 돼",
            "앞에 글씨 읽으면 안 돼요",
            "앞에 글씨 읽으면 안 된다",
            "앞에 사진 안 찍어줘",
            "앞에 사진 안 찍어",
            "앞에 사진 안 찍을래",
            "앞에 사진 안 찍는다",
            "앞에 사진 안 찍음",
            "앞에 글씨 안 읽어줘",
            "앞에 글씨 안 읽어",
            "앞에 텍스트 안 읽을래",
            "앞에 글씨 안 읽는다",
            "사진 안 찍어",
            "사진 안 찍어줘",
            "사진 안 찍을래",
            "사진 안 보낼래",
            "사진 안 보내줘",
            "사진 안 보내",
            "사진 찍으면 안 돼",
            "사진 찍으면 안 됩니다",
            "사진 촬영하면 안 돼",
            "사진 촬영하면 안 됩니다",
            "사진 보내면 안 돼",
            "사진 보내면 안 돼요",
            "질문 안 할래",
            "질문 안 물어봐",
            "챗지피티 물어보면 안 돼",
            "앞에 카메라로 안 볼래",
            "앞에 보지 마",
            "앞에 글씨 읽지 마",
            "앞에 사진 찍지 마",
        )
        for (phrase in negativeForms) {
            val result = parse(phrase)
            assertTrue("Expected Clarify for negative form '$phrase', got $result", result is Interpretation.Clarify)
            val clarify = result as Interpretation.Clarify
            assertEquals("원하는 명령을 하나만 다시 말해 주세요.", clarify.question)
        }
    }

    @Test
    fun directPhotoAndQuestionCancelReturnsExecuteCancel() {
        val cancelForms = listOf(
            "앞에 글씨 읽기 취소",
            "글씨 읽기 취소",
            "글자 읽기 취소",
            "텍스트 읽기 취소",
            "사진 취소",
            "사진 찍기 취소",
            "사진 촬영 취소",
            "촬영 취소",
            "질문 취소",
            "앞에 보는 거 취소",
            "챗지피티 취소",
            "ChatGPT 취소",
        )
        for (phrase in cancelForms) {
            val result = parse(phrase)
            assertTrue("Expected Execute(Cancel) for '$phrase', got $result", result is Interpretation.Execute && result.command == AppCommand.Cancel)
        }
    }

    @Test
    fun directPhotoAndQuestionStopCancelsActiveQuestion() {
        val stopForms = listOf(
            "사진 중지",
            "촬영 중지",
            "사진 촬영 중지",
            "질문 중지",
            "글씨 읽기 중지",
        )
        for (phrase in stopForms) {
            val result = parse(phrase)
            assertTrue("Expected Execute(Cancel) for '$phrase', got $result", result is Interpretation.Execute && result.command == AppCommand.Cancel)
        }
    }

    @Test
    fun positiveVisualQuestionsWithAnOrProhibitionsRemainPhoto() {
        val positivePhrases = listOf(
            "앞에 있는 안내판 읽어줘",
            "앞에 안내판 뭐라고 써 있어",
            "앞에 출입 금지 표지판 읽어줘",
            "전방 주차 금지 표지판 읽어줘",
            "앞에 안전 표지판 읽어줘",
            "눈앞에 보안 안내문 읽어줘",
            "앞에 있는 간판 읽어줘",
            "앞에 글씨 못 읽겠어 읽어줘",
        )
        for (phrase in positivePhrases) {
            val result = parse(phrase)
            assertTrue("Expected CloudPhotoQuestion for '$phrase', got $result", result is Interpretation.CloudPhotoQuestion)
        }
    }
}
