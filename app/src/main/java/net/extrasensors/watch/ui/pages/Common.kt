package net.extrasensors.watch.ui.pages

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Emite o horário atual imediatamente e depois a cada [periodMs]. */
fun tickerFlow(periodMs: Long): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}

const val PAGE_COUNT = 10
