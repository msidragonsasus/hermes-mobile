package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Normalize provider/MCP content blocks to the same durable Markdown used by live text. */
internal fun messageContentText(content: JsonElement?): String {
    fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun imageMarkdown(
        uri: String?,
        alt: String = "Image",
    ): String? {
        val value = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Agent host paths are resolved by the gateway. Never open file/content URIs supplied by an agent.
        if (!(
                value.startsWith("https://") || value.startsWith("http://") || value.startsWith("data:image/") ||
                    value.startsWith("/") || value.startsWith("~/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value)
            )
        ) {
            return null
        }
        if (value.any { it == '\n' || it == '\r' || it == '<' || it == '>' }) return null
        val label = alt.replace(Regex("[\\[\\]\\r\\n]"), " ")
        return "![$label](<$value>)"
    }

    return when (content) {
        null -> {
            ""
        }

        is JsonPrimitive -> {
            content.content
        }

        is JsonArray -> {
            content.joinToString("\n\n") { messageContentText(it) }
        }

        is JsonObject -> {
            when (content["type"].string()) {
                "text", "input_text", "output_text" -> {
                    content["text"].string().orEmpty()
                }

                "image_url", "input_image", "output_image" -> {
                    val image = content["image_url"]
                    val uri = image.string() ?: (image as? JsonObject)?.get("url").string() ?: content["url"].string()
                    imageMarkdown(uri, content["alt"].string() ?: "Image") ?: content.toString()
                }

                "image" -> {
                    val source = content["source"] as? JsonObject
                    val mime = content["mimeType"].string() ?: source?.get("media_type").string()
                    val data = content["data"].string() ?: source?.get("data").string()
                    val uri =
                        if (mime?.startsWith("image/") == true && data != null &&
                            Regex("[A-Za-z0-9+/=\\s]+").matches(data)
                        ) {
                            "data:$mime;base64,${data.filterNot { it.isWhitespace() }}"
                        } else {
                            content["url"].string() ?: source?.get("url").string()
                        }
                    imageMarkdown(uri, content["alt"].string() ?: "Image") ?: content.toString()
                }

                else -> {
                    content.toString()
                }
            }
        }
    }
}
