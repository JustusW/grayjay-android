package com.futo.platformplayer.subfeed

import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.fragment.mainactivity.main.SettingsFragment
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.views.fields.FieldForm
import com.futo.platformplayer.views.fields.FormField
import com.futo.platformplayer.views.fields.FormFieldButton
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The "subfeed" section in Settings. */
@Serializable
class SubfeedSettings {
    @FormField(R.string.subfeed_enabled, FieldForm.TOGGLE, R.string.subfeed_enabled_description, 0)
    var enabled: Boolean = false

    var serverUrl: String = DEFAULT_URL

    @FormField(R.string.subfeed_server, FieldForm.READONLYTEXT, -1, 1)
    val serverText: String get() = serverUrl

    @FormField(R.string.subfeed_status, FieldForm.READONLYTEXT, -1, 2)
    val statusText: String get() = StateApp.instance.contextOrNull?.let { SubfeedBridge.instance.statusText(it) } ?: "-"

    @FormField(R.string.subfeed_last_sync, FieldForm.READONLYTEXT, -1, 3)
    val lastSyncText: String get() = StateApp.instance.contextOrNull?.let {
        val at = SubfeedStore.state(it).lastSyncAt
        if (at <= 0) "never" else FORMAT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    } ?: "-"

    @FormField(R.string.subfeed_outbox, FieldForm.READONLYTEXT, -1, 4)
    val outboxText: String get() = StateApp.instance.contextOrNull?.let { SubfeedStore.outbox(it).size().toString() } ?: "-"

    @FormField(R.string.subfeed_sync_now, FieldForm.BUTTON, R.string.subfeed_sync_now_description, 5)
    @FormFieldButton(R.drawable.ic_update)
    fun syncNow() {
        Settings.instance.save()
        SubfeedBridge.instance.syncFromUser()
    }

    @FormField(R.string.subfeed_configure_server, FieldForm.BUTTON, R.string.subfeed_configure_server_description, 6)
    @FormFieldButton(R.drawable.ic_link)
    fun configureServer() {
        val context = StateApp.instance.activity ?: return
        UIDialogs.showDialog(context, R.drawable.ic_link, false,
            "subfeed server url", "Default: $DEFAULT_URL", null, serverUrl, DEFAULT_URL, 0,
            UIDialogs.Action("Cancel", {}),
            UIDialogs.Action("Reset", {
                serverUrl = DEFAULT_URL
                Settings.instance.save()
                SettingsFragment.currentView?.reloadSettings()
            }, UIDialogs.ActionStyle.ACCENT),
            UIDialogs.Action.withInput("Save", {
                val text = it?.text?.trim()
                if (!text.isNullOrBlank()) {
                    serverUrl = text
                    Settings.instance.save()
                    SettingsFragment.currentView?.reloadSettings()
                }
            }, UIDialogs.ActionStyle.PRIMARY))
    }

    @FormField(R.string.subfeed_restore_server, FieldForm.BUTTON, R.string.subfeed_restore_server_description, 7)
    @FormFieldButton(R.drawable.ic_move_up)
    fun restoreFromServer() {
        SubfeedBridge.instance.restoreFromServerWithConfirmation()
    }

    @FormField(R.string.subfeed_restore_local, FieldForm.BUTTON, R.string.subfeed_restore_local_description, 8)
    @FormFieldButton(R.drawable.ic_move_up)
    fun restoreFromLocal() {
        SubfeedBridge.instance.restoreFromLocalWithConfirmation()
    }

    companion object {
        const val DEFAULT_URL = "http://subfeed.wingert.internal"
        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
