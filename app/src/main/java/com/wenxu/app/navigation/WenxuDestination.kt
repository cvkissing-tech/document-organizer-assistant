package com.wenxu.app.navigation

import androidx.annotation.StringRes
import com.wenxu.app.R
import com.wenxu.app.ui.components.WenxuLineIconType

data class WenxuDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: WenxuLineIconType,
)

object WenxuDestinations {
    val LanguageGate = WenxuDestination(
        "language-gate",
        R.string.destination_language_gate,
        WenxuLineIconType.BOOKMARK,
    )
    val Language = WenxuDestination(
        "language",
        R.string.destination_language,
        WenxuLineIconType.BOOKMARK,
    )
    val SettingsLanguage = WenxuDestination(
        "settings/language",
        R.string.destination_language,
        WenxuLineIconType.BOOKMARK,
    )
    val FirstUseGuide = WenxuDestination(
        "first-use-guide",
        R.string.destination_first_use_guide,
        WenxuLineIconType.BOOKMARK,
    )
    val HelpGuide = WenxuDestination(
        "settings/help",
        R.string.destination_help_guide,
        WenxuLineIconType.BOOKMARK,
    )
    val Onboarding = WenxuDestination(
        "onboarding",
        R.string.destination_onboarding,
        WenxuLineIconType.SHIELD,
    )
    val Home = WenxuDestination("home", R.string.destination_home, WenxuLineIconType.FOLDER)
    val Documents = WenxuDestination(
        "documents",
        R.string.destination_documents,
        WenxuLineIconType.FILE,
    )
    val Categories = WenxuDestination(
        "categories",
        R.string.destination_categories,
        WenxuLineIconType.TAG,
    )
    val Related = WenxuDestination(
        "related",
        R.string.destination_related,
        WenxuLineIconType.SIMILAR,
    )
    val Settings = WenxuDestination(
        "settings",
        R.string.destination_settings,
        WenxuLineIconType.SETTINGS,
    )
    val ScanSources = WenxuDestination(
        "settings/sources",
        R.string.destination_scan_sources,
        WenxuLineIconType.SCAN,
    )
    val Trash = WenxuDestination("trash", R.string.destination_trash, WenxuLineIconType.TRASH)
    val Import = WenxuDestination("import", R.string.destination_import, WenxuLineIconType.OPEN)
    const val CategoryDetailPattern = "category/{categoryId}"
    const val Unclassified = "category/unclassified"
    const val PdfReaderPattern = "pdf/{documentId}"
    const val RelatedPattern = "related?targetIds={targetIds}"

    fun categoryDetail(categoryId: Long): String = "category/$categoryId"
    fun pdfReader(documentId: Long): String = "pdf/$documentId"
    fun related(documentIds: Set<Long>): String {
        val encodedIds = documentIds.sorted().take(20).joinToString(",")
        return if (encodedIds.isBlank()) Related.route else "related?targetIds=$encodedIds"
    }

    fun parseRelatedTarget(value: String?): Set<Long> = value.orEmpty()
        .split(',')
        .mapNotNull { token -> token.toLongOrNull()?.takeIf { it > 0L } }
        .distinct()
        .take(20)
        .toSet()

    val bottomNavigation = listOf(Home, Documents, Categories)
}
