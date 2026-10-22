package com.wendigo.suzgec

import android.content.Context
import com.wendigo.suzgec.core.FilterRules
import com.wendigo.suzgec.core.RuleParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FilterStore(private val context: Context) {

    data class Loaded(
        val rules: FilterRules,
        val kept: Int,
        val skipped: Int,
        val sources: Int
    )

    private val dir: File by lazy {
        File(context.filesDir, "lists").apply { mkdirs() }
    }

    fun load(useBundled: Boolean, urls: Set<String>): Loaded {
        val chunks = ArrayList<Sequence<String>>()
        if (useBundled) {
            val text = try {
                context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            if (text.isNotBlank()) {
                chunks.add(text.lineSequence())
            }
        }
        for (url in urls) {
            val file = fileFor(url)
            if (file.exists()) {
                chunks.add(lines(file))
            }
        }
        val parsed = RuleParser.parse(chunks.asSequence().flatten())
        return Loaded(parsed.rules, parsed.kept, parsed.skipped, chunks.size)
    }

    fun save(url: String, text: String) {
        fileFor(url).writeText(text)
    }

    fun remove(url: String) {
        val file = fileFor(url)
        if (file.exists()) file.delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun lineCount(url: String): Int {
        val file = fileFor(url)
        if (!file.exists()) return 0
        var count = 0
        file.bufferedReader().use { reader ->
            while (reader.readLine() != null) count++
        }
        return count
    }

    fun download(url: String): String {
        val trimmed = url.trim()
        require(trimmed.startsWith("https://")) { "Liste adresi https:// ile başlamalı" }
        val request = Request.Builder().url(trimmed).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw IllegalStateException("İndirilemedi: ${e.message}")
        }
        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("Sunucu hatası: HTTP ${it.code}")
            }
            val length = it.body?.contentLength() ?: -1
            if (length > MAX_BYTES) {
                throw IllegalStateException("Liste çok büyük (${length / 1024 / 1024} MB)")
            }
            val stream = it.body?.byteStream()
                ?: throw IllegalStateException("Boş yanıt")
            val out = StringBuilder()
            var total = 0L
            stream.bufferedReader().use { reader ->
                var line = reader.readLine()
                var lines = 0
                while (line != null) {
                    total += line.length + 1
                    if (total > MAX_BYTES) {
                        throw IllegalStateException("Liste çok büyük")
                    }
                    if (lines >= MAX_LINES) break
                    out.append(line).append('\n')
                    lines++
                    line = reader.readLine()
                }
            }
            if (out.isEmpty()) throw IllegalStateException("Liste boş")
            return out.toString()
        }
    }

    private fun lines(file: File): Sequence<String> = sequence {
        val reader = try {
            file.bufferedReader()
        } catch (_: Exception) {
            return@sequence
        }
        try {
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (_: Exception) {
                    null
                } ?: break
                yield(line)
            }
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(url.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 20)
        return File(dir, "$hash.txt")
    }

    companion object {
        private const val BUNDLED_ASSET = "filter_core.txt"
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_LINES = 1_500_000

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
