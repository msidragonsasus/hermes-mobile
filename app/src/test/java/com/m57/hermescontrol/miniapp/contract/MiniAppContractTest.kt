package com.m57.hermescontrol.miniapp.contract

import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ChatScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiniAppContractTest {
    private data object DevelopmentFixture : NavKey

    // A fixture declaration, never registered in the running app.
    private val fixtureFeatures =
        NativeMiniAppFeatures(
            listOf(
                NativeMiniAppFeature(
                    "development",
                    "development.overview",
                    mapOf("development.overview" to DevelopmentFixture),
                ),
            ),
        )

    private fun bytes(id: String = "career") = File("src/main/assets/miniapp-examples/$id.json").readBytes()

    private fun manifest(id: String = "career") = MiniAppManifestParser.parse(bytes(id))

    private fun validate(value: MiniAppManifest = manifest()) =
        MiniAppManifestValidator.validate(value, fixtureFeatures, "1.0.0")

    private fun rejected(
        reason: MiniAppContractError? = null,
        block: () -> Unit,
    ) {
        val error = assertThrows(MiniAppContractException::class.java) { block() }
        if (reason != null) assertEquals(reason, error.reason)
    }

    @Test
    fun allThreeOriginalExamplesValidateAgainstTheirDeclaredContract() {
        for (id in listOf("career", "university", "development")) {
            val value = validate(manifest(id))
            assertEquals(id, value.manifest.id)
            assertEquals(value.manifest.permissions.size, value.permissions.size)
            assertArrayEquals(File("../docs/personal-ai-spec/examples/$id/manifest.json").readBytes(), bytes(id))
        }
        assertSame(DevelopmentFixture, validate(manifest("development")).nativeEntry)
        rejected(MiniAppContractError.UNKNOWN_NATIVE_FEATURE) {
            MiniAppManifestValidator.validate(manifest("development"), NativeMiniAppFeatures.builtIn, "1.0.0")
        }
    }

    @Test
    fun unknownFieldsAndSnakeCaseCannotUseNetworkDecoderLeniency() {
        val original = Json.parseToJsonElement(bytes().toString(Charsets.UTF_8)).jsonObject
        val variants =
            listOf(
                JsonObject(original + ("unknown" to JsonPrimitive("value"))),
                JsonObject((original - "schemaVersion") + ("schema_version" to JsonPrimitive("1.0"))),
                JsonObject(
                    original +
                        (
                            "publisher" to
                                JsonObject(
                                    original.getValue("publisher").jsonObject + ("unknown" to JsonPrimitive("value")),
                                )
                        ),
                ),
                JsonObject(
                    original +
                        (
                            "entry" to
                                JsonObject(
                                    original.getValue("entry").jsonObject +
                                        ("className" to JsonPrimitive("arbitrary.Class")),
                                )
                        ),
                ),
            )
        variants.forEach { rejected { MiniAppManifestParser.parse(it.toString().toByteArray(Charsets.UTF_8)) } }
    }

    @Test
    fun requiredFieldsCannotBeOmittedAndOptionalFieldsCannotBeExplicitNull() {
        val original = Json.parseToJsonElement(bytes().toString(Charsets.UTF_8)).jsonObject
        for (field in listOf("schemaVersion", "id", "name", "version", "publisher", "entry", "permissions")) {
            rejected {
                MiniAppManifestParser.parse(
                    JsonObject(original - field).toString().toByteArray(Charsets.UTF_8),
                )
            }
        }
        for (field in listOf("description", "icon", "network", "offline", "integrity")) {
            rejected(MiniAppContractError.INVALID_FIELD) {
                MiniAppManifestParser.parse(
                    JsonObject(original + (field to JsonNull)).toString().toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    @Test
    fun duplicateJsonMembersIncludingEscapedNamesAreRejectedBeforeOverwrite() {
        val original = bytes().toString(Charsets.UTF_8)
        for (prefix in listOf("{\"id\":\"duplicate\",", "{\"\\u0069d\":\"duplicate\",")) {
            rejected(MiniAppContractError.DUPLICATE) {
                MiniAppManifestParser.parse((prefix + original.drop(1)).toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun quotedNumbersBooleansAndWrongPrimitiveTypesAreNotCoerced() {
        val original = Json.parseToJsonElement(bytes().toString(Charsets.UTF_8)).jsonObject
        val offline = original.getValue("offline").jsonObject
        for ((field, value) in listOf(
            "maxAgeSeconds" to JsonPrimitive("123"),
            "allowDrafts" to JsonPrimitive("true"),
            "maxAgeSeconds" to JsonPrimitive(true),
        )) {
            rejected(MiniAppContractError.INVALID_FIELD) {
                val changed = JsonObject(original + ("offline" to JsonObject(offline + (field to value))))
                MiniAppManifestParser.parse(changed.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun byteLimitMalformedUtf8DeepNestingAndTruncatedTextFailWithRecoverableErrors() {
        rejected(MiniAppContractError.SIZE_LIMIT) {
            MiniAppManifestParser.parse(
                ByteArray(MiniAppManifestParser.MAX_BYTES + 1),
            )
        }
        rejected(MiniAppContractError.MALFORMED_JSON) { MiniAppManifestParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        val malformed =
            listOf(
                "{",
                "{\"name\":\"unterminated",
                "[,,]",
                "[".repeat(1000) + "0" + "]".repeat(1000),
                bytes().toString(Charsets.UTF_8) + " extra",
            )
        malformed.forEach { text -> rejected { MiniAppManifestParser.parse(text.toByteArray(Charsets.UTF_8)) } }
    }

    @Test
    fun rejectsInvalidIdentityLocalizationVersionsAndSchema() {
        val base = manifest()
        for (id in listOf("ab", "Career", "career/other", "career..other", "career.", "a".repeat(81))) {
            rejected { validate(base.copy(id = id)) }
        }
        for (name in listOf(emptyMap(), mapOf("JA" to "Name"), mapOf("ja" to ""), mapOf("en" to "x".repeat(241)))) {
            rejected { validate(base.copy(name = name)) }
        }
        for (version in listOf("1", "v1.0.0", "01.0.0", "1.0-dev")) rejected { validate(base.copy(version = version)) }
        rejected(MiniAppContractError.UNSUPPORTED_SCHEMA) { validate(base.copy(schemaVersion = "2.0")) }
    }

    @Test
    fun permissionsAreTypedUniqueAndUnknownValuesFailClosed() {
        assertEquals(
            MiniAppPermission.PluginApi("career", "read.scope"),
            MiniAppPermission.parse("plugin.api:career:read.scope"),
        )
        assertEquals(
            MiniAppPermission.Core(CoreMiniAppPermission.FILES_WRITE),
            MiniAppPermission.parse("hermes.files.write"),
        )
        val base = manifest()
        for (value in listOf("shell.exec", "hermes.runs.delete", "plugin.api:career:read/other", "device.all")) {
            rejected(MiniAppContractError.UNKNOWN_PERMISSION) { validate(base.copy(permissions = listOf(value))) }
        }
        rejected(MiniAppContractError.DUPLICATE) {
            validate(base.copy(permissions = listOf("hermes.runs.read", "hermes.runs.read")))
        }
    }

    @Test
    fun sduiCannotLeaveItsPluginNamespaceOrUseEncodedTraversal() {
        val base = manifest()
        val entry = base.entry as MiniAppEntry.Sdui
        val paths =
            listOf(
                "/api/plugins/other/mobile/index",
                "/api/plugins/career/mobile/../escape",
                "/api/plugins/career/mobile/%2e%2e/escape",
                "/api/plugins/career/mobile/%252e%252e/escape",
                "//evil.example/api/plugins/career/mobile/index",
                "/api/plugins/career/mobile/a\\b",
                "/api/plugins/career/mobile/index?token=synthetic",
                "/api/plugins/career/mobile/index#section",
                "file:///api/plugins/career/mobile/index",
            )
        paths.forEach { value -> rejected { validate(base.copy(entry = entry.copy(documentUrl = value))) } }
        rejected(
            MiniAppContractError.UNSUPPORTED_SCHEMA,
        ) { validate(base.copy(entry = entry.copy(componentVersion = "2.0"))) }
    }

    @Test
    fun webOriginMustBeExplicitCanonicalAndConsistentWithNetworkDeclaration() {
        val allowed = "https://allowed.example"
        val base =
            manifest().copy(
                entry = MiniAppEntry.Web("$allowed/app", listOf(allowed)),
                network = MiniAppNetwork(listOf(allowed)),
            )
        validate(base)
        for (url in listOf(
            "https://evil.example/app",
            "http://allowed.example/app",
            "https://user@allowed.example/app",
            "javascript:alert(1)",
            "content://allowed.example/app",
            "https://allowed.example.evil/app",
            "$allowed/app?token=synthetic",
            "$allowed/app#fragment",
        )) {
            rejected { validate(base.copy(entry = (base.entry as MiniAppEntry.Web).copy(url = url))) }
        }
        for (origin in listOf("$allowed/path", "https://*.example", "$allowed?x=1", "$allowed#fragment")) {
            rejected { validate(base.copy(network = MiniAppNetwork(listOf(origin)))) }
        }
        rejected(MiniAppContractError.INVALID_ORIGIN) { validate(base.copy(network = MiniAppNetwork())) }
        rejected(MiniAppContractError.DUPLICATE) {
            validate(base.copy(network = MiniAppNetwork(listOf(allowed, "$allowed:443"))))
        }
    }

    @Test
    fun remoteIconsNeedAllowedHttpsOriginAndValidIntegrityMetadata() {
        val base = manifest().copy(network = MiniAppNetwork(listOf("https://assets.example")))
        validate(base.copy(icon = MiniAppIcon.Remote("https://assets.example/icon.png", "a".repeat(64))))
        rejected { validate(base.copy(icon = MiniAppIcon.Remote("https://other.example/icon.png"))) }
        rejected { validate(base.copy(icon = MiniAppIcon.Remote("https://assets.example/icon.png", "bad"))) }
        rejected { validate(base.copy(integrity = MiniAppIntegrity(bundleSha256 = "bad"))) }
        rejected { validate(base.copy(integrity = MiniAppIntegrity(signature = ""))) }
    }

    @Test
    fun nativeEntryResolvesOnlyCompiledFeatureAndRegisteredRoute() {
        val base = manifest().copy(entry = MiniAppEntry.Native("chat", "hermes/chat"))
        val valid = MiniAppManifestValidator.validate(base, NativeMiniAppFeatures.builtIn, "1.0.0")
        assertSame(ChatScreen, valid.nativeEntry)
        for (entry in listOf(
            MiniAppEntry.Native("arbitrary.class.name"),
            MiniAppEntry.Native("chat", "hermes/unknown"),
        )) {
            rejected(MiniAppContractError.UNKNOWN_NATIVE_FEATURE) {
                MiniAppManifestValidator.validate(base.copy(entry = entry), NativeMiniAppFeatures.builtIn, "1.0.0")
            }
        }
    }

    @Test
    fun contributionIdentityPriorityRoutesOfflineAndKeywordLimitsAreEnforced() {
        val base = manifest()
        val card = base.contributions.homeCards.single()
        rejected(MiniAppContractError.DUPLICATE) {
            validate(base.copy(contributions = base.contributions.copy(composerActions = listOf(card))))
        }
        for (priority in listOf(-1001, 1001)) {
            rejected {
                validate(
                    base.copy(contributions = MiniAppContributions(homeCards = listOf(card.copy(priority = priority)))),
                )
            }
        }
        for (route in listOf("../escape", "https://evil.example", "command; execute", "career..other")) {
            rejected {
                validate(
                    base.copy(contributions = MiniAppContributions(homeCards = listOf(card.copy(route = route)))),
                )
            }
        }
        rejected { validate(base.copy(offline = MiniAppOffline(maxAgeSeconds = 2592001))) }
        rejected { validate(base.copy(offline = MiniAppOffline(maxAgeSeconds = -1))) }
        val command = base.contributions.commands.single()
        rejected(MiniAppContractError.DUPLICATE) {
            validate(
                base.copy(
                    contributions =
                        MiniAppContributions(
                            commands = listOf(command.copy(keywords = listOf("same", "same"))),
                        ),
                ),
            )
        }
    }

    @Test
    fun hostMinimumVersionRequiresKnownCompatibleVersionIncludingPrereleaseOrdering() {
        val base = manifest().copy(minHostVersion = "1.2.0")
        rejected(
            MiniAppContractError.HOST_VERSION,
        ) { MiniAppManifestValidator.validate(base, fixtureFeatures, "1.1.99") }
        rejected(
            MiniAppContractError.HOST_VERSION,
        ) { MiniAppManifestValidator.validate(base, fixtureFeatures, "1.2.0-alpha") }
        rejected { MiniAppManifestValidator.validate(base, fixtureFeatures, "1.0-dev") }
        MiniAppManifestValidator.validate(base, fixtureFeatures, "1.2.0+build")
    }

    @Test
    fun prereleaseIdentifiersAreNotSignedIntegersAndCannotContainEmptyParts() {
        val base = manifest().copy(minHostVersion = "1.2.0--1")
        rejected(MiniAppContractError.HOST_VERSION) {
            MiniAppManifestValidator.validate(base, fixtureFeatures, "1.2.0-1")
        }
        for (version in listOf("1.2.0-01", "1.2.0-alpha..1", "1.2.0+build..meta")) {
            rejected { validate(manifest().copy(version = version)) }
        }
    }

    @Test
    fun localizedTextLengthCountsUnicodeCodePoints() {
        validate(manifest().copy(name = mapOf("ja" to "🧪".repeat(240))))
        rejected { validate(manifest().copy(name = mapOf("ja" to "🧪".repeat(241)))) }
    }

    @Test
    fun declarationsStartDisabledAndDisablingRemovesEveryContributionWithoutRemovingOtherApps() {
        val apps = listOf(validate(manifest("career")), validate(manifest("university")))
        val index = MiniAppContributionIndex(apps)
        assertTrue(index.contributions().isEmpty())
        index.setEnabled("university", true)
        index.setEnabled("career", true)
        val reversed = MiniAppContributionIndex(apps.reversed())
        reversed.setEnabled("career", true)
        reversed.setEnabled("university", true)
        assertEquals(index.contributions(), reversed.contributions())
        index.setEnabled("career", false)
        assertTrue(index.contributions().isNotEmpty())
        assertTrue(index.contributions().all { it.appId == "university" })
        index.setEnabled("university", false)
        assertTrue(index.contributions().isEmpty())
        rejected(MiniAppContractError.DUPLICATE) { MiniAppContributionIndex(listOf(apps.first(), apps.first())) }
    }
}
