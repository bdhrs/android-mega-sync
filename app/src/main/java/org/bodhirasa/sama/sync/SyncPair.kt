package org.bodhirasa.sama.sync

data class SyncPair(
    val id: String,
    val remoteRoot: String,
    val localTreeUri: String
)

object SyncPairCodec {

    private const val FIELD_SEP = "\t"
    private const val RECORD_SEP = "\n"

    fun encode(pairs: List<SyncPair>): String =
        pairs.joinToString(RECORD_SEP) { listOf(it.id, it.remoteRoot, it.localTreeUri).joinToString(FIELD_SEP) }

    fun decode(text: String): List<SyncPair> =
        text.split(RECORD_SEP)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(FIELD_SEP)
                SyncPair(id = parts[0], remoteRoot = parts[1], localTreeUri = parts[2])
            }
}
