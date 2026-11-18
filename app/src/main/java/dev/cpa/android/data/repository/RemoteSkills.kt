package dev.cpa.android.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Locale

@Serializable
data class SkillReference(val start: Int, val name: String) {
    val length: Int get() = name.length + 1
}
data class RemoteSkill(val name: String, val description: String, val filePath: String)
data class RemoteSkillCatalog(val skills: List<RemoteSkill>, val usage: Map<String, Long>) {
    fun enabled(defaults: RemoteDefaults): List<RemoteSkill> = skills.filter { defaults.isSkillEnabled(it.name) }
        .sortedWith(compareByDescending<RemoteSkill> { usage[it.name.lowercase(Locale.ROOT)] ?: 0L }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/** Same three roots and depth-first precedence as iOS. Reads only; never follows directory symlinks. */
internal suspend fun scanRemoteSkills(projectPath: String?, maxDirectoriesPerRoot: Int = 400,
    call: suspend (String, List<JsonElement>) -> JsonElement): List<RemoteSkill> = coroutineScope {
    suspend fun optional(method: String, args: List<JsonElement>): JsonElement? = try { call(method, args) }
        catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    val info = call("native:runtimeInfo", emptyList()) as? JsonObject ?: error("Missing runtime info")
    val roots = buildList {
        info.string("userConfigDir")?.takeIf(String::isNotEmpty)?.let { add("$it/coding-professional-agent/agent/skills") }
        info.string("homeDir")?.takeIf(String::isNotEmpty)?.let {
            val config = info.string("appConfigDirName") ?: if ((info["isDebug"] as? JsonPrimitive)?.booleanOrNull == true)
                ".coding-professional-agent-dev" else ".coding-professional-agent"
            add("$it/$config/skills")
        }
        projectPath?.takeIf(String::isNotEmpty)?.let { add("$it/.cpa/skills") }
    }
    val discovered = mutableListOf<RemoteSkill>(); val names = mutableSetOf<String>(); val visited = mutableSetOf<String>()
    for (root in roots) {
        val stack = ArrayDeque<String>(); stack.addLast(root); var count = 0
        while (stack.isNotEmpty() && count < maxDirectoriesPerRoot) {
            ensureActive()
            val dir = stack.removeLast()
            if (!visited.add(dir)) continue
            count++
            val fileRead = async { optional("native:readFileIfExists", listOf(JsonPrimitive("$dir/SKILL.md"))) }
            val entriesRead = async { optional("native:readDir", listOf(JsonPrimitive(dir))) }
            val file = fileRead.await() as? JsonObject
            val encoded = file?.string("dataBase64")
            if (encoded != null) {
                val skill = runCatching { parseRemoteSkill(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8), dir) }.getOrNull()
                if (skill != null && names.add(skill.name.lowercase(Locale.ROOT))) discovered += skill
            }
            val children = (entriesRead.await() as? JsonArray).orEmpty().mapNotNull {
                val entry = it as? JsonObject ?: return@mapNotNull null
                val name = entry.string("name") ?: return@mapNotNull null
                if ((entry["isDir"] as? JsonPrimitive)?.booleanOrNull != true ||
                    (entry["isSymbolicLink"] as? JsonPrimitive)?.booleanOrNull == true ||
                    name in setOf("node_modules", ".git", ".", "..") || name.any { ch -> ch == '/' || ch == '\\' }) null else "$dir/$name"
            }
            children.asReversed().forEach(stack::addLast)
        }
    }
    discovered.toList()
}

class RemoteSkillUsage {
    private val counts = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val state = counts.asStateFlow()
    internal fun merge(id: String, incoming: Map<String, Long>) {
        if (incoming.isEmpty()) return
        counts.update { previous ->
            val latest = previous[id].orEmpty().toMutableMap()
            incoming.forEach { (name, count) -> latest[name] = maxOf(latest[name] ?: 0L, count) }
            previous + (id to latest)
        }
    }
    internal fun sent(id: String, references: List<SkillReference>) {
        if (references.isEmpty()) return
        counts.update { previous ->
            val latest = previous[id].orEmpty().toMutableMap()
            references.forEach { val key = it.name.lowercase(Locale.ROOT); latest[key] = (latest[key] ?: 0L) + 1 }
            previous + (id to latest)
        }
    }
    internal fun remove(id: String) = counts.update { it - id }
}

internal fun parseSkillUsage(metrics: JsonElement): Map<String, Long> {
    val summary = (metrics as? JsonObject)?.get("summary") as? JsonObject
    return (summary?.get("topSkills") as? JsonArray).orEmpty().mapNotNull {
        val item = it as? JsonObject ?: return@mapNotNull null
        val name = item.string("name") ?: return@mapNotNull null
        val count = (item["count"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        name.lowercase(Locale.ROOT) to count.coerceAtLeast(0)
    }.toMap()
}

internal fun parseRemoteSkill(content: String, dir: String): RemoteSkill? {
    val fields = mutableMapOf<String, String>()
    if (content.startsWith("---")) {
        val end = content.indexOf("\n---", 3)
        if (end >= 0) content.substring(3, end).lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon >= 0) {
                val value = line.substring(colon + 1).trim()
                fields[line.substring(0, colon).trim()] = if (value.length >= 2 &&
                    ((value.first() == '\'' && value.last() == '\'') || (value.first() == '"' && value.last() == '"')))
                    value.substring(1, value.lastIndex) else value
            }
        }
    }
    val name = fields["name"]?.takeIf(String::isNotEmpty) ?: dir.replace('\\', '/').trimEnd('/').substringAfterLast('/')
    return name.takeIf(String::isNotEmpty)?.let { RemoteSkill(it, fields["description"].orEmpty(), "$dir/SKILL.md") }
}

internal fun validSkillReferences(text: String, references: List<SkillReference>): List<SkillReference> {
    var end = 0
    return references.sortedBy { it.start }.filter {
        val valid = it.start >= end && it.start <= text.length - it.length && text.regionMatches(it.start, "$${it.name}", 0, it.length)
        if (valid) end = it.start + it.length
        valid
    }
}
