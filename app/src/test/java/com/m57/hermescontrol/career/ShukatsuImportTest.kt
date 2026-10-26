package com.m57.hermescontrol.career

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShukatsuImportTest {
    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/career/synthetic.shukatsu")).readBytes()

    @Test
    fun independentlyEncryptedFixtureDecryptsAndExcludesSecretsBeforeUpload() {
        val file = fixture()
        val original = file.copyOf()
        val passphrase = "fixture-only-日本語".toCharArray()
        val projection = ShukatsuImport.decrypt(file, passphrase)
        val result = projection.uploadPayload
        val serialized = result.toString()
        assertTrue(original.contentEquals(file))
        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(3, result.getValue("records").jsonArray.size)
        assertFalse(serialized.contains("SECRET-FIXTURE"))
        assertFalse(serialized.contains("token="))
        assertFalse(serialized.contains("mailBody"))
        assertEquals(
            "https://example.com/recruit",
            result
                .getValue("records")
                .jsonArray
                .map { it.jsonObject }
                .first { it.text("type") == "CompanyLink" }["data"]!!
                .jsonObject
                .text("url"),
        )
    }

    @Test
    fun loginIdsAreSkippedAndNeverIncludedInTheUpload() {
        val bundle =
            Json
                .parseToJsonElement(
                    """{"manifest":{"formatVersion":1,"bundleId":"bundle"},"versions":[{"versionId":"company-a-v1","entityType":"Company","syncId":"company-a","parentVersionIds":[],"payloadJson":"{\"name\":\"企業A\"}"},{"versionId":"company-b-v1","entityType":"Company","syncId":"company-b","parentVersionIds":[],"payloadJson":"{\"name\":\"企業B株式会社\"}"},{"versionId":"login-a-v1","entityType":"CompanyLoginAccount","syncId":"login-a","parentVersionIds":[],"payloadJson":"{\"companySyncId\":\"company-a\",\"siteType\":\"i-web\",\"accountLabel\":\"本選考\",\"username\":\"id-a\",\"passwordStoredOnSource\":true}"},{"versionId":"login-b-v1","entityType":"CompanyLoginAccount","syncId":"login-b","parentVersionIds":[],"payloadJson":"{\"companySyncId\":\"company-b\",\"siteType\":\"AXOL\",\"accountLabel\":\"インターン\",\"username\":\"id-b\",\"passwordStoredOnSource\":true}"}],"records":[{"entityType":"Company","syncId":"company-a","currentVersionId":"company-a-v1"},{"entityType":"Company","syncId":"company-b","currentVersionId":"company-b-v1"},{"entityType":"CompanyLoginAccount","syncId":"login-a","currentVersionId":"login-a-v1"},{"entityType":"CompanyLoginAccount","syncId":"login-b","currentVersionId":"login-b-v1"}]}""",
                ).jsonObject

        val result = ShukatsuImport.project(bundle, byteArrayOf())

        assertFalse(result.uploadPayload.toString().contains("id-a"))
        assertFalse(result.uploadPayload.toString().contains("id-b"))
        val skipped = result.uploadPayload.getValue("skipped").jsonArray
        assertEquals(1, skipped.count { it.jsonObject.text("type") == "CompanyLoginAccount" })
    }

    @Test
    fun wrongPassphraseAndTamperingFailWithoutReturningAnyPayload() {
        assertThrows(Exception::class.java) { ShukatsuImport.decrypt(fixture(), "wrong".toCharArray()) }
        val tampered = fixture()
        val index = tampered.indexOfLast { it == 'A'.code.toByte() }
        tampered[index] = 'B'.code.toByte()
        assertThrows(Exception::class.java) { ShukatsuImport.decrypt(tampered, "fixture-only-日本語".toCharArray()) }
    }

    @Test
    fun unrecognizedEnvelopeAndResourceExhaustionParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ShukatsuImport.decrypt("not-a-file".toByteArray(), "test".toCharArray())
        }
        val text = fixture().toString(Charsets.UTF_8)
        val envelope = Json.parseToJsonElement(text.substringAfter('\n')).jsonObject
        for (memory in listOf(65537, 262144, Int.MAX_VALUE)) {
            val excessive = "SHUKATSU-TRANSFER-1\n" + envelope.with("memoryKb" to JsonPrimitive(memory))
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    ShukatsuImport.decrypt(excessive.toByteArray(), "test".toCharArray())
                }
            assertEquals("未対応の暗号化設定です。", failure.message)
        }
    }

    @Test
    fun missingVersionCannotBeImported() {
        val bundle =
            Json
                .parseToJsonElement(
                    """{"manifest":{"formatVersion":1},"versions":[],"records":[{"entityType":"Company","syncId":"one","currentVersionId":"missing"}]}""",
                ).jsonObject
        assertThrows(IllegalArgumentException::class.java) { ShukatsuImport.project(bundle, byteArrayOf()) }
    }

    @Test
    fun mailSenderIsProjectedForPrivateCompanyClassification() {
        val bundle =
            Json
                .parseToJsonElement(
                    """{"manifest":{"formatVersion":1,"bundleId":"fixture"},"versions":[{"versionId":"company-v1","entityType":"Company","syncId":"company-1","parentVersionIds":[],"payloadJson":"{\"name\":\"A社\"}"},{"versionId":"sender-v1","entityType":"CompanyMailSender","syncId":"sender-1","parentVersionIds":[],"payloadJson":"{\"companySyncId\":\"company-1\",\"emailAddress\":\"recruit@example.com\"}"}],"records":[{"entityType":"Company","syncId":"company-1","currentVersionId":"company-v1"},{"entityType":"CompanyMailSender","syncId":"sender-1","currentVersionId":"sender-v1"}]}""",
                ).jsonObject
        val projected = ShukatsuImport.project(bundle, byteArrayOf()).uploadPayload
        val sender = projected.getValue("records").jsonArray[1].jsonObject
        assertEquals("CompanyMailSender", sender.text("type"))
        assertEquals("recruit@example.com", sender.getValue("data").jsonObject.text("emailAddress"))
        assertFalse(projected.toString().contains("mailBody"))
    }
}
