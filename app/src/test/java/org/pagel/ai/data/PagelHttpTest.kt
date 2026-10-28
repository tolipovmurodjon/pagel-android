package org.pagel.ai.data

import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.pagel.ai.data.api.pagelHttp

/** pagel.uz's TLS 1.3 handshake took 11-15 s from Tashkent, TLS 1.2 0.8 s (2026-10-28): the client speaks 1.2 only. */
class PagelHttpTest {
    @Test fun everyTlsConnectionIsTls12Only() {
        val http = pagelHttp()
        val tls = http.connectionSpecs.filter { it.isTls }
        assertEquals(1, tls.size)
        assertEquals(listOf(TlsVersion.TLS_1_2), tls.single().tlsVersions)
        // Plain http only for the test server on 127.0.0.1.
        assertEquals(listOf(ConnectionSpec.CLEARTEXT), http.connectionSpecs.filter { !it.isTls })
    }
}
