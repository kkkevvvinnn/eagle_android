package com.eagleviewer.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** 本机筛选状态兼容：旧保存省略的 andMode 是旧默认 ALL，新状态默认 ANY。 */
object FilterPersistence {
    fun decode(saved: String): Filter {
        if (saved.isEmpty()) return Filter()
        val payload = EagleItemMeta.json.parseToJsonElement(saved).jsonObject
        val compatible = if ("andMode" in payload) payload else {
            JsonObject(payload + ("andMode" to JsonPrimitive(true)))
        }
        val decoded=EagleItemMeta.json.decodeFromJsonElement<Filter>(compatible)
        // An explicit new key (including null) wins over legacy payload.
        return decoded.copy(similarColor=null, colorCondition=if("colorCondition" in payload) decoded.colorCondition else decoded.effectiveColor)
    }

    fun encode(filter: Filter): String {
        val payload = EagleItemMeta.json.encodeToJsonElement(filter.copy(similarColor=null,colorCondition=filter.effectiveColor)).jsonObject
        // 不依赖 encodeDefaults：以后 true/false 均明确保存，消除旧默认省略歧义。
        val color=filter.effectiveColor?.let { condition ->
            JsonObject(EagleItemMeta.json.encodeToJsonElement(condition).jsonObject + ("semanticsVersion" to JsonPrimitive(1)) + ("tolerance" to JsonPrimitive(condition.tolerance)))
        } ?: JsonNull
        return JsonObject((payload - "similarColor") + ("andMode" to JsonPrimitive(filter.andMode)) + ("colorCondition" to color)).toString()
    }
}
