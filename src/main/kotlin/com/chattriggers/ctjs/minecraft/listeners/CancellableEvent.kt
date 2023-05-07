package com.chattriggers.ctjs.minecraft.listeners

open class CancellableEvent {
    private var cancelled = false

    @JvmOverloads
    fun setCanceled(newVal: Boolean = true) {
        cancelled = newVal
    }

    @JvmOverloads
    fun setCancelled(newVal: Boolean = true) {
        cancelled = newVal
    }

    fun isCancelable() = true
    fun isCancellable() = true

    fun isCancelled() = cancelled
    fun isCanceled() = cancelled
}
