package com.github.lodestone.common

import kotlinx.serialization.json.Json

/**
 * The JSON configuration every Lodestone parser uses.
 *
 * Unknown keys are ignored on purpose: Mojang adds fields to the manifest format without notice,
 * and a launcher that refuses to parse a version because of a field it does not use would break on
 * the day of a release.
 */
val LodestoneJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}
