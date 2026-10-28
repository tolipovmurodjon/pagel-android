package org.pagel.ai.data.api

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * TLS 1.2 only, for pagel.uz behind Cloudflare. Measured from Tashkent by the lead (2026-10-28): the TLS 1.3 handshake
 * to 188.114.97.11 took 11-15 s, TLS 1.2 0.8 s (`curl --tls-max 1.2`), the server itself answers in 9 ms. That 1.3
 * handshake was the "Offline" badge, the ~15 s pairing and the late sync.
 */
val PagelTls: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()

/**
 * The one client for everything the app asks pagel.uz (API, live stream, pairing, pictures, outbox, crash upload).
 * Plain http stays allowed for the test server on 127.0.0.1 (CI's fake pagel.uz). Connections are kept warm for
 * 5 minutes, so the next call after a tap skips the handshake altogether.
 */
fun pagelHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectionSpecs(listOf(PagelTls, ConnectionSpec.CLEARTEXT))
    .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
