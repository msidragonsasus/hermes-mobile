package com.m57.hermescontrol.ui.chat

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Screenshot gallery for design review: dark/light x font 1.0/1.5 at a 360dp column. */
class NativeUiGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = mutableStateOf("")
    private val scroll = ScrollState(0)

    @Test fun darkNormal() = gallery(dark = true, scale = 1f)

    @Test fun darkLarge() = gallery(dark = true, scale = 1.5f)

    @Test fun lightNormal() = gallery(dark = false, scale = 1f)

    @Test fun lightLarge() = gallery(dark = false, scale = 1.5f)

    private fun gallery(
        dark: Boolean,
        scale: Float,
    ) {
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                    LocalNativeUiHost provides NativeUiHost("gallery-" + UUID.randomUUID()) { },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .verticalScroll(scroll)
                            .width(360.dp)
                            .padding(12.dp),
                    ) {
                        MarkdownText(
                            source.value,
                            MaterialTheme.colorScheme.onSurface,
                            isStreaming = false,
                            nativeUiIdentity = "gallery",
                        )
                    }
                }
            }
        }
        val config = (if (dark) "dark" else "light") + "-" + (if (scale > 1f) "150" else "100")
        FIXTURES.forEach { (name, line) ->
            compose.runOnIdle { source.value = "```hermes-ui-v2\n$line\n```" }
            compose.waitForIdle()
            runBlocking { scroll.scrollTo(0) }
            var page = 1
            while (true) {
                capture("$config/$name-p$page")
                val before = scroll.value
                if (before >= scroll.maxValue || page >= 4) break
                runBlocking { scroll.scrollTo((before + scroll.viewportSize * 9 / 10).coerceAtMost(scroll.maxValue)) }
                page++
            }
        }
    }

    @Test fun actionButtonsDark() = actionButtons(dark = true)

    @Test fun actionButtonsLight() = actionButtons(dark = false)

    /** Send buttons after a selection, enabled and disabled, for the design review. */
    private fun actionButtons(dark: Boolean) {
        val canSubmit = mutableStateOf(true)
        val hostScope = "gallery-" + UUID.randomUUID()
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                CompositionLocalProvider(
                    LocalNativeUiHost provides NativeUiHost(hostScope, canSubmit.value) { },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .verticalScroll(scroll)
                            .width(360.dp)
                            .padding(12.dp),
                    ) {
                        MarkdownText(
                            source.value,
                            MaterialTheme.colorScheme.onSurface,
                            isStreaming = false,
                            nativeUiIdentity = "gallery-actions",
                        )
                    }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        val savings = FIXTURES.first { it.first == "savings" }.second.dropLast(1) + ",\"action\":\"この条件で最小サイズを検討\"}"
        val cases =
            listOf(
                Triple("savings", savings, null),
                Triple("choices", FIXTURES.first { it.first == "choices" }.second, "ch-option-b"),
                Triple("table", FIXTURES.first { it.first == "table-narrow" }.second, "標準"),
            )
        cases.forEach { (name, line, select) ->
            compose.runOnIdle {
                canSubmit.value = true
                source.value = "```hermes-ui-v2\n$line\n```"
            }
            compose.waitForIdle()
            when {
                select == null -> {
                    Unit
                }

                select.startsWith("ch-") -> {
                    compose.onNodeWithTag(select).performScrollTo().performClick()
                }

                else -> {
                    compose
                        .onAllNodesWithText(select)
                        .onFirst()
                        .performScrollTo()
                        .performClick()
                }
            }
            val tag = Regex("\"id\":\"([^\"]+)\"").find(line)!!.groupValues[1] + "-discuss"
            compose.onNodeWithTag(tag).performScrollTo()
            capture("after-send/$theme-$name")
            compose.runOnIdle { canSubmit.value = false }
            compose.onNodeWithTag(tag).performScrollTo()
            capture("after-send/$theme-$name-disabled")
        }
    }

    @Test fun svgDark() = svgDiagram(dark = true)

    @Test fun svgLight() = svgDiagram(dark = false)

    /** The chroma-key layout in both themes; theme words recolor without changing the source. */
    private fun svgDiagram(dark: Boolean) {
        source.value = "```hermes-ui-v2\n$SVG_FIXTURE\n```"
        compose.setContent {
            HermesControlTheme(themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .systemBarsPadding()
                        .width(360.dp)
                        .padding(12.dp),
                ) {
                    MarkdownText(
                        source.value,
                        MaterialTheme.colorScheme.onSurface,
                        isStreaming = false,
                        nativeUiIdentity = "gallery-svg",
                    )
                }
            }
        }
        compose.onNodeWithTag("ck-svg").assertExists()
        capture("after-svg/${if (dark) "dark" else "light"}-chroma-key")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Window capture without UiAutomation, so a resident automation helper cannot block it.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(compose.activity.getExternalFilesDir(null), "native-ui/gallery/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    companion object {
        /** Chroma-key studio layout: green screen, dashed safe frame, subject, camera and labels. */
        val SVG_FIXTURE: String =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("v", kotlinx.serialization.json.JsonPrimitive(1))
                    put("id", kotlinx.serialization.json.JsonPrimitive("ck"))
                    put("type", kotlinx.serialization.json.JsonPrimitive("svg"))
                    put("title", kotlinx.serialization.json.JsonPrimitive("クロマキー撮影の配置"))
                    put("alt", kotlinx.serialization.json.JsonPrimitive("緑の背景幕の前に被写体、その手前にカメラと照明2灯を置いた配置図"))
                    put(
                        "svg",
                        kotlinx.serialization.json.JsonPrimitive(
                            """
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 300">
                              <rect x="30" y="16" width="300" height="70" rx="6" fill="#00b140"/>
                              <text x="180" y="51" text-anchor="middle" dominant-baseline="middle" font-size="15" font-weight="bold" fill="#ffffff">グリーンバック</text>
                              <rect x="70" y="100" width="220" height="110" rx="8" fill="none" stroke="muted" stroke-width="1.5" stroke-dasharray="7 5"/>
                              <text x="78" y="116" font-size="11" fill="muted">撮影範囲</text>
                              <circle cx="180" cy="140" r="13" fill="accent"/>
                              <rect x="166" y="156" width="28" height="36" rx="10" fill="accent"/>
                              <text x="180" y="205" text-anchor="middle" font-size="12" fill="fg">被写体</text>
                              <line x1="180" y1="86" x2="180" y2="126" stroke="line" stroke-dasharray="3 3"/>
                              <text x="188" y="110" font-size="10" fill="muted">1.5m 以上</text>
                              <g transform="translate(180 262)">
                                <rect x="-22" y="-12" width="44" height="26" rx="4" fill="fg"/>
                                <polygon points="-8,-12 8,-12 0,-26" fill="fg"/>
                                <text x="0" y="30" text-anchor="middle" font-size="12" fill="fg">カメラ</text>
                              </g>
                              <g fill="warn">
                                <circle cx="70" cy="240" r="10"/>
                                <circle cx="290" cy="240" r="10"/>
                              </g>
                              <path d="M 78 233 L 160 160" stroke="warn" stroke-width="1.5" stroke-dasharray="4 4" fill="none"/>
                              <path d="M 282 233 L 200 160" stroke="warn" stroke-width="1.5" stroke-dasharray="4 4" fill="none"/>
                              <text x="70" y="266" text-anchor="middle" font-size="11" fill="fg">照明</text>
                              <text x="290" y="266" text-anchor="middle" font-size="11" fill="fg">照明</text>
                            </svg>
                            """.trimIndent(),
                        ),
                    )
                }.toString()

        val FIXTURES =
            listOf(
                "savings" to """{"v":1,"id":"plan","type":"savings","title":"積立シミュレーター"}""",
                "comparison" to
                    """{"v":1,"id":"cmp","type":"comparison","title":"プラン比較","columns":["プラン","月額","保存量"],""" +
                    """"rows":[["ライト","500円","10GB"],["標準","1,000円","100GB"],["大容量","2,000円","1TB"]]}""",
                "chart-line" to
                    """{"v":1,"id":"g","type":"chart","title":"資産推移","unit":"万円",""" +
                    """"labels":["0年","5年","10年","15年","20年"],""" +
                    """"series":[{"name":"評価額","values":[0,205,470,800,1230]},{"name":"元本","values":[0,180,360,540,720]}]}""",
                "chart-bars4" to
                    """{"v":1,"id":"bars","type":"chart","style":"bar","title":"4系列比較","unit":"件",""" +
                    """"labels":["開始","中間","終了"],""" +
                    """"series":[{"name":"A","values":[-10,5,20]},{"name":"B","values":[-5,10,25]},""" +
                    """{"name":"C","values":[0,15,30]},{"name":"D","values":[5,20,35]}]}""",
                "stat" to
                    """{"v":1,"id":"st","type":"stat","title":"今月の家計","items":[""" +
                    """{"label":"支出","value":182000,"unit":"yen","delta":14000,"good":"down","sub":"先月比"},""" +
                    """{"label":"収入","value":240000,"unit":"yen","delta":0},""" +
                    """{"label":"貯蓄率","value":24,"unit":"pct","sub":"目標 20% 達成"},""" +
                    """{"label":"予算残り","value":38000,"unit":"yen","sub":"残り 9日"}]}""",
                "donut" to
                    """{"v":1,"id":"dn","type":"chart","style":"donut","title":"支出の内訳","unit":"yen",""" +
                    """"labels":["住居","食費","通信","交際","その他"],"series":[{"name":"支出","values":[70000,46000,12000,24000,30000]}]}""",
                "hbar" to
                    """{"v":1,"id":"hb","type":"chart","style":"hbar","title":"志望企業の平均年収","unit":"yen",""" +
                    """"labels":["株式会社アルファ","ベータ商事","ガンマ・テクノロジーズ","業界平均"],""" +
                    """"series":[{"name":"年収","values":[8120000,7450000,6900000,5600000]}],"highlight":3}""",
                "stacked" to
                    """{"v":1,"id":"sk","type":"chart","style":"stacked","title":"月別の支出","unit":"万円",""" +
                    """"labels":["7月","8月","9月","10月"],"series":[{"name":"固定費","values":[9,9,9,9.5]},""" +
                    """{"name":"変動費","values":[6,8.5,7,8.7]}],"target":17}""",
                "area" to
                    """{"v":1,"id":"ar","type":"chart","style":"area","title":"体重の推移","unit":"kg",""" +
                    """"labels":["1週","2週","3週","4週","5週","6週"],"series":[{"name":"体重","values":[68,67.4,67.1,66.5,66.2,65.8]}],"target":65}""",
                "table" to
                    """{"v":1,"id":"tb","type":"table","title":"内定先の比較",""" +
                    """"columns":[{"name":"項目"},{"name":"A社","highlight":true},""" +
                    """{"name":"B社"},{"name":"C社"}],""" +
                    """"rows":[["初任給",{"num":280000,"unit":"yen"},{"num":255000,"unit":"yen"},""" +
                    """{"num":300000,"unit":"yen"}],""" +
                    """["年休",{"num":125,"unit":"日"},{"num":120,"unit":"日"},{"num":110,"unit":"日"}],""" +
                    """["リモート",{"text":"週3","mark":"good"},"週1",{"text":"なし","mark":"bad"}],["勤務地","東京","大阪","東京"]]}""",
                "table-narrow" to
                    """{"v":1,"id":"tn","type":"table","title":"候補",""" +
                    """"columns":[{"name":"候補"},{"name":"月額"},{"name":"評価"}],""" +
                    """"rows":[["ライト",{"num":500,"unit":"yen"},{"text":"軽い","mark":"good"}],["標準",{"num":1000,"unit":"yen"},"普通"]]}""",
                "choices" to
                    """{"v":1,"id":"ch","type":"choices","title":"面接対策の進め方","options":[""" +
                    """{"key":"a","label":"A. 模擬面接を週2回","pros":["本番に近い練習ができる"],"cons":["予定調整が必要"],""" +
                    """"scores":{"効果":4,"負担":3},"recommended":true},""" +
                    """{"key":"b","label":"B. 想定問答を書き出す","pros":["一人でできる"],"cons":["話す練習にならない"]}]}""",
                "steps" to
                    """{"v":1,"id":"sp","type":"steps","title":"ES提出までの手順","items":[""" +
                    """{"label":"自己分析シートを作る","detail":"約1時間","state":"done"},""" +
                    """{"label":"志望動機の骨子","detail":"約30分","state":"now"},""" +
                    """{"label":"400字に清書","detail":"約45分","state":"todo"}]}""",
                "checklist" to
                    """{"v":1,"id":"cl","type":"checklist","title":"面接の持ち物","items":[""" +
                    """{"key":"cv","label":"履歴書(写真付き)"},{"key":"id","label":"学生証"},""" +
                    """{"key":"memo","label":"企業研究メモ"},{"key":"ic","label":"交通費(ICカード)"}]}""",
                "timeline" to
                    """{"v":1,"id":"tl","type":"timeline","title":"選考スケジュール","events":[""" +
                    """{"date":"2026-10-20","label":"ES提出","detail":"A社","kind":"done"},""" +
                    """{"date":"2026-10-25","label":"Webテスト締切","kind":"deadline"},""" +
                    """{"date":"2026-11-02T14:00","label":"一次面接","detail":"オンライン"}]}""",
                "flow" to
                    """{"v":1,"id":"fl","type":"flow","title":"役割分担","nodes":[""" +
                    """{"id":"you","label":"あなた","sub":"要望・進捗確認・承認","accent":true},""" +
                    """{"id":"hermes","label":"サーバーのHermes","sub":"要件整理・作業分割・検証・統合"},""" +
                    """{"id":"codex","label":"Codex App Server","sub":"実装担当"},""" +
                    """{"id":"claude","label":"Claude Code","sub":"別視点のレビュー等"},""" +
                    """{"id":"build","label":"テスト・Androidビルド"},{"id":"apk","label":"更新用APK"}],""" +
                    """"edges":[{"from":"you","to":"hermes","label":"Mobileから"},{"from":"hermes","to":"codex"},""" +
                    """{"from":"hermes","to":"claude"},{"from":"codex","to":"build"},""" +
                    """{"from":"claude","to":"build"},""" +
                    """{"from":"build","to":"apk"}],"note":"提案する分担で、まだ接続済みではありません。"}""",
            )
    }
}
