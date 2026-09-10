package com.wenxu.app.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.wenxu.app.core.localization.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wenxuSettingsDataStore by preferencesDataStore(name = "wenxu_settings")

enum class DocumentScanScope {
    ALL_DOCUMENTS,
    SELECTED_FOLDERS,
}

interface ScanPreferences {
    val scanOnLaunch: Flow<Boolean>
    val scanScope: Flow<DocumentScanScope>
    suspend fun setScanOnLaunch(enabled: Boolean)
    suspend fun setScanScope(scope: DocumentScanScope)
}

interface InboxPreferences {
    val inboxTreeUri: Flow<String?>
    suspend fun setInboxTreeUri(uri: String)
}

interface GuidePreferences {
    val hasCompletedFirstUseGuide: Flow<Boolean>
    suspend fun setFirstUseGuideCompleted(completed: Boolean)
}

interface LanguagePreferences {
    val selectedLanguage: Flow<AppLanguage?>
    suspend fun setSelectedLanguage(language: AppLanguage)
}

class DataStoreWenxuPreferences(context: Context) :
    ScanPreferences,
    InboxPreferences,
    GuidePreferences,
    LanguagePreferences {
    private val dataStore = context.applicationContext.wenxuSettingsDataStore

    override val scanOnLaunch: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SCAN_ON_LAUNCH] ?: true
    }

    override suspend fun setScanOnLaunch(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[SCAN_ON_LAUNCH] = enabled }
    }

    override val scanScope: Flow<DocumentScanScope> = dataStore.data.map { preferences ->
        preferences[SCAN_SCOPE]
            ?.let { stored -> runCatching { DocumentScanScope.valueOf(stored) }.getOrNull() }
            ?: DocumentScanScope.ALL_DOCUMENTS
    }

    override suspend fun setScanScope(scope: DocumentScanScope) {
        dataStore.edit { preferences -> preferences[SCAN_SCOPE] = scope.name }
    }

    override val inboxTreeUri: Flow<String?> = dataStore.data.map { preferences ->
        preferences[INBOX_TREE_URI]
    }

    override suspend fun setInboxTreeUri(uri: String) {
        dataStore.edit { preferences -> preferences[INBOX_TREE_URI] = uri }
    }

    override val hasCompletedFirstUseGuide: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[FIRST_USE_GUIDE_COMPLETED] ?: false
    }

    override suspend fun setFirstUseGuideCompleted(completed: Boolean) {
        dataStore.edit { preferences -> preferences[FIRST_USE_GUIDE_COMPLETED] = completed }
    }

    override val selectedLanguage: Flow<AppLanguage?> = dataStore.data.map { preferences ->
        AppLanguage.fromStoredValue(preferences[APP_LANGUAGE])
    }

    override suspend fun setSelectedLanguage(language: AppLanguage) {
        dataStore.edit { preferences -> preferences[APP_LANGUAGE] = language.name }
    }

    private companion object {
        val SCAN_ON_LAUNCH = booleanPreferencesKey("scan_on_launch")
        val SCAN_SCOPE = stringPreferencesKey("scan_scope")
        val INBOX_TREE_URI = stringPreferencesKey("inbox_tree_uri")
        val FIRST_USE_GUIDE_COMPLETED = booleanPreferencesKey("first_use_guide_completed")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }
}
