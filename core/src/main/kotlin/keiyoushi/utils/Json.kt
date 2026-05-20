package keiyoushi.utils

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

val jsonInstance: Json by injectLazy()

fun String.looksLikeHtml() = trimStart().startsWith("<") || contains("<!doctype", ignoreCase = true)

/**
 * Parses JSON string into an object of type [T].
 */
inline fun <reified T> String.parseAs(json: Json = jsonInstance): T {
    if (looksLikeHtml()) throw IllegalArgumentException("Input looks like HTML, not JSON")
    return json.decodeFromString(this)
}

/**
 * Parses JSON string into an object of type [T], applying a [transform] function to the string before parsing.
 *
 * @param json The [Json] instance to use for deserialization.
 * @param transform A function to transform the original JSON string before it is parsed.
 */
inline fun <reified T> String.parseAs(json: Json = jsonInstance, transform: (String) -> String): T =
    transform(this).parseAs(json)

/**
 * Parses the response body into an object of type [T].
 */
inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T =
    use {
        val s = body.string()
        if (s.looksLikeHtml()) throw IllegalArgumentException("Response body looks like HTML, not JSON")
        json.decodeFromString(s)
    }

/**
 * Parses the response body into an object of type [T], applying a transformation to the raw JSON string before parsing.
 *
 * @param json The [Json] instance to use for parsing. Defaults to the injected instance.
 * @param transform A function to transform the JSON string before it's decoded.
 */
inline fun <reified T> Response.parseAs(json: Json = jsonInstance, transform: (String) -> String): T =
    body.string().let { if (it.looksLikeHtml()) throw IllegalArgumentException("Response body looks like HTML, not JSON") else it }.parseAs(json, transform)

/**
 * Parses a [JsonElement] into an object of type [T].
 *
 * @param json The [Json] instance to use for parsing. Defaults to the injected instance.
 */
inline fun <reified T> JsonElement.parseAs(json: Json = jsonInstance): T =
    json.decodeFromJsonElement(this)

/**
 * Serializes the object to a JSON string.
 */
inline fun <reified T> T.toJsonString(json: Json = jsonInstance): String =
    json.encodeToString(this)
