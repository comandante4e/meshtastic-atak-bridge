# v0.3.2 — фикс trust-anchor при импорте DataPackage (URPC-cert TLS)

## Проблема
Подключение к TAK-серверу (URPC) под импортированным cert'ом падало в бесконечный
реконнект с:

```
java.security.cert.CertPathValidatorException: Trust anchor for certification path not found
```

## Причина
`DataPackageLoader` строил trust-store так:

```kotlin
val trustStore = trustP12?.let { loadKeyStore(it, password) } ?: keyStore
tmf.init(trustStore)
```

`KeyStore.getInstance("PKCS12")` — это **дефолтный Android-провайдер PKCS12**, который
НЕ отдаёт trusted-certificate-записи (`trustedCertEntry`) как `isCertificateEntry`:

- p12 от `keytool -importcert` помечает CA проприетарным Oracle-атрибутом
  (`oracleTrustedKeyUsage`) — Android его игнорирует;
- p12 от `openssl pkcs12 -nokeys` (cert-bag) — тоже не выставляет флаг доверенного.

Итог: `TrustManagerFactory.init(pkcs12)` получал **ноль trust-anchor'ов** → любой
серверный cert не проходил валидацию цепочки. (openssl `s_client` при этом коннектился —
он не завязан на этот механизм.)

## Фикс
`DataPackageLoader.buildTrustStore()` пересобирает trust-store явно: вытаскивает ВСЕ
читаемые X509-серты (standalone-записи + **цепочки key-entry'ей**) из trust-p12 и из
клиентского p12, и кладёт их через `setCertificateEntry` в свежий in-memory keystore.
Цепочку сертификатов key-entry'я (клиентский .p12) Android-провайдер читает всегда, а
в ней лежит CA — канонический Android-паттерн «доверять своему CA». Работает и когда
отдельного truststore в DataPackage нет.

## Проверено вживую (Кола, OnePlus, Android 16 / API 36)
- `URPC [urpc]: connected` — стрим стабилен, реконнект-петли нет.
- Обратка сервер→меш (RX per-cert): счётчик `bridge → меш` тикает — CoT с URPC
  доезжает в LoRa-меш.
