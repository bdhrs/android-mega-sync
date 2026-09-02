package org.bodhirasa.androidmegasync

import android.net.Uri
import android.provider.DocumentsContract

// A SAF tree URI is unreadable on screen; its document id ("primary:Vault/Notes") is
// the closest thing to the path the user picked.
fun readableLocalPath(uriString: String): String {
    if (uriString.isEmpty()) return ""
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(Uri.parse(uriString))
        docId.substringAfter(':', docId)
    }.getOrDefault(uriString)
}
