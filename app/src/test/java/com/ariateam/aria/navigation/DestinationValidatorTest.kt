package com.ariateam.aria.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationValidatorTest {

    @Test
    fun validatesValidDestinationsPreservingRoadsAndPoiNames() {
        val validDestinations = listOf(
            "한국교통대학교 충주캠퍼스",
            "충주시청",
            "안내동",
            "안내면사무소",
            "대학로",
            "을지로",
            "테헤란로",
            "자동차박물관",
            "버스터미널",
            "강남역",
            "판교역",
            "서울역",
            "홍대입구",
            "인천공항",
        )
        for (dest in validDestinations) {
            assertTrue("Expected '$dest' to be valid", DestinationValidator.isValid(dest))
        }
    }

    @Test
    fun rejectsReservedNavigationActionWordsAndSpacingVariants() {
        val reserved = listOf(
            "길안내", "길안내해", "길안내해줘", "길안내해줘요", "길안내해주세요",
            "길 안내", "길 안내해줘", "길 안내 해줘", "길 안내 해 줘", "길 안내해주세요",
            "길찾기", "길찾기해", "길찾기해줘", "길찾기해줘요", "길찾기해주세요",
            "길 찾기", "길 찾기 해줘", "길 찾기 해 줘",
            "길찾아", "길찾아줘", "길찾아줘요", "길찾아주세요",
            "길 찾아", "길 찾아줘", "길 찾아 줘",
            "안내", "안내해", "안내해줘", "안내해줘요", "안내해주세요", "안내해줄래",
            "안내 해", "안내 해줘", "안내해 줘", "안내 해 줘", "안내 해 줘요", "안내 해 주세요",
            "도보안내", "도보안내해줘", "도보로안내", "도보로안내해줘",
            "도보 안내", "도보로 안내", "도보 안내해줘", "도보로 안내해줘",
            "지도", "목적지", "도착지", "어디", "그쪽", "거기", "여기",
            "가자", "가줘", "가 줘", "가줘요", "가주세요", "가고싶어", "가고싶어요",
            "이동", "검색",
            "찾아줘", "찾아 줘", "찾아봐", "찾아봐줘", "찾아주세요",
            "알려줘", "알려 줘", "알려줘요", "알려주세요",
            "가는길", "가는길알려줘", "가는방법", "가는방법알려줘", "가는법", "가는법알려줘",
            "가는 길", "가는 길 알려줘", "가는 방법", "가는 법",
            "경로", "경로찾아줘",
            "데려다줘", "데려다 줘", "데려다줘요", "데려다주세요",
            "모셔다줘", "모셔다 줘", "모셔다줘요", "모셔다주세요",
            "어떻게가", "어떻게가요", "어떻게가줘", "어떻게가주세요",
            "어떻게 가", "어떻게 가요",
            "걸어가", "걸어가줘", "걸어가요", "걸어갈래",
            "걸어 가", "걸어 가줘",
        )
        for (word in reserved) {
            assertFalse("Expected '$word' to be rejected as destination", DestinationValidator.isValid(word))
        }
    }

    @Test
    fun rejectsStandaloneParticles() {
        val particles = listOf("까지", "으로", "에서", "에", "로", " 까지", " 으로", " 에서")
        for (p in particles) {
            assertFalse("Expected particle '$p' to be rejected", DestinationValidator.isValid(p))
        }
    }

    @Test
    fun rejectsEmptyOrOutOfRangeDestinations() {
        assertFalse(DestinationValidator.isValid(""))
        assertFalse(DestinationValidator.isValid("   "))
        assertFalse(DestinationValidator.isValid("a")) // length < 2
        assertFalse(DestinationValidator.isValid("가")) // length < 2
        assertFalse(DestinationValidator.isValid("!@#$%^")) // no letter or digit
        assertFalse(DestinationValidator.isValid("a".repeat(101))) // length > 100
    }
}
