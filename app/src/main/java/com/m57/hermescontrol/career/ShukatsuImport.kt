package com.m57.hermescontrol.career

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class ShukatsuLoginAccount(
    val sourceId: String,
    val companySourceId: String,
    val companyName: String,
    val siteType: String,
    val accountLabel: String,
    val username: String,
)

internal data class ShukatsuImportResult(
    val uploadPayload: JsonObject,
)

internal fun shukatsuTypeLabel(type: String?): String =
    when (type) {
        "Company" -> "企業"
        "CompanyStatus" -> "選考状況"
        "CompanyAlias" -> "企業の別名"
        "CompanyMailSender" -> "メールの送信元"
        "CompanyLink" -> "企業のリンク"
        "Deadline" -> "締切"
        "Task" -> "タスク"
        "CompanyEvent" -> "予定"
        "CompanyLoginAccount" -> "マイページID"
        "Tag", "CompanyTag" -> "タグ"
        "LearnedSelectorOverride" -> "登録補助の設定"
        else -> "その他"
    }

/** Interoperable format reader. Original file and credentials never leave the device. */
object ShukatsuImport {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024

    fun readFile(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_FILE_BYTES) { "ファイルが大きすぎます。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private val allowed =
        mapOf(
            "Company" to
                setOf(
                    "name",
                    "status",
                    "interestLevel",
                    "entryRoute",
                    "notes",
                    "isImportant",
                    "isArchived",
                    "createdAt",
                    "updatedAt",
                ),
            "CompanyStatus" to setOf("companySyncId", "status", "createdAt", "updatedAt"),
            "CompanyAlias" to setOf("companySyncId", "alias", "createdAt", "updatedAt"),
            "CompanyMailSender" to setOf("companySyncId", "emailAddress", "createdAt", "updatedAt"),
            "CompanyLink" to setOf("companySyncId", "type", "label", "url", "createdAt", "updatedAt"),
            "Deadline" to
                setOf("companySyncId", "type", "title", "dueAt", "note", "completed", "createdAt", "updatedAt"),
            "Task" to
                setOf(
                    "companySyncId",
                    "title",
                    "dueAt",
                    "estimate",
                    "priority",
                    "status",
                    "note",
                    "createdAt",
                    "updatedAt",
                ),
            "CompanyEvent" to
                setOf(
                    "companySyncId",
                    "type",
                    "title",
                    "scheduledAt",
                    "endAt",
                    "note",
                    "completed",
                    "createdAt",
                    "updatedAt",
                ),
        )

    internal fun decrypt(
        file: ByteArray,
        passphrase: CharArray,
    ): ShukatsuImportResult {
        require(file.size <= MAX_FILE_BYTES) { "ファイルが大きすぎます。" }
        require(passphrase.isNotEmpty()) { "合言葉を入力してください。" }
        val content = file.toString(Charsets.UTF_8)
        require(content.substringBefore('\n') == "SHUKATSU-TRANSFER-1") { "未対応のファイル形式です。" }
        val envelope = Json.parseToJsonElement(content.substringAfter('\n')).jsonObject
        require(envelope["formatVersion"]?.jsonPrimitive?.int == 1 && envelope.text("kdf") == "Argon2id") {
            "未対応の暗号化形式です。"
        }
        val memory = envelope.getValue("memoryKb").jsonPrimitive.int
        val iterations = envelope.getValue("iterations").jsonPrimitive.int
        val parallelism = envelope.getValue("parallelism").jsonPrimitive.int
        require(memory in 8192..65536 && iterations in 1..10 && parallelism in 1..4) { "未対応の暗号化設定です。" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(envelope.text("saltBase64"))
        val nonce = decoder.decode(envelope.text("nonceBase64"))
        val ciphertext = decoder.decode(envelope.text("ciphertextBase64"))
        require(salt.size == 16 && nonce.size == 12 && ciphertext.size >= 16) { "暗号化ファイルが壊れています。" }
        val passwordBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(passwordBuffer.remaining()).also(passwordBuffer::get)
        if (passwordBuffer.hasArray()) passwordBuffer.array().fill(0)
        val key = ByteArray(32)
        try {
            val parameters =
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(memory)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .build()
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                return project(Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject, file)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            password.fill(0)
            key.fill(0)
            passphrase.fill('\u0000')
        }
    }

    internal fun project(
        bundle: JsonObject,
        file: ByteArray,
    ): ShukatsuImportResult {
        val manifest = bundle.getValue("manifest").jsonObject
        require(manifest.getValue("formatVersion").jsonPrimitive.int == 1) { "未対応の取り込み形式です。" }
        val versions = bundle.getValue("versions").jsonArray.map { it.jsonObject }
        val byId = versions.associateBy { it.text("versionId") }
        require(byId.size == versions.size && null !in byId) { "元の版に重複・欠落があります。" }
        require(
            versions.all { version ->
                (version["parentVersionIds"] as? JsonArray).orEmpty().all { it.jsonPrimitive.content in byId }
            },
        ) { "元の履歴に欠落があります。" }
        val sourceRecords = bundle.getValue("records").jsonArray.map { it.jsonObject }
        val skipped = mutableListOf<JsonObject>()
        val records =
            sourceRecords.mapNotNull { record ->
                val type = requireNotNull(record.text("entityType"))
                val version = requireNotNull(byId[record.text("currentVersionId")]) { "元の版が見つかりません。" }
                require(version.text("entityType") == type && version.text("syncId") == record.text("syncId"))
                val fields = allowed[type]
                if (fields == null) {
                    require(
                        type in
                            setOf(
                                "Tag",
                                "CompanyTag",
                                "CompanyLoginAccount",
                                "LearnedSelectorOverride",
                            ),
                    ) {
                        "未対応の項目があります。元ファイルは保持されています。"
                    }
                    skipped +=
                        buildJsonObject {
                            put("type", type)
                            put("reason", "対象外のため端末内で除外")
                        }
                    null
                } else {
                    val payload = Json.parseToJsonElement(requireNotNull(version.text("payloadJson"))).jsonObject
                    buildJsonObject {
                        put("type", type)
                        put("source_id", record.getValue("syncId"))
                        put("source_version", version.getValue("versionId"))
                        put("deleted", version["deleted"]?.jsonPrimitive?.booleanOrNull == true)
                        val filtered = payload.filterKeys { it in fields }.toMutableMap()
                        if (type == "CompanyLink") {
                            val url = runCatching { URI(payload.text("url")) }.getOrNull()
                            filtered["url"] =
                                JsonPrimitive(
                                    url
                                        ?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null }
                                        ?.let {
                                            URI(
                                                it.scheme,
                                                null,
                                                it.host,
                                                it.port,
                                                it.path,
                                                null,
                                                null,
                                            ).toString()
                                        }.orEmpty(),
                                )
                        }
                        put("data", JsonObject(filtered))
                    }
                }
            }
        return ShukatsuImportResult(
            uploadPayload =
                buildJsonObject {
                    put("operation_id", UUID.randomUUID().toString())
                    put("format_version", 1)
                    put("bundle_id", manifest.getValue("bundleId"))
                    put(
                        "file_sha256",
                        MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) },
                    )
                    put("records", JsonArray(records))
                    put("skipped", JsonArray(skipped.distinct()))
                },
        )
    }
}
