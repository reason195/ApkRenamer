package com.workbuddy.apkrenamer.ui

import android.content.Context
import com.workbuddy.apkrenamer.R
import com.workbuddy.apkrenamer.core.NameComposer
import com.workbuddy.apkrenamer.vm.RowStatus
import com.workbuddy.apkrenamer.vm.StatusFilter
import com.workbuddy.apkrenamer.vm.UiText

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> context.getString(resId, *args.toTypedArray())
    is UiText.Raw -> value
}

fun RowStatus.localizedLabel(context: Context): String = context.getString(when (this) {
    RowStatus.PENDING -> R.string.row_status_pending
    RowStatus.NEEDS_RENAME -> R.string.row_status_needs_rename
    RowStatus.NO_CHANGE -> R.string.row_status_no_change
    RowStatus.DONE -> R.string.row_status_done
    RowStatus.FAILED -> R.string.row_status_failed
})

fun StatusFilter.localizedLabel(context: Context): String = context.getString(when (this) {
    StatusFilter.ALL -> R.string.filter_all
    StatusFilter.NEEDS_RENAME -> R.string.row_status_needs_rename
    StatusFilter.NO_CHANGE -> R.string.row_status_no_change
    StatusFilter.DONE -> R.string.row_status_done
    StatusFilter.FAILED -> R.string.row_status_failed
    StatusFilter.SELECTED -> R.string.filter_selected
    StatusFilter.DUPLICATE -> R.string.filter_duplicate
})

fun NameComposer.Field.localizedLabel(context: Context): String = context.getString(when (this) {
    NameComposer.Field.APP_NAME -> R.string.field_app_name
    NameComposer.Field.VERSION_NAME -> R.string.field_version_name
    NameComposer.Field.VERSION_CODE -> R.string.field_version_code
    NameComposer.Field.PACKAGE_NAME -> R.string.field_package_name
    NameComposer.Field.CUSTOM_PREFIX -> R.string.field_custom_prefix
    NameComposer.Field.ORIGINAL_NAME -> R.string.field_original_name
    NameComposer.Field.ARCHIVE_TYPE -> R.string.field_archive_type
    NameComposer.Field.DATE -> R.string.field_date
    NameComposer.Field.SIZE -> R.string.field_size
    NameComposer.Field.MIN_SDK -> R.string.field_min_sdk
    NameComposer.Field.TARGET_SDK -> R.string.field_target_sdk
})

fun com.workbuddy.apkrenamer.core.NameValidation.localizedMessage(context: Context): String? = when (error) {
    null -> null
    com.workbuddy.apkrenamer.core.NameError.EMPTY -> context.getString(R.string.manual_error_empty)
    com.workbuddy.apkrenamer.core.NameError.ILLEGAL_ONLY -> context.getString(R.string.manual_error_illegal, NameComposer.ILLEGAL_CHARS.joinToString(" "))
    com.workbuddy.apkrenamer.core.NameError.RESERVED -> context.getString(R.string.manual_error_reserved, sanitized.substringBeforeLast("."))
    com.workbuddy.apkrenamer.core.NameError.TOO_LONG -> context.getString(R.string.manual_error_too_long)
    com.workbuddy.apkrenamer.core.NameError.CONFLICT -> context.getString(R.string.manual_error_conflict, sanitized)
}
