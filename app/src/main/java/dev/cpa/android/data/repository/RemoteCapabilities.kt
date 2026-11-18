package dev.cpa.android.data.repository

import kotlinx.serialization.json.*

data class RemoteReasoningOption(val id: String, val label: String = id)
data class RemoteModel(val id: String, val label: String = id, val supportsFast: Boolean = false,
    val reasoningLevels: List<RemoteReasoningOption> = emptyList(), val supportsImages: Boolean = false, val contextWindow: Double = 0.0)
data class RemoteDefaults(val modelId: String? = null, val reasoningLevel: String? = null,
    val speed: String = "standard", val disabledModels: Set<String> = emptySet(),
    val modelOrder: List<String> = emptyList(), val enabledReasoningLevels: Map<String, Set<String>> = emptyMap(),
    val enableAll: Boolean = true, val skillDefaultMode: String = "auto",
    val skillModes: Map<String, String> = emptyMap(), val skillTrigger: Char = '$', val contextWindows: Map<String, Double> = emptyMap()) {
    fun isSkillEnabled(name: String): Boolean = (skillModes[name]?.takeUnless { it == "default" } ?: skillDefaultMode) != "disabled"
}
data class RemoteCapabilities(val models: List<RemoteModel> = emptyList(), val defaults: RemoteDefaults = RemoteDefaults(), val defaultsLoaded: Boolean = false) {
    val availableModels: List<RemoteModel> get() {
        val enabled = if (defaults.enableAll) models else models.filter { it.id !in defaults.disabledModels }
        return enabled.sortedBy { defaults.modelOrder.indexOf(it.id).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
    }
    fun reasoningChoices(modelId: String?): List<RemoteReasoningOption> = models.find { it.id == modelId }?.reasoningLevels.orEmpty()
        .filter { !it.id.equals("ultra", true) && (defaults.enableAll || defaults.enabledReasoningLevels[modelId]?.contains(it.id) != false) }
    fun preferredModel(): RemoteModel? = availableModels.find { it.id == defaults.modelId } ?: availableModels.firstOrNull()
    fun preferredReasoning(modelId: String?): String? {
        val choices = reasoningChoices(modelId)
        return choices.find { it.id == defaults.reasoningLevel }?.id ?: choices.find { it.id == "medium" }?.id ?: choices.lastOrNull()?.id
    }
}
internal fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.strings() = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
internal fun parseRemoteModels(value: JsonElement): List<RemoteModel> = (value as? JsonArray).orEmpty().mapNotNull {
    val obj = it as? JsonObject ?: return@mapNotNull null
    val id = obj.string("id") ?: return@mapNotNull null
    RemoteModel(id, obj.string("label") ?: id, (obj["supportsFast"] as? JsonPrimitive)?.booleanOrNull == true,
        (obj["reasoningLevels"] as? JsonArray).orEmpty().mapNotNull { level ->
            val option = level as? JsonObject ?: return@mapNotNull null
            option.string("id")?.let { key -> RemoteReasoningOption(key, option.string("fallbackLabel") ?: option.string("label") ?: key) }
        }, obj["input"].strings().contains("image"), obj["contextWindow"].positiveNumber() ?: 0.0)
}
internal fun parseRemoteDefaults(value: JsonElement): RemoteDefaults {
    val settings = (value as? JsonObject)?.get("settings") as? JsonObject ?: return RemoteDefaults()
    val modelSettings = settings["modelSettings"] as? JsonObject
    val configs = modelSettings?.get("models") as? JsonObject
    val skills = settings["skills"] as? JsonObject
    return RemoteDefaults(settings.string("modelId")?.takeIf { it.isNotBlank() }, settings.string("reasoningLevel")?.takeIf { it.isNotBlank() }, settings.string("speed")?.takeIf { it.isNotBlank() } ?: "standard",
        configs.orEmpty().filterValues { ((it as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull == false }.keys,
        modelSettings?.get("modelOrder").strings(),
        configs.orEmpty().mapNotNull { (id, config) ->
            val levels = (config as? JsonObject)?.get("enabledReasoningLevels") as? JsonArray
            levels?.let { id to it.strings().toSet() }
        }.toMap(), (modelSettings?.get("enableAll") as? JsonPrimitive)?.booleanOrNull != false,
        skills?.string("defaultMode") ?: "auto",
        ((skills?.get("skills")) as? JsonObject).orEmpty().mapNotNull { (name, mode) ->
            (mode as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
        }.toMap(), skills?.string("trigger")?.singleOrNull()?.takeIf { it in "$#/" } ?: '$',
        if ((modelSettings?.get("enableAll") as? JsonPrimitive)?.booleanOrNull == false) configs.orEmpty().mapNotNull { (id, config) ->
            ((config as? JsonObject)?.get("contextWindow")).positiveNumber()?.let { id to kotlin.math.floor(it) }
        }.toMap() else emptyMap())
}

private fun JsonElement?.positiveNumber(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }

/** Mirrors iOS ThreadModel: latest positive assistant usage and configured model context, clamped to 100%. */
fun remoteContextUsage(entries: List<RemoteEntry>, sessionModelId: String?, capabilities: RemoteCapabilities): Double? {
    val model = capabilities.models.find { it.id == sessionModelId }
        ?: capabilities.models.find { it.id == capabilities.defaults.modelId } ?: return null
    val window = capabilities.defaults.contextWindows[model.id] ?: model.contextWindow
    if (!window.isFinite() || window <= 0) return null
    val used = entries.asReversed().firstNotNullOfOrNull { entry ->
        if (entry.kind == "assistant") entry.details.usage?.get("totalTokens").positiveNumber() else null
    } ?: return null
    return (used / window).coerceIn(0.0, 1.0)
}
