// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.phone.core

object RequestScope {
    fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }
    fun <T> withDeadline(deadlineNanos: Long, block: () -> T): T = block()
}
