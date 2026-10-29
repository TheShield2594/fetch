package com.fetch.tv.core.util

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A StateFlow of [transform] over [first] and [second]. Unlike `combine(...).stateIn(...)`,
 * [StateFlow.value] is worked out on read, so it is never behind its sources.
 */
fun <A, B, R> derivedStateFlow(
    first: StateFlow<A>,
    second: StateFlow<B>,
    transform: (A, B) -> R
): StateFlow<R> = DerivedStateFlow(first, second, transform)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow<A, B, R>(
    private val first: StateFlow<A>,
    private val second: StateFlow<B>,
    private val transform: (A, B) -> R
) : StateFlow<R> {
    override val value: R
        get() = transform(first.value, second.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        combine(first, second, transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
