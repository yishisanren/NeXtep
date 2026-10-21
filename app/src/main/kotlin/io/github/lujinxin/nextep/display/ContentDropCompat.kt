package io.github.lujinxin.nextep.display

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.view.DragEvent

/** Standard Android sharing, preserving the source's temporary URI permission. */
internal object ContentDropCompat {
    fun accepts(description: ClipDescription?): Boolean = description != null &&
        !AppDragContract.accepts(description) && (0 until description.mimeTypeCount).any {
            description.getMimeType(it).let { mime ->
                mime.startsWith("text/") || mime.startsWith("image/") ||
                    mime.startsWith("application/") || mime.startsWith("video/") || mime.startsWith("audio/")
            }
        }

    fun intent(clip: ClipData, targetPackage: String): Intent {
        require(accepts(clip.description) && clip.itemCount in 1..32) { "Unsupported drop" }
        val streams = arrayListOf<Uri>()
        val text = mutableListOf<CharSequence>()
        repeat(clip.itemCount) { index ->
            val item = clip.getItemAt(index)
            require(item.intent == null) { "Executable drag payloads are not shared" }
            item.uri?.let { uri ->
                require(uri.scheme == "content") { "Only permission-granted content URIs can be shared" }
                streams += uri
            }
            item.text?.let { text += it }
        }
        require(streams.isNotEmpty() || text.isNotEmpty()) { "Empty drop" }
        return Intent(if (streams.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            setPackage(targetPackage)
            type = if (streams.isEmpty()) "text/plain" else (0 until clip.description.mimeTypeCount)
                .map { clip.description.getMimeType(it) }.firstOrNull { !it.startsWith("text/") } ?: "*/*"
            clipData = clip
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (streams.size == 1) putExtra(Intent.EXTRA_STREAM, streams.single())
            else if (streams.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text.joinToString("\n"))
        }
    }

    fun deliver(event: DragEvent, targetPackage: String, launch: (Intent) -> Boolean): Boolean {
        val clip = event.clipData ?: return false
        val intent = runCatching { intent(clip, targetPackage) }.getOrNull() ?: return false
        var permissions: Any? = null
        val type = runCatching { Class.forName("android.view.DragAndDropPermissions") }.getOrNull() ?: return false
        return try {
            if ((0 until clip.itemCount).any { clip.getItemAt(it).uri != null }) {
                permissions = type.getDeclaredMethod("obtain", DragEvent::class.java).apply { isAccessible = true }
                    .invoke(null, event) ?: return false
                if (type.getDeclaredMethod("takeTransient").apply { isAccessible = true }.invoke(permissions) != true) return false
            }
            launch(intent)
        } catch (_: ReflectiveOperationException) { false }
        catch (_: SecurityException) { false }
        finally {
            permissions?.let { runCatching { type.getMethod("release").invoke(it) } }
        }
    }
}
