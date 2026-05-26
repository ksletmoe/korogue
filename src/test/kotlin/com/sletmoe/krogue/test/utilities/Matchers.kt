package com.sletmoe.krogue.test.utilities

import asciiPanel.AsciiCharacterData
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult

fun haveCharacterData(expectedCharacterData: AsciiCharacterData) =
    object : Matcher<AsciiCharacterData> {
        private fun characterDataDescriptor(characterData: AsciiCharacterData): String {
            return "character '${characterData.character}', foregroundColor ${characterData.foregroundColor}, " +
                "and backgroundColor ${characterData.backgroundColor}"
        }

        override fun test(value: AsciiCharacterData): MatcherResult {
            val matched =
                value.character == expectedCharacterData.character &&
                    value.foregroundColor == expectedCharacterData.foregroundColor &&
                    value.backgroundColor == expectedCharacterData.backgroundColor
            return MatcherResult(
                matched,
                {
                    "AsciiCharacterData had character ${characterDataDescriptor(value)} but we expected it to have " +
                        characterDataDescriptor(expectedCharacterData)
                },
                { "AsciiCharacterData should not have length ${characterDataDescriptor(expectedCharacterData)}" },
            )
        }
    }
