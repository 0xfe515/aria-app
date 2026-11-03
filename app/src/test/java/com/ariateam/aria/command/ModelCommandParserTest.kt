package com.ariateam.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCommandParserTest {
    @Test fun acceptsOnlyASingleKnownCommand() {
        (AppCommand.entries - setOf(AppCommand.HideScreen, AppCommand.ShowScreen)).forEach {
            assertEquals(Interpretation.Execute(it), ModelCommandParser.parse(""" { "command" : "$it" } """))
        }
        assertTrue(ModelCommandParser.parse("""{"command":"Clarify"}""") is Interpretation.Clarify)
    }

    @Test fun screenVisibilityActionsCannotBeInferredByTheModel() {
        listOf(AppCommand.HideScreen, AppCommand.ShowScreen).forEach {
            assertEquals(Interpretation.Unknown, ModelCommandParser.parse("""{"command":"$it"}"""))
        }
    }

    @Test fun rejectsAmbiguousMalformedAndUnapprovedOutput() {
        listOf(
            """{"command":"LaunchApp"}""",
            """{"command":"SoundOff","intent":"anything"}""",
            """[{"command":"Stop"}]""",
            """{"command":"Stop"} {"command":"Home"}""",
            """{"command":"Stop","command":"Home"}""",
            """{command:"Stop"}""",
            """{"command":null}""",
            """```json {"command":"Stop"} ```""",
            "not json",
            "x".repeat(1025),
        ).forEach { assertEquals(it, Interpretation.Unknown, ModelCommandParser.parse(it)) }
    }
}
