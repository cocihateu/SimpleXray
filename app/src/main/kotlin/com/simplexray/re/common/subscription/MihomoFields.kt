package com.simplexray.re.common.subscription

import org.json.JSONArray
import org.json.JSONObject

internal class MihomoConversionException(
    val kind: Kind,
    val location: String,
    val feature: String = "",
) : IllegalArgumentException("$kind: $location") {
    enum class Kind {
        FORMAT, REQUIRED, FIELD_TYPE, CAPABILITY, REFERENCE, DUPLICATE, GRAPH, CONFIG
    }
}

internal class MihomoFields(
    val json: JSONObject,
    val location: String,
) {
    fun allow(keys: Set<String>) {
        json.keys().asSequence().firstOrNull { it !in keys }?.let { key ->
            capability(key, key)
        }
    }

    fun has(key: String): Boolean = json.has(key) && json.isNull(key) == false

    fun text(key: String, default: String = ""): String {
        if (has(key) == false) return default
        return when (val value = json.get(key)) {
            is String -> value
            is Number -> value.toString()
            else -> typeError(key)
        }
    }

    fun requiredText(key: String): String = text(key).takeIf { it.isNotBlank() }
        ?: throw MihomoConversionException(MihomoConversionException.Kind.REQUIRED, "$location.$key")

    fun integer(key: String, default: Int): Int {
        if (has(key) == false) return default
        return text(key).toIntOrNull() ?: typeError(key)
    }

    fun boolean(key: String, default: Boolean = false): Boolean {
        if (has(key) == false) return default
        return when (val value = json.get(key)) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull() ?: typeError(key)
            else -> typeError(key)
        }
    }

    fun objectValue(key: String): MihomoFields? {
        if (has(key) == false) return null
        val value = json.optJSONObject(key) ?: typeError(key)
        return MihomoFields(value, "$location.$key")
    }

    fun array(key: String): JSONArray {
        if (has(key) == false) return JSONArray()
        return json.optJSONArray(key) ?: typeError(key)
    }

    fun strings(key: String): List<String> {
        val values = array(key)
        return (0 until values.length()).map { index ->
            values.opt(index) as? String ?: typeError("$key[$index]")
        }
    }

    fun stringMap(key: String): JSONObject {
        val fields = objectValue(key) ?: return JSONObject()
        return JSONObject().apply {
            fields.json.keys().forEach { name ->
                put(name, fields.text(name))
            }
        }
    }

    fun capability(key: String, feature: String): Nothing =
        throw MihomoConversionException(
            MihomoConversionException.Kind.CAPABILITY,
            "$location.$key",
            feature,
        )

    fun typeError(key: String): Nothing =
        throw MihomoConversionException(MihomoConversionException.Kind.FIELD_TYPE, "$location.$key")
}
