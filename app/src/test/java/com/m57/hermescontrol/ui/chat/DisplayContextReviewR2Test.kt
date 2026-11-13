package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayContextReviewR2Test {
    private val tag = "<hermes-mobile-display-context>\n"
    private val boundary = "\n\n<!--hermes-mobile-generated-context:v1-->"

    @Test
    fun leadingReleasedDescriptorTruncationsDoNotLeakInPreview() {
        val descriptors = NativeUiInstructions.previousDescriptors + NativeUiInstructions.descriptor
        for (descriptor in descriptors) {
            val block = tag + descriptor + "\n</hermes-mobile-display-context>"
            for (length in listOf(tag.length - 1, tag.length, tag.length + 98, 200)) {
                assertEquals("length=$length", "", RichAnswerPrompt.previewText(block.take(length)))
                assertEquals(block.take(length), RichAnswerPrompt.visibleText(block.take(length)))
            }
        }
        val typed = tag + "my incomplete example"
        assertEquals(typed, RichAnswerPrompt.previewText(typed))
        val fenced = "```xml\n" + tag + NativeUiInstructions.descriptor.take(120)
        assertEquals(fenced, RichAnswerPrompt.previewText(fenced))
    }

    @Test
    fun generatedSuffixRoundTripsUnclosedFencesWithoutEatingTypedDescriptor() {
        for (fence in listOf("```kotlin", "~~~kotlin")) {
            val human = "Please inspect:\r\n$fence\r\nval x = 1  \n![image](</host/image.png>)  "
            val wire = RichAnswerPrompt.append(human)
            assertTrue(wire.contains(boundary))
            assertEquals(human, RichAnswerPrompt.visibleText(wire))
            assertEquals(human, RichAnswerPrompt.previewText(wire))
            assertEquals(wire, RichAnswerPrompt.append(wire))
            for (length in 3..boundary.length) {
                assertEquals(human, RichAnswerPrompt.previewText(wire.take(human.length + length)))
            }
            val legacy = human + "\n\n" + tag + NativeUiInstructions.descriptor + "\n</hermes-mobile-display-context>"
            assertEquals(legacy, RichAnswerPrompt.visibleText(legacy))
            assertEquals(legacy, RichAnswerPrompt.previewText(legacy))
        }
    }

    @Test
    fun humanClosingTagDoesNotBlockTruncatedGeneratedBoundaryProjection() {
        val human = "Explain </hermes-mobile-display-context> literally."
        val wire = RichAnswerPrompt.append(human)
        for (length in 3..boundary.length) {
            assertEquals(human, RichAnswerPrompt.previewText(wire.take(human.length + length)))
        }
    }

    @Test
    fun reservedBoundaryAloneDoesNotAuthorizeUnknownDescriptor() {
        val typed = "```\nexample" + boundary + "\n\n" + tag + "not client context\n</hermes-mobile-display-context>"
        assertEquals(typed, RichAnswerPrompt.visibleText(typed))
        assertEquals(typed, RichAnswerPrompt.previewText(typed))
        for (human in listOf("human\n", "human\n\n", "human\r\n  ", "![photo](</host/image.png>)  ")) {
            assertEquals(human, RichAnswerPrompt.previewText(human))
            assertEquals(human, RichAnswerPrompt.visibleText(RichAnswerPrompt.append(human)))
        }
    }
}
