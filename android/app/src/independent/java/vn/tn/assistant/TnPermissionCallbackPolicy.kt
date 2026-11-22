package vn.tn.assistant

/** Guards Android permission callbacks against a newer Activity session or operation. */
internal data class TnPermissionOperation(val owner: Long, val revision: Long)

internal object TnPermissionCallbackPolicy {
    fun isCurrent(
        operation: TnPermissionOperation?,
        currentOwner: Long,
        currentRevision: Long,
        activityValid: Boolean,
    ): Boolean = activityValid && operation != null &&
        operation.owner == currentOwner && operation.revision == currentRevision

    fun accepts(
        operation: TnPermissionOperation?,
        currentOwner: Long,
        currentRevision: Long,
        activityValid: Boolean,
        granted: Boolean,
    ): Boolean = granted && isCurrent(operation, currentOwner, currentRevision, activityValid)
}
