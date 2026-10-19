package com.launchapk.java.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement

internal fun launchRuleAllowed(rules: JsonArray?, arch: String, features: Map<String, Boolean>): Boolean {
    if (rules == null) return true
    var allowed = false
    for (element in rules) {
        val rule = element.asJsonObject
        val os = rule.getAsJsonObject("os")
        val requested = rule.getAsJsonObject("features")
        val matches = (os?.get("name")?.asString?.let { it == "linux" } ?: true) &&
            (os?.get("arch")?.asString?.let { Regex(it).matches(arch) } ?: true) &&
            (os?.get("version")?.asString?.let { Regex(it).containsMatchIn(System.getProperty("os.version").orEmpty()) } ?: true) &&
            (requested?.entrySet()?.all { (key, value) -> (features[key] ?: false) == value.asBoolean } ?: true)
        if (matches) {
            require(rule["action"].asString in setOf("allow", "disallow")) { "未知的启动规则动作" }
            allowed = rule["action"].asString == "allow"
        }
    }
    return allowed
}

internal fun argumentValues(elements: List<JsonElement>, arch: String, features: Map<String, Boolean>): List<String> = elements.flatMap { value ->
    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) listOf(value.asString) else {
        val objectValue = value.asJsonObject
        if (!launchRuleAllowed(objectValue.getAsJsonArray("rules"), arch, features)) emptyList() else {
            val argument = objectValue["value"] ?: error("启动规则缺少 value")
            if (argument.isJsonArray) argument.asJsonArray.map { it.asString } else listOf(argument.asString)
        }
    }
}

/** Parse legacy quoted argument text once; replacements remain single argv entries. No shell is used. */
internal fun legacyTokens(text: String): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quote: Char? = null
    var started = false
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        when {
            char == '\\' && index < text.length && (text[index] == quote || text[index] == '\\') -> { token.append(text[index++]); started = true }
            quote != null -> if (char == quote) quote = null else token.append(char)
            char == '"' || char == '\'' -> { quote = char; started = true }
            char.isWhitespace() -> if (started) { result += token.toString(); token.clear(); started = false }
            else -> { token.append(char); started = true }
        }
    }
    require(quote == null) { "旧版启动参数的引号未闭合" }
    if (started) result += token.toString()
    return result
}

internal fun substitute(argument: String, replacements: Map<String, String>): String {
    require(argument.length <= 65536 && '\u0000' !in argument && '\n' !in argument && '\r' !in argument) { "启动参数过长或包含控制字符" }
    val result = Regex("\\$\\{([A-Za-z0-9_]+)}").replace(argument) { match ->
        replacements[match.groupValues[1]] ?: error("未支持的启动参数变量：${match.groupValues[1]}")
    }
    require(!result.contains("\${")) { "启动参数变量格式无效" }
    return result
}
