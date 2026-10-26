package com.m57.hermescontrol.data.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class McpServersResponse(
    val servers: List<McpServer>,
)

@Serializable
data class McpServer(
    val name: String,
    val transport: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val enabled: Boolean,
    val status: String? = null,
    val error: String? = null,
    val auth: String? = null,
    @Serializable(with = McpDiscoveredToolNamesSerializer::class)
    val tools: List<String>? = null,
)

/** Structured tool configuration is not a discovered tool list. Writes use separate request models. */
internal object McpDiscoveredToolNamesSerializer : JsonTransformingSerializer<List<String>?>(
    ListSerializer(String.serializer()).nullable,
) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonNull else element
}

@Serializable
data class McpServerTestResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val tools: List<McpServerToolInfo> = emptyList(),
)

@Serializable
data class McpServerToolInfo(
    val name: String,
    val description: String? = null,
    @SerialName("schema_chars")
    val schemaChars: Int? = null,
)

@Serializable
data class McpServerToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class AddMcpServerRequest(
    val name: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val env: Map<String, String>? = null,
    val auth: String? = null, // "none" | "header" | "oauth"
    val bearerToken: String? = null, // sent only when auth == "header"
)

@Serializable
data class McpCatalogResponse(
    val entries: List<McpCatalogEntry>,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class McpCatalogEntry(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    @JsonNames("required_env")
    val env: List<McpCatalogEnvVar>? = null,
    @SerialName("auth_type")
    val authType: String? = null,
    @SerialName("post_install")
    val postInstall: String? = null,
    val installed: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class McpCatalogEnvVar(
    @JsonNames("name")
    val key: String,
    @JsonNames("prompt")
    val label: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val secret: Boolean = true,
)

@Serializable
data class McpCatalogInstallRequest(
    val name: String,
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class McpCatalogInstallResponse(
    val ok: Boolean = false,
    val name: String? = null,
    val background: Boolean = false,
    val action: String? = null,
)

@Serializable
data class McpOAuthFlowResponse(
    val flowId: String,
    val serverName: String,
    val status: String,
    val authorizationUrl: String? = null,
    val error: String? = null,
)
