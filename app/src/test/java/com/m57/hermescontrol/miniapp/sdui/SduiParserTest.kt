package com.m57.hermescontrol.miniapp.sdui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SduiParserTest {
    private val manifest =
        SduiManifest(
            appId = "career.mypage",
            protocolVersion = 1,
            componentVersion = 1,
            releaseId = "bundled-1",
            permissions = setOf("mypage.read", "mypage.check"),
            route = "company",
        )

    private fun manifestJson(
        appId: String = "\"career.mypage\"",
        protocol: String = "1",
        component: String = "1",
        release: String = "\"bundled-1\"",
        permissions: String = "[\"mypage.read\",\"mypage.check\"]",
        route: String = "\"company\"",
        extra: String = "",
    ) = """{"app_id":$appId,"protocol_version":$protocol,"component_version":$component,""" +
        """"release_id":$release,"permissions":$permissions,"route":$route$extra}"""

    private fun screenJson(
        components: String = """[{"type":"Text","text":"本文"}]""",
        protocol: String = "1",
        component: String = "1",
        release: String = "\"bundled-1\"",
        company: String = "\"c1\"",
        profile: String = "\"default\"",
        extra: String = "",
    ) = """{"app_id":"career.mypage","protocol_version":$protocol,"component_version":$component,""" +
        """"release_id":$release,"company_id":$company,"profile":$profile,"title":"マイページ",""" +
        """"state":"ready","components":$components$extra}"""

    private fun screen(json: String): SduiParsed<SduiScreen> =
        SduiParser.parseScreen(json.toByteArray(Charsets.UTF_8), manifest, "c1", "default")

    private fun assertRejected(
        reason: SduiRejection,
        parsed: SduiParsed<*>,
    ) {
        assertEquals(SduiParsed.Rejected(reason), parsed)
    }

    private fun nested(levels: Int): String {
        // `levels` Columns inside each other, the innermost holding one Text.
        val open = """{"type":"Column","children":["""
        val close = "]}"
        return "[" + open.repeat(levels) + """{"type":"Text","text":"x"}""" + close.repeat(levels) + "]"
    }

    // ── manifest ────────────────────────────────────────────────────────

    @Test
    fun validManifestIsParsed() {
        val parsed = SduiParser.parseManifest(manifestJson().toByteArray())
        assertEquals(SduiParsed.Valid(manifest), parsed)
        assertTrue((parsed as SduiParsed.Valid).value.canCheck)
    }

    @Test
    fun readOnlyManifestCannotCheck() {
        val parsed = SduiParser.parseManifest(manifestJson(permissions = "[\"mypage.read\"]").toByteArray())
        assertFalse((parsed as SduiParsed.Valid).value.canCheck)
    }

    @Test
    fun manifestRejectsUnknownVersionsAppRoutePermissionsAndFields() {
        assertRejected(
            SduiRejection.UNSUPPORTED_VERSION,
            SduiParser.parseManifest(manifestJson(protocol = "2").toByteArray()),
        )
        assertRejected(
            SduiRejection.UNSUPPORTED_VERSION,
            SduiParser.parseManifest(manifestJson(component = "2").toByteArray()),
        )
        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            SduiParser.parseManifest(manifestJson(appId = "\"other.app\"").toByteArray()),
        )
        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            SduiParser.parseManifest(manifestJson(route = "\"home\"").toByteArray()),
        )
        assertRejected(
            SduiRejection.UNKNOWN_FIELD,
            SduiParser.parseManifest(manifestJson(permissions = "[\"mypage.read\",\"shell.run\"]").toByteArray()),
        )
        assertRejected(
            SduiRejection.PERMISSION_MISSING,
            SduiParser.parseManifest(manifestJson(permissions = "[\"mypage.check\"]").toByteArray()),
        )
        assertRejected(
            SduiRejection.UNKNOWN_FIELD,
            SduiParser.parseManifest(manifestJson(extra = ",\"url\":\"https://example.test\"").toByteArray()),
        )
    }

    @Test
    fun textualNumbersDoNotMasqueradeAsVersions() {
        assertRejected(
            SduiRejection.MALFORMED,
            SduiParser.parseManifest(manifestJson(protocol = "\"1\"").toByteArray()),
        )
        assertRejected(
            SduiRejection.MALFORMED,
            SduiParser.parseManifest(manifestJson(component = "1.0").toByteArray()),
        )
    }

    // ── screen: accept ──────────────────────────────────────────────────

    @Test
    fun everyAllowedComponentIsParsedRecursively() {
        val json =
            screenJson(
                """
                [
                  {"type":"Section","title":"状況","children":[
                    {"type":"Status","text":"確認済み","tone":"success"},
                    {"type":"KeyValue","label":"最終確認","value":"今日"},
                    {"type":"Column","children":[{"type":"Text","text":"詳細"}]}
                  ]},
                  {"type":"Button","label":"今すぐ確認","action":"mypage.check","enabled":true},
                  {"type":"Button","label":"更新","action":"refresh","enabled":false},
                  {"type":"Button","label":"設定","action":"native.settings","enabled":true},
                  {"type":"Button","label":"ブラウザ","action":"native.browser","enabled":true}
                ]
                """.trimIndent(),
            )
        val parsed = (screen(json) as SduiParsed.Valid).value
        assertEquals("マイページ", parsed.title)
        assertEquals(5, parsed.components.size)
        val section = parsed.components[0] as SduiSection
        assertEquals(
            listOf(
                SduiStatus("確認済み", SduiTone.SUCCESS),
                SduiKeyValue("最終確認", "今日"),
                SduiColumn(listOf(SduiText("詳細"))),
            ),
            section.children,
        )
        assertEquals(
            listOf(
                SduiButton("今すぐ確認", SduiAction.MYPAGE_CHECK, true),
                SduiButton("更新", SduiAction.REFRESH, false),
                SduiButton("設定", SduiAction.NATIVE_SETTINGS, true),
                SduiButton("ブラウザ", SduiAction.NATIVE_BROWSER, true),
            ),
            parsed.components.drop(1),
        )
    }

    @Test
    fun japaneseTextAndNewlinesAreKeptExactly() {
        val parsed =
            screen(screenJson("""[{"type":"Text","text":"一行目\n  二行目\r\n三行目 😀"}]""")) as SduiParsed.Valid
        assertEquals(SduiText("一行目\n  二行目\r\n三行目 😀"), parsed.value.components.single())
    }

    @Test
    fun emptyComponentListIsAValidScreen() {
        assertTrue(screen(screenJson("[]")) is SduiParsed.Valid)
    }

    // ── screen: version / identity ──────────────────────────────────────

    @Test
    fun unknownVersionsAreUnsupportedEvenWhenTheSchemaAlsoChanged() {
        assertRejected(SduiRejection.UNSUPPORTED_VERSION, screen(screenJson(protocol = "2")))
        assertRejected(SduiRejection.UNSUPPORTED_VERSION, screen(screenJson(component = "2")))
        // A future server adds fields as well as bumping the version: still "update needed".
        assertRejected(
            SduiRejection.UNSUPPORTED_VERSION,
            screen(screenJson(component = "2", extra = ",\"layout\":\"grid\"")),
        )
    }

    @Test
    fun identityMustMatchTheRequestAndTheManifest() {
        assertRejected(SduiRejection.IDENTITY_MISMATCH, screen(screenJson(company = "\"c2\"")))
        assertRejected(SduiRejection.IDENTITY_MISMATCH, screen(screenJson(profile = "\"other\"")))
        assertRejected(SduiRejection.RELEASE_MISMATCH, screen(screenJson(release = "\"bundled-2\"")))
        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            screen(screenJson().replace("career.mypage", "career.other")),
        )
    }

    // ── screen: components ──────────────────────────────────────────────

    @Test
    fun unknownComponentActionToneAndFieldsAreRejectedAsAWhole() {
        assertRejected(
            SduiRejection.UNKNOWN_COMPONENT,
            screen(screenJson("""[{"type":"Text","text":"ok"},{"type":"WebView","url":"https://example.test"}]""")),
        )
        assertRejected(
            SduiRejection.UNKNOWN_ACTION,
            screen(screenJson("""[{"type":"Button","label":"x","action":"shell.run","enabled":true}]""")),
        )
        assertRejected(
            SduiRejection.UNKNOWN_ACTION,
            screen(screenJson("""[{"type":"Button","label":"x","action":"native.browser?url=a","enabled":true}]""")),
        )
        assertRejected(
            SduiRejection.MALFORMED,
            screen(screenJson("""[{"type":"Status","text":"x","tone":"critical"}]""")),
        )
        assertRejected(
            SduiRejection.UNKNOWN_FIELD,
            screen(screenJson("""[{"type":"Text","text":"x","url":"https://example.test"}]""")),
        )
        assertRejected(
            SduiRejection.UNKNOWN_FIELD,
            screen(screenJson("""[{"type":"Button","label":"x","action":"refresh","enabled":true,"path":"/x"}]""")),
        )
        assertRejected(SduiRejection.UNKNOWN_FIELD, screen(screenJson(extra = ",\"debug\":true")))
        assertRejected(
            SduiRejection.MALFORMED,
            screen(screenJson("""[{"type":"Section","children":[]}]""")),
        )
        assertRejected(SduiRejection.MALFORMED, screen(screenJson("""[{"text":"no type"}]""")))
        assertRejected(SduiRejection.MALFORMED, screen(screenJson("""["Text"]""")))
        assertRejected(SduiRejection.MALFORMED, screen(screenJson("""[{"type":1,"text":"x"}]""")))
    }

    @Test
    fun textualBooleansDoNotMasqueradeAsEnabled() {
        for (value in listOf("\"true\"", "\"false\"", "1", "0", "null", "\"yes\"")) {
            assertRejected(
                SduiRejection.MALFORMED,
                screen(screenJson("""[{"type":"Button","label":"x","action":"refresh","enabled":$value}]""")),
            )
        }
    }

    @Test
    fun nestingIsLimitedToSix() {
        // Top-level components are depth 1, so five Columns plus the innermost Text reach depth 6.
        assertTrue(screen(screenJson(nested(5))) is SduiParsed.Valid)
        // Six Columns put their Text children at depth 7.
        assertRejected(SduiRejection.LIMIT_EXCEEDED, screen(screenJson(nested(6))))
        assertRejected(SduiRejection.LIMIT_EXCEEDED, screen(screenJson(nested(20))))
    }

    private fun texts(count: Int) = (1..count).joinToString(",", "[", "]") { """{"type":"Text","text":"$it"}""" }

    @Test
    fun nodeCountIsLimitedTo64() {
        assertTrue(screen(screenJson(texts(64))) is SduiParsed.Valid)
        assertRejected(SduiRejection.LIMIT_EXCEEDED, screen(screenJson(texts(65))))
    }

    @Test
    fun containersCountTowardsTheNodeLimit() {
        assertTrue(screen(screenJson("""[{"type":"Column","children":${texts(63)}}]""")) is SduiParsed.Valid)
        assertRejected(
            SduiRejection.LIMIT_EXCEEDED,
            screen(screenJson("""[{"type":"Column","children":${texts(64)}}]""")),
        )
    }

    @Test
    fun textLengthIsBounded() {
        val ok = "あ".repeat(2000)
        assertTrue(screen(screenJson("""[{"type":"Text","text":"$ok"}]""")) is SduiParsed.Valid)
        assertRejected(
            SduiRejection.LIMIT_EXCEEDED,
            screen(screenJson("""[{"type":"Text","text":"${ok}あ"}]""")),
        )
        assertRejected(
            SduiRejection.LIMIT_EXCEEDED,
            screen(screenJson("""[{"type":"KeyValue","label":"${"l".repeat(201)}","value":"v"}]""")),
        )
    }

    @Test
    fun controlCharactersAndUnpairedSurrogatesAreRejected() {
        assertRejected(
            SduiRejection.MALFORMED,
            screen(screenJson("""[{"type":"Text","text":"a\u0000b"}]""")),
        )
        assertRejected(
            SduiRejection.MALFORMED,
            screen(screenJson("""[{"type":"Text","text":"a\ud800b"}]""")),
        )
    }

    // ── envelope ────────────────────────────────────────────────────────

    @Test
    fun oversizeBodyIsRejectedBeforeParsing() {
        val padding = " ".repeat(SduiContract.MAX_BODY_BYTES)
        assertRejected(
            SduiRejection.TOO_LARGE,
            SduiParser.parseScreen((screenJson() + padding).toByteArray(), manifest, "c1", "default"),
        )
        assertRejected(
            SduiRejection.TOO_LARGE,
            SduiParser.parseManifest((manifestJson() + padding).toByteArray()),
        )
    }

    @Test
    fun malformedJsonInvalidUtf8AndNonObjectsAreRejected() {
        assertRejected(SduiRejection.MALFORMED, screen("not json"))
        assertRejected(SduiRejection.MALFORMED, screen("[]"))
        assertRejected(SduiRejection.MALFORMED, screen(""))
        assertRejected(SduiRejection.MALFORMED, screen("""{"app_id":"career.mypage"}"""))
        val invalidUtf8 = screenJson("""[{"type":"Text","text":"x"}]""").toByteArray() + byteArrayOf(0xC3.toByte())
        assertRejected(
            SduiRejection.MALFORMED,
            SduiParser.parseScreen(invalidUtf8, manifest, "c1", "default"),
        )
    }

    @Test
    fun hostileBracketNestingCannotOverflowTheParser() {
        val bomb = "[".repeat(20_000)
        assertRejected(
            SduiRejection.LIMIT_EXCEEDED,
            SduiParser.parseScreen(bomb.toByteArray(), manifest, "c1", "default"),
        )
        val quoted = """{"app_id":"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}"""
        assertRejected(SduiRejection.MALFORMED, screen(quoted))
    }

    // ── receipt ─────────────────────────────────────────────────────────

    private val expected = SduiReceiptExpectation("bundled-1", "c1", "default", "5d1f0f0e-7a6e-4a4d-a7a3-4d3d2a1f9b10")

    private fun receiptJson(
        protocol: String = "1",
        release: String = "bundled-1",
        company: String = "c1",
        profile: String = "default",
        operation: String = expected.operationId,
        state: String = "queued",
        runId: String = "\"run-1\"",
        extra: String = "",
    ) = """{"protocol_version":$protocol,"release_id":"$release","company_id":"$company","profile":"$profile",""" +
        """"operation_id":"$operation","state":"$state","run_id":$runId$extra}"""

    @Test
    fun receiptMustEchoTheRequestExactly() {
        val queued = SduiParser.parseReceipt(receiptJson().toByteArray(), expected) as SduiParsed.Valid
        assertEquals(SduiReceiptState.QUEUED, queued.value.state)
        assertEquals("run-1", queued.value.runId)
        val running = SduiParser.parseReceipt(receiptJson(state = "running").toByteArray(), expected)
        assertEquals(SduiReceiptState.RUNNING, (running as SduiParsed.Valid).value.state)

        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            SduiParser.parseReceipt(receiptJson(operation = "other").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            SduiParser.parseReceipt(receiptJson(company = "c2").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.IDENTITY_MISMATCH,
            SduiParser.parseReceipt(receiptJson(profile = "p2").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.RELEASE_MISMATCH,
            SduiParser.parseReceipt(receiptJson(release = "bundled-2").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.UNSUPPORTED_VERSION,
            SduiParser.parseReceipt(receiptJson(protocol = "2").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.MALFORMED,
            SduiParser.parseReceipt(receiptJson(state = "done").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.MALFORMED,
            SduiParser.parseReceipt(receiptJson(runId = "7").toByteArray(), expected),
        )
        assertRejected(
            SduiRejection.UNKNOWN_FIELD,
            SduiParser.parseReceipt(receiptJson(extra = ",\"message\":\"hi\"").toByteArray(), expected),
        )
    }
}
