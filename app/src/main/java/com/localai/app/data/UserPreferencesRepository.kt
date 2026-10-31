package com.localai.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localai_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val SD_MODEL_NAME = stringPreferencesKey("sd_model_name")
        val LORA_NAME = stringPreferencesKey("lora_name")
        val LAST_IMAGE_PATH = stringPreferencesKey("last_image_path")
        val CF_ACCOUNT_ID = stringPreferencesKey("cf_account_id")
        val CF_API_TOKEN = stringPreferencesKey("cf_api_token")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
    }

    // ---- SD Model ----
    val sdModelNameFlow: Flow<String> = context.dataStore.data
        .map { it[Keys.SD_MODEL_NAME] ?: "" }

    suspend fun saveSdModelName(name: String) {
        context.dataStore.edit { it[Keys.SD_MODEL_NAME] = name }
    }

    suspend fun clearSdModelName() {
        context.dataStore.edit { it.remove(Keys.SD_MODEL_NAME) }
    }

    // ---- LoRA ----
    val loraNameFlow: Flow<String> = context.dataStore.data
        .map { it[Keys.LORA_NAME] ?: "" }

    suspend fun saveLoraName(name: String) {
        context.dataStore.edit { it[Keys.LORA_NAME] = name }
    }

    suspend fun clearLoraName() {
        context.dataStore.edit { it.remove(Keys.LORA_NAME) }
    }

    // ---- Last Image ----
    val lastImagePathFlow: Flow<String> = context.dataStore.data
        .map { it[Keys.LAST_IMAGE_PATH] ?: "" }

    suspend fun saveLastImagePath(path: String) {
        context.dataStore.edit { it[Keys.LAST_IMAGE_PATH] = path }
    }

    suspend fun clearLastImagePath() {
        context.dataStore.edit { it.remove(Keys.LAST_IMAGE_PATH) }
    }
    // ---- Cloudflare Workers AI credentials ----
    val cfAccountIdFlow: Flow<String> = context.dataStore.data
        .map { it[Keys.CF_ACCOUNT_ID] ?: "" }

    val cfApiTokenFlow: Flow<String> = context.dataStore.data
        .map { it[Keys.CF_API_TOKEN] ?: "" }

    suspend fun saveCfCredentials(accountId: String, token: String) {
        context.dataStore.edit {
            it[Keys.CF_ACCOUNT_ID] = accountId
            it[Keys.CF_API_TOKEN] = token
        }
    }

    suspend fun clearCfCredentials() {
        context.dataStore.edit {
            it.remove(Keys.CF_ACCOUNT_ID)
            it.remove(Keys.CF_API_TOKEN)
        }
    }
    // ---- Onboarding ----
    val onboardingSeenFlow: kotlinx.coroutines.flow.Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDING_SEEN] ?: false }

    suspend fun setOnboardingSeen(seen: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_SEEN] = seen }
    }
}