package com.workbuddy.apkrenamer.vm

/** User-visible text resolved by phone and TV renderers in their current locale. */
sealed interface UiText {
    data class Resource(val resId: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val value: String) : UiText
}
