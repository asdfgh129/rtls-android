package com.rtls.app

import android.content.Context
import com.rtls.core.data.db.RtlsDatabase
import com.rtls.core.data.preferences.RtlsPreferences
import com.rtls.core.data.queue.LocationQueue
import com.rtls.core.data.queue.RoomLocationQueue
import com.rtls.core.data.token.EncryptedTokenStore
import com.rtls.core.data.token.TokenStore
import com.rtls.core.network.OkHttpRtlsWebSocketClient
import com.rtls.core.network.RtlsWebSocketClient

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val preferences: RtlsPreferences by lazy { RtlsPreferences(appContext) }

    val tokenStore: TokenStore by lazy { EncryptedTokenStore(appContext) }

    val database: RtlsDatabase by lazy { RtlsDatabase.create(appContext) }

    val locationQueue: LocationQueue by lazy {
        RoomLocationQueue(database.locationQueueDao())
    }

    val webSocketClient: RtlsWebSocketClient by lazy {
        OkHttpRtlsWebSocketClient(wsUrl = BuildConfig.WS_BASE_URL.trimEnd('/') + "/v1/ws")
    }
}
