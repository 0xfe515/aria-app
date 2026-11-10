package com.ariateam.aria.navigation

import com.ariateam.aria.command.CommandContext
import com.ariateam.aria.command.Interpretation

/**
 * Parses Korean voice utterances for external navigation commands.
 * Handles provider selection (Google Maps, Naver Map, KakaoMap), destination extraction,
 * ambiguous providers, missing destinations, and two-turn conversational contexts.
 */
object NavigationCommandParser {
    private const val CONTEXT_DEST_PREFIX = "nav_dest:"
    private const val CONTEXT_PROV_PREFIX = "nav_prov:"

    private val NEGATION_PATTERNS = listOf(
        "하지마", "하지말", "켜지마", "끄지마", "열지마", "가지마", "숨기지마", "보여주지마",
        "안내하지마", "길찾지마", "검색하지마",
    )

    private val COMPOUND_PATTERNS = listOf("그리고", "다음에")
    private val UNSUPPORTED_TRANSPORT_MODE = Regex(
        "(?:^|\\s)(?:자동차|차|택시|버스|자전거|대중교통)\\s*(?:(?:으)?로|(?:을|를)\\s*타고|타고)",
    )

    private val APP_RESERVED_WORDS = setOf(
        "홈", "홈으로", "홈으로가", "홈으로가줘", "홈열어", "홈화면열어", "홈으로이동",
        "설정", "설정열어", "설정열어줘", "설정으로가", "설정으로가줘", "설정으로이동", "설정화면열어",
        "현재상태", "상태", "도움말", "사용법",
        "화면숨겨", "화면꺼", "화면보여줘", "화면켜",
        "소리켜", "소리꺼", "조용히해",
    )

    fun parse(text: String, context: CommandContext, now: Long): Interpretation? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val compact = trimmed.lowercase().filter { it.isLetterOrDigit() }
        if (compact.isEmpty()) return null

        // Negation check
        if (NEGATION_PATTERNS.any(compact::contains)) {
            return Interpretation.Clarify("원하는 명령을 하나만 다시 말해 주세요.")
        }

        // Compound command check
        if (COMPOUND_PATTERNS.any(compact::contains) || hasIndependentCommandAfterHago(trimmed)) {
            return Interpretation.Clarify("명령을 하나씩 말해 주세요.")
        }

        // Cancel / Stop are handled by higher-level interpreter
        if (compact in setOf("취소", "취소해", "취소해줘", "아니", "아니요", "됐어")) {
            return null
        }
        if (compact in setOf("듣기중지", "듣기중지해", "그만들어", "그만들어줘", "음성인식꺼", "전체중지")) {
            return null
        }

        // Check if this is an app-level command (Home, Settings, Screen, Sound)
        if (compact in APP_RESERVED_WORDS) {
            return null
        }

        val activeTarget = context.target(now)
        val requestsUnsupportedTransport = UNSUPPORTED_TRANSPORT_MODE.containsMatchIn(trimmed)
        if (requestsUnsupportedTransport &&
            (containsNavigationKeyword(trimmed) || activeTarget?.startsWith(CONTEXT_DEST_PREFIX) == true)) {
            return Interpretation.Clarify("현재는 도보 길안내만 지원해요. 목적지와 함께 도보로 안내해 달라고 말씀해 주세요.")
        }

        // Check active conversational context (valid for 30s)
        if (activeTarget != null) {
            if (activeTarget.startsWith(CONTEXT_DEST_PREFIX)) {
                val pendingDest = activeTarget.substring(CONTEXT_DEST_PREFIX.length).trim()
                val detectedProviders = detectProviders(trimmed)
                if (detectedProviders.size > 1) {
                    return Interpretation.Clarify("네이버 지도, 카카오맵, 구글 지도 중 하나만 선택해 주세요.")
                }
                if (detectedProviders.size == 1) {
                    return Interpretation.NavigateExternal(detectedProviders.first(), pendingDest)
                }
                if (containsNavigationKeyword(trimmed)) {
                    return Interpretation.NavigateExternal(NavigationProvider.Naver, pendingDest)
                }
            } else if (activeTarget.startsWith(CONTEXT_PROV_PREFIX)) {
                val providerId = activeTarget.substring(CONTEXT_PROV_PREFIX.length).trim()
                val provider = NavigationProvider.fromId(providerId)
                if (provider != null) {
                    val candidate = extractDestinationCandidate(trimmed, null)
                    if (DestinationValidator.isValid(candidate)) {
                        return Interpretation.NavigateExternal(provider, candidate)
                    }
                }
            }
        }

        val providers = detectProviders(trimmed)
        if (providers.size > 1) {
            return Interpretation.Clarify("네이버 지도, 카카오맵, 구글 지도 중 하나만 선택해 주세요.")
        }

        val provider = providers.firstOrNull()
        val hasNavKeyword = containsNavigationKeyword(trimmed)

        // If neither a provider nor a navigation verb is present, not a navigation command
        if (provider == null && !hasNavKeyword) {
            return null
        }

        val destinationCandidate = extractDestinationCandidate(trimmed, provider)

        return when {
            provider != null && DestinationValidator.isValid(destinationCandidate) -> {
                Interpretation.NavigateExternal(provider, destinationCandidate)
            }
            provider != null && !DestinationValidator.isValid(destinationCandidate) -> {
                Interpretation.Clarify("${provider.displayName}로 갈 목적지를 말씀해 주세요.")
            }
            provider == null && DestinationValidator.isValid(destinationCandidate) ->
                Interpretation.NavigateExternal(NavigationProvider.Naver, destinationCandidate)
            else -> {
                Interpretation.Clarify("어디로 갈까요? 목적지를 말씀해 주세요.")
            }
        }
    }

    private fun detectProviders(text: String): Set<NavigationProvider> {
        val normalized = text.lowercase()
        val found = mutableSetOf<NavigationProvider>()

        // Naver Map
        if (listOf("네이버 지도", "네이버지도", "네이버 맵", "네이버맵").any(normalized::contains) ||
            Regex("""\b네이버(로|에서)?\b""").containsMatchIn(normalized) ||
            (normalized.contains("네이버") && !normalized.contains("네이버 본사") && !normalized.contains("네이버 사옥"))
        ) {
            found.add(NavigationProvider.Naver)
        }

        // KakaoMap
        if (listOf("카카오 맵", "카카오맵", "카카오 지도", "카카오지도", "카카오 내비", "카카오내비").any(normalized::contains) ||
            Regex("""\b카카오(로|에서)?\b""").containsMatchIn(normalized) ||
            (normalized.contains("카카오") && !normalized.contains("카카오 본사") && !normalized.contains("카카오 오피스"))
        ) {
            found.add(NavigationProvider.Kakao)
        }

        // Google Maps
        if (listOf("구글 지도", "구글지도", "구글 맵", "구글맵").any(normalized::contains) ||
            Regex("""\b구글(로|에서)?\b""").containsMatchIn(normalized) ||
            (normalized.contains("구글") && !normalized.contains("구글 코리아") && !normalized.contains("구글 본사"))
        ) {
            found.add(NavigationProvider.Google)
        }

        return found
    }

    private fun containsNavigationKeyword(text: String): Boolean {
        val keywords = listOf(
            "길안내", "길 안내", "길찾기", "길 찾기", "길찾아", "안내해", "안내 해", "안내해 주세요", "안내해주세요",
            "가자", "가줘", "가 주세요", "가고 싶어", "가고싶어", "가고 싶어요", "가고싶어요",
            "가는길", "가는 길", "가는 길 좀 알려", "걸어서", "도보로",
        )
        if (keywords.any(text::contains)) return true
        val compact = text.lowercase().filter { it.isLetterOrDigit() }
        return Regex("(?:어떻게|어떡해).{0,8}(?:가|걸어가)|(?:가는방법|가는법|가는경로).{0,8}(?:알려|찾아)|(?:데려다줘|데려다주세요|모셔다줘|모셔다주세요)|걸어갈래|걸어가줘|길좀찾아줘|길찾아줘").containsMatchIn(compact)
    }

    private fun hasIndependentCommandAfterHago(text: String): Boolean {
        val compact = text.lowercase().filter { it.isLetterOrDigit() }
        return Regex("(?:하고|가고)(?:홈|설정|소리|효과음|화면|중지|종료|취소|닫아|열어|꺼줘|켜줘)")
            .containsMatchIn(compact)
    }

    private fun containsExplicitGenericNavigationKeyword(text: String): Boolean {
        val compact = text.lowercase().filter { it.isLetterOrDigit() }
        return listOf("길안내", "길찾기", "길찾아", "가는길", "가는방법", "가고싶어", "가고싶어요")
            .any(compact::contains)
    }

    fun extractDestinationCandidate(text: String, provider: NavigationProvider?): String {
        var result = text.trim().replace(Regex("[，,。.!?？]+"), " ").replace(Regex("\\s+"), " ").trim()

        // 1. Remove provider mentions (longer/more specific patterns first)
        if (provider != null) {
            val providerPatterns = when (provider) {
                NavigationProvider.Naver -> listOf(
                    "네이버 지도로", "네이버지도로", "네이버 지도에서", "네이버지도에서", "네이버 지도", "네이버지도",
                    "네이버 맵으로", "네이버맵으로", "네이버 맵에서", "네이버맵에서", "네이버 맵", "네이버맵",
                    "네이버로", "네이버에서", "네이버",
                )
                NavigationProvider.Kakao -> listOf(
                    "카카오 지도로", "카카오지도로", "카카오 지도에서", "카카오지도에서", "카카오 지도", "카카오지도",
                    "카카오 맵으로", "카카오맵으로", "카카오 맵에서", "카카오맵에서", "카카오 맵", "카카오맵",
                    "카카오 내비로", "카카오내비로", "카카오 내비에서", "카카오내비에서", "카카오 내비", "카카오내비",
                    "카카오로", "카카오에서", "카카오",
                )
                NavigationProvider.Google -> listOf(
                    "구글 지도로", "구글지도로", "구글 지도에서", "구글지도에서", "구글 지도", "구글지도",
                    "구글 맵으로", "구글맵으로", "구글 맵에서", "구글맵에서", "구글 맵", "구글맵",
                    "구글로", "구글에서", "구글",
                )
            }
            for (pattern in providerPatterns) {
                result = result.replace(pattern, " ")
            }
        }
        result = result.trim()

        // Remove any orphan leading particles leftover from provider postpositions
        val leadingParticles = listOf("으로", "로", "에서")
        for (particle in leadingParticles) {
            if (result.startsWith(particle) && (result.length == particle.length || result[particle.length].isWhitespace())) {
                result = result.substring(particle.length).trim()
                break
            }
        }

        // Strip request language only at boundaries, so action-like substrings in names survive.
        val actions = listOf(
            "도보로 안내해 주세요", "도보로 안내해주세요", "도보로 안내해줘", "도보로 안내해 주세요",
            "길 좀 찾아줘", "길좀찾아줘", "가는 방법 알려 주세요", "가는 방법 알려주세요", "가는 방법 알려줘",
            "가는법 알려줘", "가는 법 알려줘", "어떻게 가 주세요", "어떻게 가주세요", "어떻게 가줘", "어떻게 가",
            "가는 경로 찾아줘", "가는경로찾아줘", "걸어갈래", "걸어 가고 싶어", "걸어가고싶어", "걸어가고 싶어",
            "데려다 주세요", "데려다주세요", "데려다 줘", "데려다줘", "모셔다 주세요", "모셔다주세요", "모셔다 줘", "모셔다줘",
            "길 안내 해 주세요", "길 안내 해 줘", "길 안내 해줘", "길 안내해 주세요", "길 안내해줘", "길안내 해주세요", "길안내 해 주세요", "길안내해주세요", "길안내 해줘", "길안내해줘",
            "길안내", "길 안내", "길찾기 해주세요", "길찾기해주세요", "길찾아줘", "길찾기", "길찾아",
            "가는 길 좀 알려주세요", "가는 길 좀 알려줘", "가는길 알려줘", "가는 길 알려줘",
            "가는방법 알려줘", "가는 방법 알려줘", "가는 길", "가는길",
            "걸어서", "도보로", "찾아봐줘", "찾아봐", "찾아줘", "알려 주세요", "알려주세요", "알려줘",
            "안내해 주세요", "안내해주세요", "안내 해주세요", "안내해줘", "안내 해줘", "안내해", "안내",
            "가고 싶어요", "가고싶어요", "가고 싶어", "가고싶어", "가고", "가 주세요", "가주세요", "가줘", "가자",
            "이동해줘", "이동하자", "검색해줘", "검색", "열어줘", "보여줘",
        ).sortedByDescending(String::length)
        for (action in actions) {
            val genericWord = action in setOf("안내", "길안내", "길 안내", "길찾기", "가는 길", "가는길")
            if (result.startsWith(action) && (!genericWord || result.length == action.length || result[action.length].isWhitespace())) {
                result = result.removePrefix(action).trim()
                break
            }
        }
        for (action in actions) {
            val genericWord = action in setOf("안내", "길안내", "길 안내", "길찾기", "가는 길", "가는길")
            if (result.endsWith(action) && (!genericWord || result.length == action.length || result[result.length - action.length - 1].isWhitespace())) {
                result = result.removeSuffix(action).trim()
                break
            }
        }
        if (result.endsWith("가고")) result = result.removeSuffix("가고").trim()
        // Destination particles are meaningful only at the end of the extracted phrase.
        val particle = listOf("까지", "으로", "에").firstOrNull { result.endsWith(it) && result.length > it.length + 1 }
        if (particle != null) result = result.removeSuffix(particle).trim()
        val spacedParticle = listOf(" 까지", " 으로", " 에").firstOrNull { result.endsWith(it) && result.length > it.length + 1 }
        if (spacedParticle != null) result = result.removeSuffix(spacedParticle).trim()
        if (result.endsWith("로로") && result.length > 3) result = result.dropLast(1)
        val roadSuffixes = listOf(
            "대로", "순환로", "간선로", "북로", "남로", "대학로", "을지로", "충무로", "퇴계로", "테헤란로",
            "종로", "구로", "압구정로", "도산대로", "남대문로", "한강대로", "마포대로", "백범로", "율곡로",
            "사직로", "세종로", "창경궁로", "동호로", "다산로", "강변북로",
        )
        if (result.endsWith("로") && result.length > 2 && roadSuffixes.none(result::endsWith)) result = result.dropLast(1).trim()

        return DestinationValidator.sanitize(result)
    }

    fun extractPendingTarget(text: String): String? {
        val trimmed = text.trim()
        val compact = trimmed.lowercase().filter { it.isLetterOrDigit() }
        val providers = detectProviders(trimmed)
        if (providers.size > 1) return null

        val provider = providers.firstOrNull()
        if (NEGATION_PATTERNS.any(compact::contains) || COMPOUND_PATTERNS.any(compact::contains) || hasIndependentCommandAfterHago(trimmed)) {
            return null
        }
        if (provider == null && !containsNavigationKeyword(trimmed)) return null
        val candidate = extractDestinationCandidate(trimmed, provider)

        return when {
            provider != null && !DestinationValidator.isValid(candidate) -> {
                "$CONTEXT_PROV_PREFIX${provider.id}"
            }
            provider == null && DestinationValidator.isValid(candidate) -> {
                "$CONTEXT_DEST_PREFIX$candidate"
            }
            provider == null && !DestinationValidator.isValid(candidate) &&
                containsExplicitGenericNavigationKeyword(trimmed) -> {
                "$CONTEXT_PROV_PREFIX${NavigationProvider.Naver.id}"
            }
            else -> null
        }
    }

    fun makeContextTarget(destination: String?, provider: NavigationProvider?): String? {
        return when {
            destination != null && destination.isNotBlank() -> "$CONTEXT_DEST_PREFIX$destination"
            provider != null -> "$CONTEXT_PROV_PREFIX${provider.id}"
            else -> null
        }
    }
}
