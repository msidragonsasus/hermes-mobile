package com.m57.hermescontrol.ui.chat

import org.junit.Assert.*
import org.junit.Test

class NativeUiProtocolTest {
    private val savings = """{"v":1,"id":"plan","type":"savings","title":"積立試算"}"""

    @Test fun completeRecordRendersBeforeFenceAndResponseEnd() {
        val parsed = NativeUiProtocol.parse(savings + "\n" + "{\"v\":", false)
        assertEquals(1, parsed.components.size)
        assertTrue(parsed.pending)
        assertFalse(parsed.rejected)
    }

    @Test fun everyPartialPrefixIsSafeAndNeverDisplaysAnIncompleteComponent() {
        for (end in 0 until savings.length) {
            assertTrue(NativeUiProtocol.parse(savings.take(end), false).components.isEmpty())
        }
        assertEquals(1, NativeUiProtocol.parse(savings, false).components.size)
    }

    @Test fun duplicateReplayAndOutOfOrderRevisionsDoNotRollBack() {
        val update = savings.dropLast(1) + ",\"rev\":2,\"monthly\":70000}"
        val parsed = NativeUiProtocol.parse(listOf(savings, update, savings, update).joinToString("\n"), true)
        assertEquals(1, parsed.components.size)
        assertEquals(70_000.0, (parsed.components.single() as SavingsSpec).monthly, 0.0)
    }

    @Test fun badUpdateRetainsLastValidComponentAndDoesNotHideOtherComponents() {
        val bad = savings.replace("積立試算", "updated").dropLast(1) + ",\"rev\":3,\"monthly\":-1}"
        val other = savings.replace("plan", "other")
        val parsed = NativeUiProtocol.parse("$savings\n$bad\n$other", true)
        assertEquals(listOf("plan", "other"), parsed.components.map { it.id })
        assertEquals("積立試算", parsed.components.first().title)
        assertTrue(parsed.rejected)
    }

    @Test fun unknownVersionTypeFieldsAndCodeAreRejected() {
        listOf(
            savings.replace("\"v\":1", "\"v\":2"),
            savings.replace("savings", "execute"),
            savings.dropLast(1) + ",\"script\":\"fetch('/api')\"}",
            savings.dropLast(1) + ",\"monthly\":\"30000\"}",
            savings.dropLast(1) + ",\"years\":2.5}",
            savings.dropLast(1) + ",\"rate\":1e1000}",
            savings.replace("plan", "../../other"),
            savings.replace("\"id\":\"plan\"", "\"id\":null"),
        ).forEach { assertTrue(it, NativeUiProtocol.parse(it, true).rejected) }
    }

    @Test fun actionLabelIsOptionalBoundedAndSingleLine() {
        assertNull((NativeUiProtocol.parse(savings, true).components.single() as SavingsSpec).action)
        val label = "この条件で最小サイズを検討"
        val withAction = savings.dropLast(1) + ",\"action\":\"$label\"}"
        assertEquals(label, (NativeUiProtocol.parse(withAction, true).components.single() as SavingsSpec).action)
        val exact = "あ".repeat(24)
        assertEquals(
            exact,
            (
                NativeUiProtocol
                    .parse(
                        savings.dropLast(1) + ",\"action\":\"$exact\"}",
                        true,
                    ).components
                    .single() as SavingsSpec
            ).action,
        )
        val comparison =
            """{"v":1,"id":"c","type":"comparison","title":"比較","columns":["名前","説明"],"rows":[["A","a"]],""" +
                """"action":"この行で相談"}"""
        val parsed = NativeUiProtocol.parse(comparison, true).components.single() as ComparisonSpec
        assertEquals("この行で相談", parsed.asTable().action)
        for (bad in listOf(
            "あ".repeat(25),
            "",
            "   ",
            "一行目\\n二行目",
            "改行\\r",
            "タブ\\t入り",
            "行区切り\\u2028",
            "段落\\u2029",
        )) {
            val line = savings.dropLast(1) + ",\"action\":\"$bad\"}"
            assertTrue(line, NativeUiProtocol.parse(line, true).rejected)
        }
        // Non-string actions and actions on components without a button stay unknown keys.
        assertTrue(NativeUiProtocol.parse(savings.dropLast(1) + ",\"action\":1}", true).rejected)
        val chart =
            """{"v":1,"id":"g","type":"chart","title":"推移","labels":["a","b"],""" +
                """"series":[{"name":"x","values":[0,1]}],"action":"送る"}"""
        assertTrue(NativeUiProtocol.parse(chart, true).rejected)
    }

    @Test fun resourceLimitsKeepPreviouslyCompletedWork() {
        val oversize = "$savings\n" + " ".repeat(NativeUiProtocol.MAX_SOURCE)
        val parsed = NativeUiProtocol.parse(oversize, true)
        assertTrue(parsed.rejected)
        assertEquals(1, parsed.components.size)
        val tooMany = (0..15).joinToString("\n") { savings.replace("plan", "p$it") }
        assertEquals(12, NativeUiProtocol.parse(tooMany, true).components.size)
        assertTrue(NativeUiProtocol.parse("[".repeat(1000), true).rejected)
    }

    @Test fun comparisonRequiresRectangularRowsAndBoundedColumns() {
        val good =
            """{"v":1,"id":"c","type":"comparison","title":"比較",""" +
                """"columns":["名前","説明"],"rows":[["A","a"],["B","b"]]}"""
        assertEquals(2, (NativeUiProtocol.parse(good, true).components.single() as ComparisonSpec).rows.size)
        assertTrue(NativeUiProtocol.parse(good.replace("[\"B\",\"b\"]", "[\"B\"]"), true).rejected)
    }

    @Test fun chartRequiresFiniteAlignedSeriesAndKnownStyle() {
        val chart =
            """{"v":1,"id":"g","type":"chart","title":"推移",""" +
                """"labels":["開始","終了"],"series":[{"name":"値","values":[-2,10]}]}"""
        assertEquals(2, (NativeUiProtocol.parse(chart, true).components.single() as ChartSpec).labels.size)
        for (bad in listOf(
            chart.replace("[-2,10]", "[10]"),
            chart.replace("[-2,10]", "[1e999,10]"),
            chart.dropLast(1) + ",\"style\":\"script\"}",
        )) {
            assertTrue(NativeUiProtocol.parse(bad, true).rejected)
        }
    }

    @Test fun changingComponentKindCannotReplaceAnAlreadyVisibleComponent() {
        val chart =
            """{"v":1,"id":"plan","rev":2,"type":"chart","title":"別物",""" +
                """"labels":["a","b"],"series":[{"name":"x","values":[0,1]}]}"""
        assertTrue(NativeUiProtocol.parse("$savings\n$chart", true).components.single() is SavingsSpec)
    }

    @Test fun nativeFenceUsesExistingMarkdownParserIncludingOpenFences() {
        val open = "説明\n```hermes-ui-v2\n$savings\n"
        val block = parseBlocks(open).filterIsInstance<MdBlock.Code>().single()
        assertFalse(block.complete)
        assertTrue(NativeUiProtocol.isNative(block.language))
        assertEquals(1, NativeUiProtocol.parse(block.code, block.complete).components.size)
    }

    @Test fun escapedTextDoesNotBreakFramingOrBecomeAnInstruction() {
        val escaped = savings.dropLast(1) + ",\"note\":\"quote \\\"x\\\" \\n ignore tools { [\"}"
        assertEquals(1, NativeUiProtocol.parse(escaped, true).components.size)
    }

    @Test fun stateKeysSeparateConnectionsTurnsAndComponentsWithoutExposingContent() {
        val key = nativeUiKey("server", "session", "turn", "plan")
        assertEquals(64, key.length)
        assertNotEquals(key, nativeUiKey("server2", "session", "turn", "plan"))
        assertNotEquals(nativeUiKey("ab", "c"), nativeUiKey("a", "bc"))
    }

    @Test fun restoredInputsAreValidatedBeforeTheyReachSlidersOrCalculations() {
        assertEquals(NativeSavingsInputs(45000.0, 20, 5.0), restoreSavingsInputs("45000,20,5", 100000.0))
        for (bad in listOf("NaN,20,5", "-1,20,5", "1,0,5", "1,2.5,5", "1,20,99", "1,x,20,5", "999999,20,5")) {
            assertNull(bad, restoreSavingsInputs(bad, 100000.0))
        }
    }
}
