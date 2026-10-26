package com.m57.hermescontrol.ui.chat

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NativeUiComponentsTest {
    private fun parse(line: String) = NativeUiProtocol.parse(line, true)

    private fun single(line: String): NativeUiSpec {
        val parsed = parse(line)
        assertFalse(line, parsed.rejected)
        return parsed.components.single()
    }

    private fun rejected(line: String) = assertTrue(line, parse(line).rejected)

    private val stat =
        """{"v":1,"id":"s","type":"stat","title":"家計","items":[{"label":"支出","value":182000,"unit":"yen",""" +
            """"delta":-14000,"good":"down","sub":"先月比"}]}"""
    private val donut =
        """{"v":1,"id":"d","type":"chart","style":"donut","title":"内訳","labels":["住居","食費"],""" +
            """"series":[{"name":"支出","values":[70000,46000]}]}"""
    private val table =
        """{"v":1,"id":"t","type":"table","title":"比較","columns":[{"name":"項目"},{"name":"A社","highlight":true}],""" +
            """"rows":[["初任給",{"num":280000,"unit":"yen"}],["リモート",{"text":"週3","mark":"good"}]]}"""
    private val choices =
        """{"v":1,"id":"c","type":"choices","title":"進め方","options":[{"key":"a","label":"A","pros":["近い"],""" +
            """"scores":{"効果":4},"recommended":true},{"key":"b","label":"B","cons":["遠い"]}]}"""
    private val steps =
        """{"v":1,"id":"p","type":"steps","title":"手順","items":[{"label":"作る","state":"done"},{"label":"書く","state":"now"}]}"""
    private val checklist =
        """{"v":1,"id":"k","type":"checklist","title":"持ち物","items":[{"key":"cv","label":"履歴書"},{"key":"id","label":"学生証"}]}"""
    private val timeline =
        """{"v":1,"id":"l","type":"timeline","title":"予定","events":[{"date":"2026-11-02T14:00","label":"面接"},""" +
            """{"date":"2026-10-25","label":"締切","kind":"deadline"}]}"""

    @Test fun everyNewComponentParsesWithItsDefaults() {
        val s = single(stat) as StatSpec
        assertEquals(-14000.0, s.items.single().delta!!, 0.0)
        assertEquals("down", s.items.single().good)
        val d = single(donut) as ChartSpec
        assertEquals("donut", d.style)
        val t = single(table) as TableSpec
        assertTrue(t.columns[1].highlight)
        assertEquals("28万円", t.rows[0][1].display)
        assertEquals("good", t.rows[1][1].mark)
        val c = single(choices) as ChoicesSpec
        assertEquals(listOf("効果" to 4), c.options[0].scores)
        assertTrue(c.options[0].recommended && !c.options[1].recommended)
        assertEquals(listOf("done", "now"), (single(steps) as StepsSpec).items.map { it.state })
        assertEquals(listOf("cv", "id"), (single(checklist) as ChecklistSpec).items.map { it.key })
        val events = (single(timeline) as TimelineSpec).events
        assertEquals("events are ordered by date", listOf("締切", "面接"), events.map { it.label })
        assertEquals("14:00", events[1].time.toString())
        val chart =
            """{"v":1,"id":"g","type":"chart","style":"stacked","title":"t","labels":["a","b"],""" +
                """"series":[{"name":"x","values":[1,2]}],"target":3,"highlight":1}"""
        val g = single(chart) as ChartSpec
        assertEquals(3.0, g.target!!, 0.0)
        assertEquals(1, g.highlight)
    }

    @Test fun invalidNewComponentsAreRejected() {
        listOf(
            stat.dropLast(1) + ",\"extra\":1}",
            stat.replace("\"good\":\"down\"", "\"good\":\"sideways\""),
            stat.replace("\"value\":182000", "\"value\":\"182000\""),
            stat.replace("\"unit\":\"yen\"", "\"unit\":\"とても長い単位です\""),
            donut.replace("[70000,46000]", "[70000,-1]"),
            donut.replace("[70000,46000]", "[0,0]"),
            donut.replace(
                "\"series\":[{\"name\":\"支出\",\"values\":[70000,46000]}]",
                "\"series\":[{\"name\":\"a\",\"values\":[1,2]},{\"name\":\"b\",\"values\":[1,2]}]",
            ),
            donut.replace("\"donut\"", "\"pie\""),
            donut.replace("\"style\":\"donut\"", "\"style\":\"stacked\"").replace("[70000,46000]", "[1,-2]"),
            donut.dropLast(1) + ",\"highlight\":5}",
            donut.dropLast(1) + ",\"target\":1e999}",
            table.replace("{\"name\":\"項目\"}", "{\"name\":\"項目\",\"highlight\":true}"),
            table.replace("{\"name\":\"項目\"}", "{\"name\":\"項目\",\"link\":\"https://x\"}"),
            table.replace("{\"num\":280000,\"unit\":\"yen\"}", "{\"unit\":\"yen\"}"),
            table.replace("\"mark\":\"good\"", "\"mark\":\"great\""),
            table.replace("[\"初任給\",{\"num\":280000,\"unit\":\"yen\"}]", "[\"初任給\"]"),
            choices.replace("{\"key\":\"b\",\"label\":\"B\"", "{\"key\":\"b\",\"label\":\"B\",\"recommended\":true"),
            choices.replace("\"key\":\"b\"", "\"key\":\"a\""),
            choices.replace("\"効果\":4", "\"効果\":6"),
            choices.replace("\"pros\":[\"近い\"]", "\"pros\":[\"1\",\"2\",\"3\",\"4\"]"),
            choices.replace("\"recommended\":true", "\"recommended\":\"yes\""),
            steps.replace("\"state\":\"now\"", "\"state\":\"later\""),
            steps.replace(",\"state\":\"done\"", ""),
            checklist.replace("\"key\":\"id\"", "\"key\":\"cv\""),
            checklist.replace("\"key\":\"id\"", "\"key\":\"../id\""),
            timeline.replace("2026-10-25", "2026-02-30"),
            timeline.replace("2026-10-25", "10/25"),
            timeline.replace("T14:00", "T25:00"),
            timeline.replace("\"kind\":\"deadline\"", "\"kind\":\"today\""),
        ).forEach(::rejected)
    }

    @Test fun componentCountAndSizeLimitsStillApplyToNewTypes() {
        val many = (0..14).joinToString("\n") { checklist.replace("\"id\":\"k\"", "\"id\":\"k$it\"") }
        val parsed = parse(many)
        assertEquals(12, parsed.components.size)
        assertTrue(parsed.rejected)
        val items = (0..20).joinToString(",") { "{\"key\":\"k$it\",\"label\":\"x\"}" }
        rejected("""{"v":1,"id":"k","type":"checklist","title":"t","items":[$items]}""")
    }

    @Test fun legacyComparisonRendersThroughTheTableModel() {
        val comparison =
            """{"v":1,"id":"c","type":"comparison","title":"比較","columns":["名前","説明"],"rows":[["A","a"]]}"""
        val spec = single(comparison) as ComparisonSpec
        val asTable = spec.asTable()
        assertEquals(listOf("名前", "説明"), asTable.columns.map { it.name })
        assertEquals("a", asTable.rows.single()[1].display)
        assertEquals("comparison", spec.kind)
    }

    @Test fun yenFormattingFollowsTheJapaneseUnitLadder() {
        assertEquals("0円", NativeFormat.yen(0.0))
        assertEquals("9,800円", NativeFormat.yen(9_800.0))
        assertEquals("1万円", NativeFormat.yen(10_000.0))
        assertEquals("12.3万円", NativeFormat.yen(123_400.0))
        assertEquals("18.2万円", NativeFormat.yen(182_000.0))
        assertEquals("100万円", NativeFormat.yen(999_960.0))
        assertEquals("1,542万円", NativeFormat.yen(15_420_000.0))
        assertEquals("1億円", NativeFormat.yen(99_999_999.0))
        assertEquals("1億2,345万円", NativeFormat.yen(123_450_000.0))
        assertEquals("−12.3万円", NativeFormat.yen(-123_400.0))
        assertEquals("−1.5", NativeFormat.number(-1.5))
        assertEquals("24%", NativeFormat.value(24.0, "pct"))
        assertEquals("125日", NativeFormat.value(125.0, "日"))
        assertEquals("+1.4万円", NativeFormat.signed(14_000.0, "yen"))
        assertEquals("1,542" to "万円", NativeFormat.split("1,542万円"))
        assertEquals("1億2,345" to "万円", NativeFormat.split("1億2,345万円"))
    }

    @Test fun ticksAreRoundIncludeZeroAndCoverTheData() {
        val ranges =
            listOf(
                0.0 to 1230.0,
                -10.0 to 35.0,
                0.0 to 1.1,
                0.0 to 3.0,
                65.0 to 68.0,
                -0.4 to 0.0,
                0.0 to 7_450_000.0,
                5.0 to 5.0,
            )
        for ((low, high) in ranges) {
            val ticks = niceTicks(low, high)
            assertTrue(
                "$low..$high -> ${ticks.values}",
                ticks.values.size in 4..6 || (low == high && ticks.values.size in 2..6),
            )
            assertTrue("$low..$high must include 0", ticks.values.contains(0.0))
            assertTrue(ticks.low <= minOf(0.0, low) && ticks.high >= maxOf(0.0, high))
            val mantissa = ticks.step / Math.pow(10.0, Math.floor(Math.log10(ticks.step)))
            assertTrue("step ${ticks.step}", listOf(1.0, 2.0, 5.0).any { Math.abs(it - mantissa) < 1e-9 })
        }
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), niceTicks(0.0, 1230.0).values)
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), niceTicks(0.0, 1.1).values)
        assertEquals("200万", NativeFormat.axisLabel(2_000_000.0, 2_000_000.0, "yen"))
        assertEquals("0.5", NativeFormat.axisLabel(0.5, 0.5, ""))
    }

    @Test fun timelineCountdownUsesTheInjectedDeviceClock() {
        val clock = Clock.fixed(Instant.parse("2026-10-23T15:30:00Z"), ZoneId.of("Asia/Tokyo"))
        // 15:30Z is already 10-24 in Tokyo: the device's local date decides "today".
        assertEquals(1L, daysFromToday(LocalDate.parse("2026-10-25"), clock))
        assertEquals(0L, daysFromToday(LocalDate.parse("2026-10-24"), clock))
        assertEquals(-4L, daysFromToday(LocalDate.parse("2026-10-20"), clock))
        assertEquals("明日", relativeDayLabel(1))
        assertEquals("今日", relativeDayLabel(0))
        assertEquals("あと9日", relativeDayLabel(9))
        assertEquals("", relativeDayLabel(-4))
    }

    @Test fun checklistStateIsBoundedAndSurvivesItemChanges() {
        val tokens = (0 until 40).map { checklistToken("item-$it") }.toSet()
        val encoded = encodeChecklist(tokens)
        assertTrue(encoded.length <= 256)
        assertEquals(25, decodeChecklist(encoded)!!.size)
        val kept = setOf(checklistToken("cv"), checklistToken("removed-later"))
        assertEquals(kept, decodeChecklist(encodeChecklist(kept)))
        assertEquals(emptySet<String>(), decodeChecklist(""))
        for (bad in listOf("zz", "45000,20,5", "abcdefgh,", "ABCDEF12")) assertNull(bad, decodeChecklist(bad))
    }

    @Test fun actionMessageStartsWithTheLabelThenTheShownConditions() {
        val table = single(table) as TableSpec
        assertEquals(
            "この行で相談\n「比較」\n項目: 初任給\nA社: 28万円",
            tableActionMessage(table, table.rows.first()),
        )
        val labelled = single(this.table.dropLast(1) + ",\"action\":\"A社で比較を続ける\"}") as TableSpec
        assertTrue(tableActionMessage(labelled, labelled.rows[1]).startsWith("A社で比較を続ける\n「比較」\n項目: リモート"))

        val choices = single(choices) as ChoicesSpec
        assertEquals(
            "これで進める\n「進め方」\n選択: A\n利点: 近い\n評価: 効果 4/5",
            choicesActionMessage(choices, choices.options.first()),
        )
        assertEquals("これで進める\n「進め方」\n選択: B\n懸念: 遠い", choicesActionMessage(choices, choices.options[1]))

        val savingsLine =
            """{"v":1,"id":"plan","type":"savings","title":"積立試算","monthly":30000,"years":20,"rate":5,""" +
                """"note":"手数料は含みません","action":"この条件で最小サイズを検討"}"""
        val savings = single(savingsLine) as SavingsSpec
        val result = simulateSavings(45_000.0, 10, 3.5, savings.cap, savings.initial)
        val text = savingsActionMessage(savings, 45_000.0, 10, 3.5, result)
        val lines = text.lines()
        assertEquals("この条件で最小サイズを検討", lines[0])
        assertEquals("「積立試算」", lines[1])
        assertEquals("毎月 45,000円、期間 10年、想定年率 3.5%。", lines[2])
        assertTrue(lines[4].contains(NativeFormat.yen(result.last.value)))
        assertEquals("手数料は含みません", lines.last())
        val plain = single(savingsLine.replace(",\"action\":\"この条件で最小サイズを検討\"", "")) as SavingsSpec
        assertTrue(savingsActionMessage(plain, 45_000.0, 10, 3.5, result).startsWith("この条件で相談\n"))
    }
}
