package ai.closepaw.ui.settings

import ai.closepaw.llm.ModelEntry

/** Converts ModelEntry list to (id, displayName) pairs for dropdowns. */
fun catalogModelOptions(entries: List<ModelEntry>): List<Pair<String, String>> =
    entries.map { it.name to it.displayName }
