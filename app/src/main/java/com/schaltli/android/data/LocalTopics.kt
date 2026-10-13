package com.schaltli.android.data

/**
 * A project's local topics (the designer's docs/device-contract.md §2.9,
 * docs/2026-10-13-local-topics.md): values this display keeps for itself.
 * Never subscribed, a write applied at once and never published, and each
 * starts at its `initial` whenever a project is loaded.
 *
 * Only a declared topic is local, whatever its name looks like, and a
 * reference to one is compared without its `#path`.
 */
class LocalTopics(topics: List<Topic>) {
    val names: Set<String> = topics.filter { it.local }.map { it.topic }.toSet()

    /** Every local topic that has an initial value, with it. */
    val initialValues: Map<String, String> =
        topics.filter { it.local && it.initial != null }.associate { it.topic to it.initial!! }

    fun isLocal(reference: String): Boolean =
        names.isNotEmpty() && splitTopicPath(reference).topic in names

    companion object {
        val NONE = LocalTopics(emptyList())
    }
}
