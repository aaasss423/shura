package app.shura.host

import app.shura.source.ShuraSource
import java.util.concurrent.ConcurrentHashMap

/** Registration boundary; isolated classloader support can be supplied by a future adapter module. */
class SourceHost {
    private val sources = ConcurrentHashMap<String, ShuraSource>()
    fun register(source: ShuraSource) { require(source.id.isNotBlank()); sources[source.id] = source }
    fun find(id: String): ShuraSource? = sources[id]
    fun all(): List<ShuraSource> = sources.values.sortedBy { it.name }
    fun remove(id: String) { sources.remove(id) }
}
