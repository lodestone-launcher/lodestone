package com.github.lodestone.domain.model.version

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One entry of a version's `arguments.game` or `arguments.jvm` list.
 *
 * The JSON shape is a union: a bare string for an unconditional argument, or an object pairing
 * `rules` with a `value` that is itself either a string or an array of strings. Modelling all three
 * as a rule list plus a value list keeps the launch-argument builder free of special cases.
 */
@Serializable(with = ArgumentSerializer::class)
data class Argument(
    val values: List<String>,
    val rules: List<Rule> = emptyList(),
) {
    fun isAllowed(environment: LaunchEnvironment): Boolean = rules.allows(environment)

    companion object {
        fun of(vararg values: String): Argument = Argument(values.toList())
    }
}

internal object ArgumentSerializer : KSerializer<Argument> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Argument")

    override fun deserialize(decoder: Decoder): Argument {
        val input = decoder as? JsonDecoder
            ?: throw UnsupportedOperationException("Arguments are only readable from JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> Argument(listOf(element.content))
            is JsonObject -> {
                val rules = element["rules"]?.let {
                    input.json.decodeFromJsonElement(ListSerializer(Rule.serializer()), it)
                }.orEmpty()
                val values = when (val value = element["value"]) {
                    is JsonPrimitive -> listOf(value.content)
                    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                    else -> emptyList()
                }
                Argument(values, rules)
            }

            else -> throw IllegalArgumentException("Unsupported argument entry: $element")
        }
    }

    override fun serialize(encoder: Encoder, value: Argument) {
        val output = encoder as? JsonEncoder
            ?: throw UnsupportedOperationException("Arguments are only writable as JSON")
        // Round-trips back to the compact form when it carries no rules, so manifests Lodestone
        // writes stay readable next to Mojang's own.
        if (value.rules.isEmpty() && value.values.size == 1) {
            output.encodeJsonElement(JsonPrimitive(value.values.single()))
            return
        }
        output.encodeJsonElement(
            buildJsonObject {
                if (value.rules.isNotEmpty()) {
                    put(
                        "rules",
                        output.json.encodeToJsonElement(
                            ListSerializer(Rule.serializer()),
                            value.rules,
                        ).jsonArray,
                    )
                }
                if (value.values.size == 1) {
                    put("value", JsonPrimitive(value.values.single()))
                } else {
                    putJsonArray("value") {
                        value.values.forEach { entry -> add(JsonPrimitive(entry)) }
                    }
                }
            },
        )
    }
}

@Serializable
data class Arguments(
    val game: List<Argument> = emptyList(),
    val jvm: List<Argument> = emptyList(),
) {
    /** Child entries append to the parent's, which is how mod loaders add their own flags. */
    operator fun plus(other: Arguments): Arguments =
        Arguments(game = game + other.game, jvm = jvm + other.jvm)
}
