package com.schaltli.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Combined topics: a yes/no the app works out itself from other values - all,
 * or any, of its conditions apply (schaltli-designer
 * docs/2026-10-07-live-values.md decisions 10-15, device contract §2.6).
 * A port of the designer's `lib/combined-topics.ts`, as the firmware's
 * CombinedTopics.cpp is; the cases are the «combined» section of
 * `live-value-vectors.json`.
 *
 * The export writes them in evaluation order. [evaluationOrder] is here anyway:
 * the shared cases give them in any order, and a hand-edited file then still
 * computes right.
 */
object CombinedTopics {

    data class Condition(val source: LiveValues.Source, val op: String, val operand: String)

    /** [any]: "any"; otherwise "all". */
    data class CombinedTopic(val id: String, val name: String, val any: Boolean, val conditions: List<Condition>)

    /** The namespace a live value's source names a combined topic with. */
    const val NAMESPACE = "combined"

    private fun combinedUses(topic: CombinedTopic): List<String> =
        topic.conditions.filter { it.source.namespace == NAMESPACE }.map { it.source.path }.distinct()

    /**
     * The topics in an order where each comes after every combined topic it
     * reads; empty, with [circular] filled (the chain, from its smallest name
     * and back to it), where none exists.
     */
    fun evaluationOrder(topics: List<CombinedTopic>, circular: MutableList<String>? = null): List<CombinedTopic> {
        val byName = topics.associateBy { it.name }
        val order = mutableListOf<CombinedTopic>()
        val state = mutableMapOf<String, Int>() // 1 visiting, 2 done
        val cycle = mutableListOf<String>()

        fun visit(name: String, path: List<String>) {
            if (cycle.isNotEmpty()) return
            val topic = byName[name] ?: return
            if (state[name] == 2) return
            if (state[name] == 1) {
                val loop = path.subList(path.indexOf(name), path.size)
                // The same cycle reads the same whichever topic the walk began at.
                var start = 0
                for (i in 1 until loop.size) if (loop[i] < loop[start]) start = i
                for (i in loop.indices) cycle += loop[(start + i) % loop.size]
                cycle += cycle.first()
                return
            }
            state[name] = 1
            for (used in combinedUses(topic)) {
                visit(used, path + name)
                if (cycle.isNotEmpty()) return
            }
            state[name] = 2
            order += topic
        }
        for (t in topics) visit(t.name, emptyList())
        if (cycle.isNotEmpty()) {
            circular?.addAll(cycle)
            return emptyList()
        }
        return order
    }

    /** One combined topic from what its conditions read: "true", "false", or null for no value yet. */
    fun evaluate(topic: CombinedTopic, read: (LiveValues.Source) -> String?): String? {
        if (topic.conditions.isEmpty()) return null
        var unknown = false
        for (condition in topic.conditions) {
            val value = read(condition.source)
            if (value == null) {
                unknown = true
                continue
            }
            val yes = LiveValues.matches(value, condition.op, condition.operand)
            if (topic.any && yes) return "true"
            if (!topic.any && !yes) return "false"
        }
        if (unknown) return null
        return if (topic.any) "false" else "true"
    }

    /** Every one's value, computed in the order given - each reading a combined topic computed before it, a topic through [topic]. */
    fun compute(ordered: List<CombinedTopic>, topic: (String) -> String?): Map<String, String?> {
        val values = mutableMapOf<String, String?>()
        val read = { source: LiveValues.Source ->
            when (source.namespace) {
                NAMESPACE -> values[source.path]
                "topic" -> topic(source.path)
                else -> null
            }
        }
        for (t in ordered) values[t.name] = evaluate(t, read)
        return values
    }

    /**
     * Every topic (bare, no `#json.path`) the combined topic [name] reads - its
     * own conditions and those of every combined topic it reads, down to the
     * designer's eight levels. What the app subscribes to.
     */
    fun inputTopics(topics: List<CombinedTopic>, name: String): List<String> {
        val out = mutableListOf<String>()
        fun collect(n: String, depth: Int) {
            // Eight levels at most (decision 14); a circular file stops here too.
            if (depth > 8) return
            for (t in topics) {
                if (t.name != n) continue
                for (c in t.conditions) {
                    when (c.source.namespace) {
                        NAMESPACE -> collect(c.source.path, depth + 1)
                        "topic" -> c.source.path.substringBefore('#').let { if (it !in out) out += it }
                    }
                }
            }
        }
        collect(name, 1)
        return out
    }

    private fun JsonElement?.str(default: String = ""): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    fun parse(json: JsonElement?): List<CombinedTopic> =
        (json as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { t ->
            CombinedTopic(
                id = t["id"].str(),
                name = t["name"].str(),
                any = t["mode"].str("all") == "any",
                conditions = (t["conditions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { c ->
                    Condition(LiveValues.parseSource(c["source"]), c["op"].str("=="), c["operand"].str())
                },
            )
        }
}
