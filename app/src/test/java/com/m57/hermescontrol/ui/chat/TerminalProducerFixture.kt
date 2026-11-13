package com.m57.hermescontrol.ui.chat

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Parent-run acceptance gate. NO synthetic fallback when actual producer inputs are absent/unfrozen. */
internal object TerminalProducerFixture {
    private fun sha(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun required(name: String): Path =
        Path.of(System.getenv(name) ?: error("Missing real-producer gate: $name"))

    fun export(
        requestId: String,
        nonce: String,
        peerId: String,
        runtimeId: String,
        targetId: String,
    ): JsonObject {
        val exporter = required("HERMES_TERMINAL_FIXTURE_EXPORTER")
        val python = required("HERMES_TERMINAL_SERVER_PYTHON")
        val source = required("HERMES_TERMINAL_SERVER_ROOT")
        val manifestPath = required("HERMES_TERMINAL_SERVER_MANIFEST")
        val manifest =
            Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject.mapValues {
                it.value.jsonPrimitive.content
            }
        require(manifest.isNotEmpty()) { "Empty frozen server input manifest" }

        fun verify() {
            manifest.forEach { (relative, digest) ->
                val target = source.resolve(relative).normalize()
                require(
                    target.startsWith(source.normalize()) && sha(target) == digest,
                ) { "Unfrozen actual producer input: $relative" }
            }
        }
        // The manifest must cover ALL Python input files, not only seven convenient producer files.
        Files.walk(source).use { paths ->
            paths
                .filter {
                    Files.isRegularFile(it) && it.toString().endsWith(".py") &&
                        !it.toString().contains("/.git/")
                }.forEach {
                    require(source.relativize(it).toString() in manifest) { "Imported source could escape freeze: $it" }
                }
        }
        verify()
        val exporterBefore = sha(exporter)
        val manifestBefore = sha(manifestPath)
        val directory =
            Files.createTempDirectory(
                Path.of(System.getProperty("java.io.tmpdir")),
                "actual-terminal-producer-",
            )
        val output = directory.resolve("fixture.json")
        val log = directory.resolve("export.log")
        val process =
            ProcessBuilder(
                python.toString(),
                exporter.toString(),
                "--request-id",
                requestId,
                "--nonce",
                nonce,
                "--peer-id",
                peerId,
                "--runtime-id",
                runtimeId,
                "--target-id",
                targetId,
                "--output",
                output.toString(),
            ).redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Actual producer fixture exceeded 90s: $log")
        }
        require(process.exitValue() == 0) { "Actual producer fixture failed: $log" }
        verify()
        require(sha(exporter) == exporterBefore && sha(manifestPath) == manifestBefore) {
            "Producer helper/manifest edited during execution"
        }
        val document = Json.parseToJsonElement(Files.readString(output)).jsonObject
        require(
            document["fixture_version"]?.jsonPrimitive?.intOrNull == 1 &&
                document["synthetic"]?.jsonPrimitive?.booleanOrNull == true,
        )
        val payload = document["frame"]!!.jsonObject["params"]!!.jsonObject["payload"]!!.jsonObject
        val receipt = payload["terminal_acceptance"]!!.jsonObject
        require(
            receipt["original_rpc_request_id"]!!.jsonPrimitive.content == requestId &&
                receipt["client_attempt_nonce"]!!.jsonPrimitive.content == nonce &&
                receipt["peer_id"]!!.jsonPrimitive.content == peerId,
        )
        document["source_sha256"]!!.jsonObject.forEach { (relative, digest) ->
            require(manifest[relative] == digest.jsonPrimitive.content)
        }
        println("ACTUAL_PRODUCER_FIXTURE=$output SHA256=${sha(output)} SERVER_MANIFEST_SHA256=$manifestBefore")
        return document
    }
}
