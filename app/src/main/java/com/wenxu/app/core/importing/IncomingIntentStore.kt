package com.wenxu.app.core.importing

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IncomingIntentStore {
    private val mutablePendingUris = MutableStateFlow<List<String>>(emptyList())
    val pendingUris: StateFlow<List<String>> = mutablePendingUris.asStateFlow()

    @Suppress("DEPRECATION")
    fun accept(intent: Intent?) {
        if (intent == null) return
        val uris = buildList {
            if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::add)
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
            }
            when (intent.action) {
                Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addAll)
            }
        }.map(Uri::toString).distinct()
        if (uris.isNotEmpty()) mutablePendingUris.value = uris
    }

    fun acceptUris(uris: List<String>) {
        val normalized = uris.filter(String::isNotBlank).distinct()
        if (normalized.isNotEmpty()) mutablePendingUris.value = normalized
    }

    fun clear() { mutablePendingUris.value = emptyList() }
}
