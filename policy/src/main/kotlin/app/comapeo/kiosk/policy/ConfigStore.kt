package app.comapeo.kiosk.policy

import android.content.Context
import android.util.Log
import java.io.File

/** Reads and writes the single [KioskConfig] document in private storage. */
class ConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    fun load(): KioskConfig? {
        if (!file.exists()) return null
        return try {
            KioskConfig.parse(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Config unreadable at ${file.path}", e)
            null
        }
    }

    /** Writes via a temp file and rename so a kill mid-write cannot truncate it. */
    fun save(config: KioskConfig) {
        val temp = File(appContext.filesDir, "$FILE_NAME.tmp")
        temp.writeText(config.encode())
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not replace ${file.path}")
        }
    }

    fun update(transform: (KioskConfig) -> KioskConfig): KioskConfig? {
        val current = load() ?: return null
        val updated = transform(current)
        save(updated)
        return updated
    }

    fun clear() {
        file.delete()
    }

    companion object {
        private const val TAG = "ConfigStore"
        private const val FILE_NAME = "kiosk-config.json"
    }
}
