package org.bodhirasa.sama.sync

object Fingerprints {
    const val DIR = "DIR"

    fun of(content: ByteArray): String = "${content.size}:${content.contentHashCode()}"
}
