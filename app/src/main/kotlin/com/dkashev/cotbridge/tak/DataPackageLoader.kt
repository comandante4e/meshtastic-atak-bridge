package com.dkashev.cotbridge.tak

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Парсит TAK DataPackage (.zip, экспорт из ATAK Import Manager → Export Mission Package → server).
 *
 * Внутри обычно:
 *   - <name>.p12       клиентский cert (пароль обычно atakatak, но может быть другой)
 *   - truststore-*.p12 серверный CA (тот же пароль)
 *   - <name>.pref      XML с connectString0=HOST:PORT:ssl и шепталкой
 *   - manifest.xml
 *
 * На выходе [Parsed] содержит готовый SSLContext, host:port из pref'а (если есть)
 * и CN клиентского cert (для дефолтного callsign'а).
 */
object DataPackageLoader {

    data class Parsed(
        val sslContext: SSLContext,
        val clientCommonName: String?,
        val hostHint: String?,
        val portHint: Int?,
    )

    fun load(input: InputStream, password: CharArray): Parsed {
        val entries = readZip(input)

        val clientP12 = entries.entries
            .firstOrNull { isClientP12(it.key) }
            ?.value
            ?: error("В DataPackage не найден клиентский .p12")

        val trustP12 = entries.entries
            .firstOrNull { isTrustP12(it.key) }
            ?.value

        val keyStore = loadKeyStore(clientP12, password)
        val trustStore = trustP12?.let { loadKeyStore(it, password) } ?: keyStore

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, java.security.SecureRandom())
        }

        val cn = extractCommonName(keyStore)
        val (host, port) = entries.entries
            .firstOrNull { it.key.endsWith(".pref", ignoreCase = true) }
            ?.value?.let(::parsePrefXml) ?: (null to null)

        return Parsed(ssl, cn, host, port)
    }

    private fun isClientP12(name: String): Boolean {
        val base = baseName(name).lowercase()
        if (!base.endsWith(".p12")) return false
        return base.contains("client") ||
            (!base.contains("trust") && !base.contains("ca") && !base.contains("root"))
    }

    private fun isTrustP12(name: String): Boolean {
        val base = baseName(name).lowercase()
        if (!base.endsWith(".p12")) return false
        return base.contains("trust") || base.contains("ca") || base.contains("root")
    }

    private fun baseName(name: String) =
        name.substringAfterLast('/').substringAfterLast('\\')

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val baos = ByteArrayOutputStream()
                zip.copyTo(baos)
                map[entry.name] = baos.toByteArray()
            }
        }
        return map
    }

    private fun loadKeyStore(bytes: ByteArray, password: CharArray): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        bytes.inputStream().use { ks.load(it, password) }
        return ks
    }

    private fun extractCommonName(ks: KeyStore): String? {
        val alias = ks.aliases().toList().firstOrNull() ?: return null
        val cert = ks.getCertificate(alias) as? X509Certificate ?: return null
        val dn = cert.subjectX500Principal.name
        return Regex("""CN=([^,]+)""").find(dn)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun parsePrefXml(bytes: ByteArray): Pair<String?, Int?> {
        val text = String(bytes, Charsets.UTF_8)
        val connect = Regex("""connectString0[^>]*>([^<]+)<""").find(text)
            ?.groupValues?.getOrNull(1) ?: return null to null
        val parts = connect.split(":")
        if (parts.size < 2) return null to null
        return parts[0] to parts[1].toIntOrNull()
    }
}
