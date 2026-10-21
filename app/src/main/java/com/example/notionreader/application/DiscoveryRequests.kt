package com.example.notionreader.application

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One request lane: latest invocation owns publication, independent of dispatcher execution order. */
internal class DiscoveryRequests(private val validity: DiscoveryValidity) {
    internal data class Ticket(val sequence: Long, val source: DiscoveryReadToken)

    private val sequence = AtomicLong()

    // Call before any dispatch or suspending read, not when a worker eventually starts.
    fun begin(): Ticket = Ticket(sequence.incrementAndGet(), validity.capture())
    fun reset() { sequence.incrementAndGet() }
    fun isCurrent(ticket: Ticket): Boolean = owns(ticket) && validity.isCurrent(ticket.source)
    private fun owns(ticket: Ticket): Boolean = ticket.sequence == sequence.get()

    fun publish(state: MutableStateFlow<DiscoveryState>, ticket: Ticket,
        transform: (DiscoveryState) -> DiscoveryState) {
        state.update { current -> if (isCurrent(ticket)) transform(current) else current }
    }

    /** Cancellation/source changes may end loading; a newer request must keep its own loading state. */
    fun finish(state: MutableStateFlow<DiscoveryState>, ticket: Ticket,
        transform: (DiscoveryState) -> DiscoveryState) {
        state.update { current -> if (owns(ticket)) transform(current) else current }
    }
}
