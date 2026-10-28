package com.eagleviewer.app.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persist the identity that was clicked, independently of when Detail first composes. */
@Serializable
data class DetailNavigationTicket(val root: String, val epoch: Long)

enum class DetailDisposition { READY, WAIT, EXIT }

object DetailNavigation {
    // Keep the old destination registered so an index-only saved back stack can exit safely.
    const val LEGACY_ROUTE = "detail/{index}"
    const val ROUTE = "detail-v2/{index}/{ticket}"

    fun capture(publication: IndexPublication): DetailNavigationTicket? =
        if (publication.readable) DetailNavigationTicket(publication.root, publication.epoch) else null

    /** URL-safe transport encoding only. Never feed this encoding to SAF URI builders. */
    fun encode(ticket: DetailNavigationTicket): String {
        require(ticket.root.isNotEmpty() && ticket.epoch > 0)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Json.encodeToString(ticket).toByteArray(Charsets.UTF_8))
    }

    fun decode(value: String?): DetailNavigationTicket? {
        if (value.isNullOrEmpty() || value.length > 64 * 1024 ||
            value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(value)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val ticket = Json.decodeFromString<DetailNavigationTicket>(text)
            if (ticket.root.isEmpty() || ticket.epoch <= 0 || encode(ticket) != value) null else ticket
        }.getOrNull()
    }

    fun route(index: Int, ticket: DetailNavigationTicket): String {
        require(index >= 0)
        return "detail-v2/$index/${encode(ticket)}"
    }

    fun admits(ticket: DetailNavigationTicket?, publication: IndexPublication): Boolean =
        ticket != null && publication.readable && ticket.root == publication.root && ticket.epoch == publication.epoch

    fun disposition(ticket: DetailNavigationTicket?, publication: IndexPublication): DetailDisposition = when {
        admits(ticket, publication) -> DetailDisposition.READY
        ticket != null && ticket.root.isNotEmpty() && ticket.epoch > 0 &&
            !publication.readable && ticket.root == publication.root &&
            ticket.root == publication.activeUri && ticket.epoch == publication.epoch -> DetailDisposition.WAIT
        else -> DetailDisposition.EXIT
    }
}

/** Navigation waits for a verified durable load; an IO failure exposes a retry, not a spinner. */
enum class NavigationIndexState { Loading, Ready, Failed }
class NavigationIndexInitialization {
    private val live = kotlinx.coroutines.flow.MutableStateFlow(NavigationIndexState.Loading)
    val state: kotlinx.coroutines.flow.StateFlow<NavigationIndexState> = live
    suspend fun load(initialize: suspend () -> Unit) {
        live.value = NavigationIndexState.Loading
        try {
            initialize()
            live.value = NavigationIndexState.Ready
        } catch (failure: kotlinx.coroutines.CancellationException) {
            live.value = NavigationIndexState.Failed
            throw failure
        } catch (failure: Exception) {
            live.value = NavigationIndexState.Failed
        }
    }
}
