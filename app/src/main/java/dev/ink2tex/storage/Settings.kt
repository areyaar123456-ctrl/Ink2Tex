package dev.ink2tex.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore("scanner")
data class ScannerSettings(val model: String = "ovis2-q8", val autoCopy: Boolean = false, val opacity: Float = 0.94f, val dark: Boolean = false, val compact: Boolean = false, val x: Int = 16, val y: Int = 220, val fastScan: Boolean = false, val trainedWeights: Boolean = true)
class Settings(private val context: Context) {
 private val model = stringPreferencesKey("model")
 private val copy = booleanPreferencesKey("copy")
 private val alpha = floatPreferencesKey("alpha")
 private val dark = booleanPreferencesKey("dark")
 private val compact = booleanPreferencesKey("compact")
 private val fast = booleanPreferencesKey("fast_scan")
 private val trained = booleanPreferencesKey("trained_weights")
 private val x = intPreferencesKey("x")
 private val y = intPreferencesKey("y")
 val flow = context.settingsStore.data.map { ScannerSettings(it[model] ?: "ovis2-q8", it[copy] ?: false, it[alpha] ?: 0.94f, it[dark] ?: false, it[compact] ?: false, it[x] ?: 16, it[y] ?: 220, it[fast] ?: false, it[trained] ?: true) }
 suspend fun trainedWeights(value:Boolean) { context.settingsStore.edit { it[trained] = value } }
 suspend fun fastScan(value:Boolean) { context.settingsStore.edit { it[fast] = value } }
 suspend fun model(id: String) { context.settingsStore.edit { it[model] = id } }
 suspend fun copy(value: Boolean) { context.settingsStore.edit { it[copy] = value } }
 suspend fun alpha(value: Float) { context.settingsStore.edit { it[alpha] = value.coerceIn(0.4f, 1f) } }
 suspend fun dark(value: Boolean) { context.settingsStore.edit { it[dark] = value } }
 suspend fun compact(value: Boolean) { context.settingsStore.edit { it[compact] = value } }
 suspend fun position(px: Int, py: Int) { context.settingsStore.edit { it[x] = px; it[y] = py } }
}
