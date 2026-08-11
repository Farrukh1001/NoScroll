package com.noscroll.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The persistent stores, kept deliberately separate.
 *
 * Separation is not tidiness here, it is a correctness requirement: deleting wellbeing history is a
 * privacy action that must happen instantly and completely, and it must not take the Instagram
 * budget or a pending protected change down with it. Different files means "delete my screen-time
 * history" can never become "reset my daily limit".
 *
 * DataStore gives atomic, transactional writes — a torn write leaves the previous file intact rather
 * than a half-parsed one.
 */
val Context.protectionStore: DataStore<Preferences> by preferencesDataStore(name = "protection_config")

val Context.pendingChangeStore: DataStore<Preferences> by preferencesDataStore(name = "pending_changes")

val Context.instagramStore: DataStore<Preferences> by preferencesDataStore(name = "instagram_budget")

val Context.notificationStore: DataStore<Preferences> by preferencesDataStore(name = "notification_quota")

val Context.filterStore: DataStore<Preferences> by preferencesDataStore(name = "filter_rules")

val Context.appPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")
