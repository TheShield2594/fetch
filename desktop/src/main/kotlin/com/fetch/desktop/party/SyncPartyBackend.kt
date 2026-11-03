package com.fetch.desktop.party

import com.fetch.desktop.party.realtime.OkHttpRealtimeTransport
import com.fetch.desktop.party.realtime.RealtimeSocket
import com.fetch.desktop.sync.SyncClient
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * The sync server account as a party backend: RPCs with its session, and Realtime at
 * `wss://<server>/realtime/v1/websocket` with its anon key and (refreshed) access token.
 */
class SyncPartyBackend(
    private val client: SyncClient,
    private val http: OkHttpClient,
    override val deviceId: String,
    private val scope: CoroutineScope,
) : PartyBackend {
    override val api: PartyApi = PartyApi(client)

    override val storageUrl: String = "${client.server.baseUrl}/storage/v1/object/"

    override fun openChannel(partyId: String, memberId: String): PartyChannelPort {
        val socket = RealtimeSocket(
            url = RealtimeSocket.urlFor(client.server.baseUrl, client.server.anonKey),
            transport = OkHttpRealtimeTransport(http),
            scope = scope,
            accessToken = { client.freshAccessToken() },
        )
        return RealtimePartyChannel(socket, partyId, memberId)
    }
}
