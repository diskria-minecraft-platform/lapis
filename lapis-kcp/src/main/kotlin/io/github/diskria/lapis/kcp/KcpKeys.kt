package io.github.diskria.lapis.kcp

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object KcpKeys {
    val RAW_ARGUMENTS_KEY = CompilerConfigurationKey<Map<String, String>>("lapis.rawArguments")
}
