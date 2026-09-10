package com.wenxu.app.core.localization

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-visible message that is resolved only when it reaches Compose.
 *
 * Keeping resource identifiers in UI state prevents ViewModels from capturing the language that
 * happened to be active when the state was created. [Plain] is reserved for user data or messages
 * supplied by Android and external providers.
 */
sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiText

    data class Plain(val value: String) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, *args.toTypedArray())
    is UiText.Plain -> value
}
