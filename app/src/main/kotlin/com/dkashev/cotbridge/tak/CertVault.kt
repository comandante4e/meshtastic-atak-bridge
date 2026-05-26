package com.dkashev.cotbridge.tak

import android.content.Context
import android.net.Uri
import java.io.File
import javax.net.ssl.SSLContext

/**
 * Хранилище N импортированных TAK DataPackage'ей в private storage приложения.
 *
 * Структура на диске:
 *   filesDir/packages/{slug}/package.zip   — исходный .zip
 *   filesDir/packages/{slug}/meta.txt      — `callsign|password|host|port|cn`
 *
 * `slug` — безопасное имя папки (filename-safe callsign).
 *
 * Пароль хранится в plain — это приватный sandbox app'а. Для production использовать
 * EncryptedSharedPreferences / Android Keystore (TODO в v0.3).
 */
class CertVault(private val context: Context) {

    data class Entry(
        val callsign: String,
        val cn: String?,
        val host: String,
        val port: Int,
        val ssl: SSLContext,
    )

    private val root: File get() = File(context.filesDir, "packages").apply { mkdirs() }

    /**
     * Импорт нового DataPackage. Парсит cert, выводит CN/host/port, сохраняет файлы на диск.
     * Если [callsignOverride] не указан — используется CN cert'а.
     * Если [hostOverride]/[portOverride] не указаны — берётся из pref-файла внутри .zip.
     *
     * Возвращает готовый [Entry] (с уже инициализированным SSLContext).
     */
    fun import(
        sourceUri: Uri,
        password: String,
        callsignOverride: String? = null,
        hostOverride: String? = null,
        portOverride: Int? = null,
    ): Entry {
        val resolver = context.contentResolver
        val zipBytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать ${sourceUri}")

        val parsed = DataPackageLoader.load(zipBytes.inputStream(), password.toCharArray())

        val callsign = callsignOverride?.takeIf { it.isNotBlank() }
            ?: parsed.clientCommonName
            ?: error("В cert'е нет CN — укажи callsign вручную")
        val host = hostOverride?.takeIf { it.isNotBlank() }
            ?: parsed.hostHint
            ?: error("Не указан host (нет в pref) — укажи руками")
        val port = portOverride ?: parsed.portHint ?: 8089

        val slug = slugify(callsign)
        val dir = File(root, slug).apply { mkdirs() }
        File(dir, "package.zip").writeBytes(zipBytes)
        File(dir, "meta.txt").writeText("$callsign|$password|$host|$port|${parsed.clientCommonName ?: ""}")

        return Entry(callsign, parsed.clientCommonName, host, port, parsed.sslContext)
    }

    /** Перечитывает все импортированные DataPackage с диска, инициализирует SSLContext каждого. */
    fun loadAll(): List<Entry> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { loadOne(dir) }.getOrNull()
        }
    }

    private fun loadOne(dir: File): Entry {
        val meta = File(dir, "meta.txt").readText().split("|")
        require(meta.size >= 4) { "Битый meta.txt в ${dir.name}" }
        val callsign = meta[0]
        val password = meta[1]
        val host = meta[2]
        val port = meta[3].toInt()
        val cn = meta.getOrNull(4)?.takeIf { it.isNotBlank() }

        val zipBytes = File(dir, "package.zip").readBytes()
        val parsed = DataPackageLoader.load(zipBytes.inputStream(), password.toCharArray())
        return Entry(callsign, cn, host, port, parsed.sslContext)
    }

    fun remove(callsign: String) {
        val slug = slugify(callsign)
        File(root, slug).deleteRecursively()
    }

    fun list(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { File(dir, "meta.txt").readText().split("|").firstOrNull() }.getOrNull()
        } ?: emptyList()

    private fun slugify(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "user" }
}
