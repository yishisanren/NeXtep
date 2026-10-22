package io.github.lujinxin.nextep.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Binder reads can start the module without depending on its broadcast queue. */
class WorkspaceConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val owner = checkNotNull(context)
        if (Binder.getCallingUid() != Process.myUid() &&
            owner.checkCallingPermission("android.permission.STATUS_BAR_SERVICE") != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Workspace settings are restricted to SystemUI")
        return when (method) {
            "query" -> WorkspaceConfigReceiver.readConfig(owner)
            "set_top_apps" -> {
                val keys = extras?.getStringArrayList(WorkspaceConfigContract.EXTRA_APPS)
                    ?: throw IllegalArgumentException("Missing favorites")
                require(keys.size <= 10 && keys.all { it.length in 1..1024 && '\n' !in it })
                Bundle().apply { putBoolean("saved", SettingsRepository(owner).setFavoriteApps(keys)) }
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
