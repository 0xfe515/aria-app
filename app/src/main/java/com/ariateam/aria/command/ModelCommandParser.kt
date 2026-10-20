package com.ariateam.aria.command

/** Exact wire shape: lenient JSON parsers would also accept duplicate keys and unquoted values. */
object ModelCommandParser {
    private val response = Regex("""\s*\{\s*"command"\s*:\s*"(Home|Settings|SoundOn|SoundOff|Status|Help|Cancel|Stop|Clarify|Unknown)"\s*\}\s*""")

    fun parse(text: String): Interpretation {
        if (text.length > 1024) return Interpretation.Unknown
        val value = response.matchEntire(text)?.groupValues?.get(1) ?: return Interpretation.Unknown
        return when (value) {
            "Clarify" -> Interpretation.Clarify("원하는 명령을 하나만 구체적으로 말해 주세요.")
            "Unknown" -> Interpretation.Unknown
            else -> Interpretation.Execute(AppCommand.valueOf(value))
        }
    }
}
