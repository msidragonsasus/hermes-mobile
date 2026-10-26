package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.ui.bots.group.GroupServerHistory
import com.m57.hermescontrol.ui.bots.group.GroupServerRequest
import com.m57.hermescontrol.ui.bots.group.GroupServerSnapshot
import com.m57.hermescontrol.ui.bots.group.GroupServerTitleRequest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Type

/** The group plugin uses camelCase, including nested saved messages. */
internal object GroupServerConverterFactory : Converter.Factory() {
    private val delegate = Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? =
        when (getRawType(type)) {
            GroupServerHistory::class.java, GroupServerSnapshot::class.java -> {
                delegate.responseBodyConverter(type, annotations, retrofit)
            }

            else -> {
                null
            }
        }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? =
        if (getRawType(type) in setOf(GroupServerRequest::class.java, GroupServerTitleRequest::class.java)) {
            delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
        } else {
            null
        }
}
