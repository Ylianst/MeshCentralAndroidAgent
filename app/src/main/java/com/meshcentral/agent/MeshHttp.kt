package com.meshcentral.agent

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// One dispatcher and connection pool for the control channel and every relay tunnel. Each
// connection derives its own certificate-pinning client from this with newBuilder(), which keeps
// the shared thread pools instead of spinning up new ones per tunnel.
internal object MeshHttp {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.MINUTES)
            .build()
    }
}
