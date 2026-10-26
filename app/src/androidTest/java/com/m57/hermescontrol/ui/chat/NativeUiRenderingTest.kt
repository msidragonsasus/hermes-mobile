package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class NativeUiRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source =
        mutableStateOf(
            """
            条件を動かせます。
            ```hermes-ui-v2
            {"v":1,"id":"plan","type":"savings","title":"積立シミュレーター"}
            """.trimIndent(),
        )
    private val visible = mutableStateOf(true)
    private val streaming = mutableStateOf(true)
    private var historyOpened = false

    // Messages the host was asked to send; sending stops input like a generating reply does.
    private val sent = CopyOnWriteArrayList<String>()
    private val canSubmit = mutableStateOf(true)

    // Saved inputs persist on disk across runs; a fresh scope per test keeps runs independent.
    private val hostScope = "test-" + UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneId.of("Asia/Tokyo"))

    private fun show(
        dark: Boolean = true,
        scale: Float = 1f,
    ) {
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                    // Like the chat screen: one tap sends, then input waits for the reply.
                    LocalNativeUiHost provides
                        NativeUiHost(hostScope, canSubmit.value) {
                            sent += it
                            canSubmit.value = false
                        },
                    LocalNativeUiClock provides clock,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .conversationHistorySwipe { historyOpened = true }
                            .verticalScroll(rememberScrollState())
                            .width(320.dp)
                            .padding(12.dp),
                    ) {
                        if (visible.value) {
                            MarkdownText(
                                source.value,
                                MaterialTheme.colorScheme.onSurface,
                                isStreaming = streaming.value,
                                nativeUiIdentity = "same-turn",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun openFenceAlreadyHasWorkingControlsAndContinuouslyUpdatesUnderHistoryGesture() {
        show()
        compose.onNodeWithTag("plan-monthly").assertIsDisplayed()
        val initial = label()
        val initialTotal = textAt("plan-total")
        val initialChart = textAt("plan-growth-selection")
        compose.onNodeWithTag("plan-monthly").performTouchInput {
            down(Offset(width * .3f, centerY))
            moveTo(Offset(width * .45f, centerY), 100)
        }
        val first = label()
        assertNotEquals(initial, first)
        compose.onNodeWithTag("plan-monthly").performTouchInput { moveTo(Offset(width * .7f, centerY), 100) }
        assertNotEquals(first, label())
        assertNotEquals(initialTotal, textAt("plan-total"))
        assertNotEquals(initialChart, textAt("plan-growth-selection"))
        assertFalse(historyOpened)
        capture("native-savings-held")
        compose.onNodeWithTag("plan-monthly").performTouchInput { up() }
        compose.onNodeWithTag("plan-total").assertIsDisplayed()
    }

    @Test fun laterComponentAndRevisionDoNotResetUserEditedValue() {
        show()
        compose.onNodeWithTag("plan-monthly").performTouchInput {
            swipe(
                Offset(width * .3f, centerY),
                Offset(width * .65f, centerY),
            )
        }
        val changed = label()
        compose.runOnIdle {
            source.value +=
                "\n" + """{"v":1,"id":"plan","rev":2,"type":"savings","title":"条件更新","monthly":1000}""" +
                "\n" +
                """{"v":1,"id":"c","type":"comparison","title":"比較","columns":["項目","値"],"rows":[["A","10"],["B","20"]]}"""
        }
        compose.onNodeWithText("条件更新").assertIsDisplayed()
        assertEquals(changed, label())
        compose.onNodeWithText("比較").performScrollTo().assertIsDisplayed()
        capture("native-mixed-stream")
    }

    @Test fun actionButtonSendsCurrentValuesOnceThenWaits() {
        show()
        compose
            .onNodeWithTag("plan-discuss")
            .performScrollTo()
            .assertTextEquals(NativeActionLabels.SAVINGS)
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            val text = sent.single()
            assertTrue(text.startsWith("この条件で相談\n「積立シミュレーター」\n"))
            assertTrue(text.contains("毎月 30,000円、期間 20年"))
            assertTrue(text.contains("端末内試算"))
        }
        // Repeated taps while the reply is generating never send again.
        compose.onNodeWithTag("plan-discuss").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("plan-discuss").performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun svgDiagramRendersWithAltAndOpensTheZoomView() {
        source.value = "```hermes-ui-v2\n${NativeUiGalleryTest.SVG_FIXTURE}\n```"
        show()
        val alt = "緑の背景幕の前に被写体、その手前にカメラと照明2灯を置いた配置図"
        compose.onNodeWithText("クロマキー撮影の配置").assertIsDisplayed()
        compose
            .onNodeWithTag("ck-svg")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(alt)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        // Full width of the 320dp column minus paddings, aspect 360:300 kept.
        val bounds = compose.onNodeWithTag("ck-svg").fetchSemanticsNode().boundsInRoot
        assertEquals(360f / 300f, bounds.width / bounds.height, 0.02f)
        compose.onNodeWithText("一部の図解を表示できません。", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("ck-svg").performClick()
        compose.onNodeWithTag("ck-svg-viewer").assertIsDisplayed()
        compose.onNodeWithContentDescription("閉じる").performClick()
        compose.onNodeWithTag("ck-svg-viewer").assertDoesNotExist()
        capture("native-svg")
    }

    @Test fun svgWithADisallowedElementShowsTheSharedFailureNotice() {
        source.value =
            "```hermes-ui-v2\n" +
            NativeUiGalleryTest.SVG_FIXTURE.replace("<circle cx=\\\"180\\\"", "<script/><circle cx=\\\"180\\\"") +
            "\n```"
        show()
        compose.onNodeWithTag("ck-svg").assertDoesNotExist()
        compose.onNodeWithText("一部の図解を表示できません。", substring = true).assertIsDisplayed()
    }

    @Test fun actionButtonIsDisabledWhileTheHostCannotSend() {
        canSubmit.value = false
        show()
        compose
            .onNodeWithTag("plan-discuss")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        canSubmit.value = true
        compose.onNodeWithTag("plan-discuss").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun modelActionLabelStaysOnOneLine() {
        source.value =
            "```hermes-ui-v2\n" +
            """{"v":1,"id":"plan","type":"savings","title":"積立","action":"この条件で最小サイズを検討"}""" +
            "\n```"
        show(dark = false, scale = 1.5f)
        val button = compose.onNodeWithTag("plan-discuss").performScrollTo()
        button.assertTextEquals("この条件で最小サイズを検討")
        val text = compose.onNodeWithText("この条件で最小サイズを検討", useUnmergedTree = true).fetchSemanticsNode()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val lineHeight = with(compose.density) { (14.sp * 1.5f).toPx() }
        // Full width of the 320dp column minus paddings (264dp), and a single text line inside the pill.
        assertTrue(bounds.width > with(compose.density) { 250.dp.toPx() })
        assertTrue(text.boundsInRoot.height < lineHeight * 1.9f)
        button.performClick()
        compose.runOnIdle { assertTrue(sent.single().startsWith("この条件で最小サイズを検討\n「積立」\n")) }
    }

    @Test fun savedInputSurvivesDisposalAndRestoredResponse() {
        show()
        compose.onNodeWithTag("plan-monthly").performTouchInput {
            swipe(Offset(width * .3f, centerY), Offset(width * .6f, centerY))
        }
        val changed = label()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5000) { label() == changed }
    }

    @Test fun narrowLargeTextHasReachableControlsResultsAndReset() {
        show(dark = false, scale = 1.5f)
        compose.onNodeWithTag("plan-years").performScrollTo().assertIsDisplayed()
        // Reset appears only once inputs differ from the answer's initial values.
        compose.onNodeWithTag("plan-reset").assertDoesNotExist()
        val initial = label()
        compose.onNodeWithTag("plan-monthly").performScrollTo().performTouchInput {
            swipe(Offset(width * .3f, centerY), Offset(width * .7f, centerY))
        }
        assertNotEquals(initial, label())
        compose.onNodeWithTag("plan-reset").performScrollTo().performClick()
        assertEquals(initial, label())
        compose.onNodeWithTag("plan-reset").assertDoesNotExist()
        compose.onNodeWithTag("plan-total").performScrollTo().assertIsDisplayed()
        capture("native-large-text-light")
        compose.onNodeWithTag("plan-discuss").performScrollTo().assertIsDisplayed()
    }

    @Test fun chartSelectionAndSeriesTogglesAreNativeAndAccessible() {
        source.value =
            "```hermes-ui-v2\n" +
            """{"v":1,"id":"g","type":"chart","title":"資産推移","unit":"万円",""" +
            """"labels":["開始","終了"],"series":[{"name":"資産","values":[0,100]},{"name":"元本","values":[0,80]}]}"""
        show()
        compose.onNodeWithTag("g-selection").assertTextContains("終了", substring = true)
        // The separate position slider is gone: scrub the plot itself.
        compose.onNodeWithTag("g-position").assertDoesNotExist()
        compose.onNodeWithTag("g-plot").performTouchInput {
            swipe(Offset(width * .95f, centerY), Offset(width * .05f, centerY))
        }
        compose.onNodeWithTag("g-selection").assertTextContains("開始", substring = true)
        // TalkBack adjusts the same position through the plot's progress semantics.
        compose.onNodeWithTag("g-plot").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        compose.onNodeWithTag("g-plot").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("g-selection").assertTextContains("終了", substring = true)
        compose.onNodeWithTag("g-selection").assertTextContains("元本 80万円", substring = true)
        compose.onNodeWithText("元本").performClick()
        // Hidden series leave the summary; the last visible series cannot be hidden.
        compose.onNodeWithText("非表示").assertIsDisplayed()
        assertFalse(textAt("g-selection").contains("元本"))
        compose.onNodeWithText("資産").performClick()
        assertTrue(textAt("g-selection").contains("資産"))
        assertFalse(historyOpened)
        capture("native-chart")
    }

    @Test fun comparisonSupportsRowSelectionAndPreservesOtherProse() {
        source.value =
            "説明は本文に残ります。\n```hermes-ui-v2\n" +
            """{"v":1,"id":"c","type":"comparison","title":"候補比較",""" +
            """"columns":["候補","利点"],"rows":[["A","軽い"],["B","大きい"]]}""" +
            "\n```\n続きの説明。"
        show(dark = false)
        compose.onNodeWithText("説明は本文に残ります。").assertIsDisplayed()
        compose.onAllNodesWithText("A").onFirst().performClick()
        // Selection emphasizes the row instead of filtering: every row stays visible.
        compose.onAllNodesWithText("A").onFirst().assertIsSelected()
        compose.onNodeWithText("大きい", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("続きの説明。").assertIsDisplayed()
        compose
            .onNodeWithTag("c-discuss")
            .performScrollTo()
            .assertTextEquals(NativeActionLabels.TABLE)
            .performClick()
        compose.runOnIdle {
            assertEquals("この行で相談\n「候補比較」\n候補: A\n利点: 軽い", sent.single())
        }
        capture("native-comparison")
    }

    @Test fun badTrailingUpdateKeepsCompletedUiAndShowsFailure() {
        source.value += "\n{\"v\":99}\n```"
        show()
        compose.onNodeWithTag("plan-monthly").assertIsDisplayed()
        compose.onNodeWithText("一部の図解を表示できません。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun interruptedOpenFenceKeepsControlsWithoutClaimingGenerationIsStillRunning() {
        streaming.value = false
        show()
        compose.onNodeWithTag("plan-monthly").assertIsDisplayed()
        compose.onNodeWithText("図解の生成が途中で終了しました。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("続きを生成中…").assertDoesNotExist()
    }

    @Test fun rawTouchUpdatesDrawBeforeReleaseWithMeasuredLatency() {
        show()
        val bounds = compose.onNodeWithTag("plan-monthly").fetchSemanticsNode().boundsInWindow
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val marker = AtomicLong(0)
        val samples = CopyOnWriteArrayList<Double>()
        val listener =
            ViewTreeObserver.OnDrawListener {
                val start = marker.getAndSet(0)
                if (start > 0) samples += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            }
        compose.runOnIdle {
            compose.activity.window.decorView.viewTreeObserver
                .addOnDrawListener(listener)
        }
        val down = SystemClock.uptimeMillis()

        fun inject(
            action: Int,
            fraction: Float,
        ) {
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.left + bounds.width * fraction,
                    bounds.center.y,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, .3f)
        val values = mutableListOf<String>()
        try {
            repeat(12) { step ->
                val count = samples.size
                marker.set(SystemClock.elapsedRealtimeNanos())
                inject(MotionEvent.ACTION_MOVE, .35f + step * .04f)
                compose.waitUntil(3000) { samples.size > count }
                values += label()
            }
            assertTrue(values.distinct().size >= 10)
            assertFalse(historyOpened)
        } finally {
            inject(MotionEvent.ACTION_UP, .8f)
            compose.runOnIdle {
                compose.activity.window.decorView.viewTreeObserver
                    .removeOnDrawListener(listener)
            }
            val sorted = samples.sorted()
            val p95 = sorted.getOrNull((sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex))
            val dir = File(compose.activity.getExternalFilesDir(null), "native-ui").apply { mkdirs() }
            File(
                dir,
                "touch-latency.txt",
            ).writeText("injected-to-next-draw-ms=$samples\np95-ms=$p95\nheld-values=$values\n")
        }
    }

    @Test fun fourSeriesBarChartSupportsNegativeValuesAndVisibleEndpoints() {
        source.value = "```hermes-ui-v2\n" +
            """{"v":1,"id":"bars","type":"chart","style":"bar","title":"4系列比較","labels":["開始","終了"],"series":[""" +
            """{"name":"A","values":[-10,20]},{"name":"B","values":[-5,25]},""" +
            """{"name":"C","values":[0,30]},{"name":"D","values":[5,35]}]}"""
        show(dark = false)
        compose.onNodeWithTag("bars-plot").assertIsDisplayed()
        capture("native-four-series-bars")
    }

    @Test fun verticalSwipeOnChartScrollsTheConversationInsteadOfTrappingTheFinger() {
        show()
        compose.onNodeWithTag("plan-growth-plot").performScrollTo()
        val before =
            compose
                .onNodeWithTag("plan-growth-plot")
                .fetchSemanticsNode()
                .boundsInWindow.top
        compose.onNodeWithTag("plan-growth-plot").performTouchInput {
            swipe(Offset(centerX, height * .8f), Offset(centerX, height * .2f), 350)
        }
        val after =
            compose
                .onNodeWithTag("plan-growth-plot")
                .fetchSemanticsNode()
                .boundsInWindow.top
        assertTrue("Vertical movement on a chart must remain available to the outer scroll", after < before - 20)
        assertFalse(historyOpened)
    }

    @Test fun comparisonKeepsSelectedCandidateWhenLaterRowsAreReordered() {
        val initial =
            """{"v":1,"id":"c","type":"comparison","title":"候補比較",""" +
                """"columns":["候補","利点"],"rows":[["A","軽い"],["B","大きい"]]}"""
        source.value = "```hermes-ui-v2\n$initial\n"
        show()
        compose.onAllNodesWithText("A").onFirst().performClick()
        compose.runOnIdle {
            source.value +=
                initial
                    .replace("\"id\":\"c\"", "\"id\":\"c\",\"rev\":1")
                    .replace("[[\"A\",\"軽い\"],[\"B\",\"大きい\"]]", "[[\"B\",\"大きい\"],[\"A\",\"さらに軽い\"]]")
        }
        compose.onNodeWithText("さらに軽い", useUnmergedTree = true).assertIsDisplayed()
        // A stays selected by its first-column value after B moved above it.
        compose.onAllNodesWithText("A").onFirst().assertIsSelected()
        compose.onAllNodesWithText("B").onFirst().assertIsNotSelected()
        compose.onNodeWithTag("c-discuss").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("この行で相談\n「候補比較」\n候補: A\n利点: さらに軽い", sent.single()) }
    }

    private fun fence(vararg lines: String) = "```hermes-ui-v2\n" + lines.joinToString("\n") + "\n"

    @Test fun everyNewComponentRendersNatively() {
        source.value = fence(
            *NativeUiGalleryTest.FIXTURES
                .drop(4)
                .map { it.second }
                .toTypedArray(),
        ) + "```"
        show()
        listOf("今月の家計", "支出の内訳", "志望企業の平均年収", "月別の支出", "体重の推移", "内定先の比較", "面接対策の進め方", "ES提出までの手順", "面接の持ち物", "選考スケジュール")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("一部の図解を表示できません。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("18.2万円", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("▲1.4万円 先月比", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("おすすめ", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("いまここ", substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("◎ 週3", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun timelineCountsDaysFromTheDeviceClock() {
        source.value = fence(NativeUiGalleryTest.FIXTURES.first { it.first == "timeline" }.second)
        show()
        // Fixed clock: 2026-10-23 in Tokyo. The model never wrote "あと2日" or today's line.
        compose.onNodeWithText("あと2日", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("あと10日", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("今日 10/23", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("✓ 完了", useUnmergedTree = true).assertIsDisplayed()
        val today =
            compose
                .onNodeWithTag("timeline-today")
                .fetchSemanticsNode()
                .boundsInWindow.top
        val past =
            compose
                .onNodeWithText("ES提出", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInWindow.top
        val next =
            compose
                .onNodeWithText("Webテスト締切", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInWindow.top
        assertTrue(past < today && today < next)
    }

    @Test fun checklistKeepsChecksAcrossRevisionsAndDisposal() {
        val v1 =
            """{"v":1,"id":"cl","type":"checklist","title":"持ち物","items":[""" +
                """{"key":"cv","label":"履歴書"},{"key":"id","label":"学生証"}]}"""
        source.value = fence(v1)
        show()
        compose.onNodeWithTag("cl-progress").assertTextContains("0 / 2")
        compose.onNodeWithTag("cl-item-cv").performClick().assertIsOn()
        compose.onNodeWithTag("cl-progress").assertTextContains("1 / 2")
        // A later revision adds and reorders items; the checked key stays checked.
        compose.runOnIdle {
            source.value +=
                """{"v":1,"id":"cl","rev":2,"type":"checklist","title":"持ち物(更新)","items":[""" +
                """{"key":"pen","label":"筆記用具"},{"key":"id","label":"学生証"},{"key":"cv","label":"履歴書(写真付き)"}]}""" + "\n"
        }
        compose.onNodeWithText("持ち物(更新)").assertIsDisplayed()
        compose.onNodeWithTag("cl-item-cv").assertIsOn()
        compose.onNodeWithTag("cl-item-pen").assertIsOff()
        compose.onNodeWithTag("cl-progress").assertTextContains("1 / 3")
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("cl-item-cv").assertIsOn() }.isSuccess }
        compose.onNodeWithTag("cl-progress").assertTextContains("1 / 3")
    }

    @Test fun choicesAndTableSelectionSendTheSelectedOption() {
        source.value =
            fence(
                NativeUiGalleryTest.FIXTURES.first { it.first == "choices" }.second,
                NativeUiGalleryTest.FIXTURES.first { it.first == "table-narrow" }.second,
            )
        show()
        compose.onNodeWithTag("ch-discuss").assertDoesNotExist()
        compose.onNodeWithTag("ch-option-b").performClick().assertIsSelected()
        compose.onNodeWithTag("ch-option-a").assertIsNotSelected()
        compose
            .onNodeWithTag(
                "ch-option-a",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose
            .onNodeWithTag("ch-discuss")
            .performScrollTo()
            .assertTextEquals(NativeActionLabels.CHOICES)
            .performClick()
        compose.runOnIdle {
            assertTrue(sent.single().startsWith("これで進める\n「面接対策の進め方」\n選択: B. 想定問答を書き出す"))
            assertTrue(sent.single().contains("懸念: 話す練習にならない"))
            // The reply finished; the next component can send again.
            canSubmit.value = true
        }
        compose
            .onAllNodesWithText("標準")
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("tn-discuss").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, sent.size)
            assertEquals("この行で相談\n「候補」\n候補: 標準\n月額: 1,000円\n評価: 普通", sent[1])
        }
    }

    @Test fun tableSortsNumericColumnsFromTheHeader() {
        source.value =
            fence(
                """{"v":1,"id":"t","type":"table","title":"表","columns":[{"name":"名前"},{"name":"点"}],""" +
                    """"rows":[["B",{"num":2}],["A",{"num":1}],["C",{"num":3}]]}""",
            )
        show()

        fun order() =
            listOf("A", "B", "C").sortedBy {
                compose
                    .onAllNodesWithText(it)
                    .onFirst()
                    .fetchSemanticsNode()
                    .boundsInWindow.top
            }
        assertEquals(listOf("B", "A", "C"), order())
        compose.onNodeWithText("点").performClick()
        assertEquals(listOf("A", "B", "C"), order())
        compose.onNodeWithText("点 ▲").performClick()
        assertEquals(listOf("C", "B", "A"), order())
        compose.onNodeWithText("点 ▼").performClick()
        assertEquals(listOf("B", "A", "C"), order())
    }

    @Test fun hbarAndDonutSelectByTap() {
        source.value =
            fence(
                NativeUiGalleryTest.FIXTURES.first { it.first == "hbar" }.second,
                NativeUiGalleryTest.FIXTURES.first { it.first == "donut" }.second,
            )
        show()
        compose.onNodeWithTag("hb-selection").assertDoesNotExist()
        compose.onNodeWithText("ベータ商事").performClick()
        compose.onNodeWithTag("hb-selection").assertTextContains("ベータ商事、年収 745万円")
        compose.onNodeWithTag("dn-selection").performScrollTo().assertTextContains("合計", substring = true)
        compose.onNodeWithTag("dn-plot").performTouchInput {
            // Right side of the ring (3 o'clock): within 住居's first 38% of the circle.
            click(Offset(width * .88f, height * .5f))
        }
        compose.onNodeWithTag("dn-selection").assertTextContains("住居", substring = true)
        compose.onNodeWithTag("dn-selection").assertTextContains("38%", substring = true)
        compose.onNodeWithText("食費").performClick()
        compose.onNodeWithTag("dn-selection").assertTextContains("25%", substring = true)
        assertFalse(historyOpened)
    }

    @Test fun verticalSwipeOnBarChartStillScrollsTheConversation() {
        source.value =
            "前置き\n".repeat(30) + fence(NativeUiGalleryTest.FIXTURES.first { it.first == "chart-bars4" }.second) +
            "```\n" +
            "後書き\n".repeat(30)
        show()
        compose.onNodeWithTag("bars-plot").performScrollTo()
        val before =
            compose
                .onNodeWithTag("bars-plot")
                .fetchSemanticsNode()
                .boundsInWindow.top
        compose.onNodeWithTag("bars-plot").performTouchInput {
            swipe(Offset(centerX, height * .8f), Offset(centerX, height * .2f), 350)
        }
        val after =
            compose
                .onNodeWithTag("bars-plot")
                .fetchSemanticsNode()
                .boundsInWindow.top
        assertTrue(after < before - 20)
        compose.onNodeWithTag("bars-plot").performTouchInput {
            swipe(Offset(width * .95f, centerY), Offset(width * .3f, centerY))
        }
        compose.onNodeWithTag("bars-selection").assertTextContains("開始", substring = true)
        assertFalse(historyOpened)
    }

    private fun label(): String = textAt("plan-monthly-value")

    private fun textAt(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(compose.activity.getExternalFilesDir(null), "native-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
