package com.m57.hermescontrol.home

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class HomeDetailKey(
    val scope: String,
    val item: HomeItem,
) : NavKey
