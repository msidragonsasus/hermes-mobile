package com.m57.hermescontrol.miniapp

import android.content.res.AssetManager
import com.m57.hermescontrol.miniapp.contract.MiniAppManifest
import com.m57.hermescontrol.miniapp.contract.MiniAppManifestParser
import com.m57.hermescontrol.miniapp.contract.MiniAppManifestValidator

/** Local contract examples only. They are not installed apps and are never enabled or fetched. */
object BundledMiniAppExamples {
    val ids = listOf("career", "university", "development")

    fun load(assets: AssetManager): List<MiniAppManifest> =
        ids.map { id ->
            assets.open("miniapp-examples/$id.json").use { stream ->
                MiniAppManifestParser.parse(stream.readBytes()).also { MiniAppManifestValidator.validateMetadata(it) }
            }
        }
}
