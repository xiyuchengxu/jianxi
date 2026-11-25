package dev.rain.laundry.cloud

/** Only the current workflow may create tracking; existing evidence survives pointer changes. */
internal object CloudDeviceReportQueue {
    private const val LIMIT = 128

    /** The current workflow is authoritative; otherwise show the newest unfinished evidence. */
    fun display(records: Collection<CloudDeviceReportRecord>, currentKey: String?): CloudDeviceReportRecord? =
        records.firstOrNull { it.orderKey == currentKey } ?: records
            .sortedWith(compareByDescending<CloudDeviceReportRecord> {
                !it.completionAcked && !it.cancelledAcked
            }.thenByDescending {
                maxOf(it.runningObservedAtMs, it.completionObservedAtMs ?: 0L,
                    it.cancelledObservedAtMs ?: 0L)
            }).firstOrNull()

    /** A different order's failure must remain actionable even while the current order is healthy. */
    fun issue(records: Collection<CloudDeviceReportRecord>): CloudIssue? {
        val report = records.sortedWith(compareByDescending<CloudDeviceReportRecord> {
            when {
                it.unsupported -> 4
                it.blocked -> 3
                it.lastIssue == "auth_required" -> 2
                CloudSyncFailurePolicy.httpCode(it.lastIssue) != null -> 1
                else -> 0
            }
        }.thenByDescending { it.runningObservedAtMs }).firstOrNull() ?: return null
        return when {
            report.unsupported -> CloudIssue(CloudIssueKind.VERSION_UNSUPPORTED)
            report.blocked -> CloudIssue(CloudIssueKind.DEVICE_REPORT_REJECTED,
                CloudSyncFailurePolicy.httpCode(report.lastIssue), CloudUpstream.sanitize(report.reasonCode))
            report.lastIssue == "auth_required" -> CloudIssue(CloudIssueKind.AUTH_REQUIRED,
                reasonCode = CloudUpstream.sanitize(report.reasonCode))
            report.lastIssue == "http_429" -> null
            CloudSyncFailurePolicy.httpCode(report.lastIssue) != null -> CloudIssue(CloudIssueKind.SYNC_PENDING,
                CloudSyncFailurePolicy.httpCode(report.lastIssue), CloudUpstream.sanitize(report.reasonCode))
            else -> null
        }
    }

    fun observe(
        records: Map<String, CloudDeviceReportRecord>,
        current: CloudDeviceObservation?,
        observations: List<CloudDeviceObservation>,
        force: Boolean = false
    ): Map<String, CloudDeviceReportRecord> {
        val updated = LinkedHashMap(records)
        val latest = (observations + listOfNotNull(current)).filter { it.ownerMatches }
            .groupBy { it.orderKey }.mapValues { (_, values) -> values.maxBy { it.observedAtMs } }
        for ((key, record) in records) {
            val base = if (force) CloudDeviceReportPolicy.prepareForced(record) else record
            updated[key] = latest[key]?.let { CloudDeviceReportPolicy.observe(base, it) } ?: base
        }
        val newRecord = current?.takeIf { it.orderKey !in updated }
            ?.let { CloudDeviceReportPolicy.observe(null, it) }
        if (newRecord != null && updated.size >= LIMIT) {
            updated.values.filter { it.completionAcked || it.cancelledAcked }
                .sortedBy { it.completionObservedAtMs ?: it.cancelledObservedAtMs ?: it.runningObservedAtMs }
                .take(updated.size - LIMIT + 1).forEach { updated.remove(it.orderKey) }
        }
        if (newRecord != null && updated.size < LIMIT) updated[newRecord.orderKey] = newRecord
        if (updated.size <= LIMIT) return updated
        // Never discard an unsent transition just because a newer order became current.
        val unfinished = updated.filterValues { !it.completionAcked && !it.cancelledAcked }
        val settled = updated.filterValues { it.completionAcked || it.cancelledAcked }.values
            .sortedByDescending { it.completionObservedAtMs ?: it.cancelledObservedAtMs ?: it.runningObservedAtMs }
            .take((LIMIT - unfinished.size).coerceAtLeast(0))
        return unfinished + settled.associateBy { it.orderKey }
    }

    fun next(records: Map<String, CloudDeviceReportRecord>): Pair<String, CloudOrderReport>? =
        records.values.sortedWith(compareBy<CloudDeviceReportRecord> {
            if (it.completionObservedAtMs != null || it.cancelledObservedAtMs != null) 0 else 1
        }.thenBy { it.runningObservedAtMs }.thenBy { it.orderKey }).firstNotNullOfOrNull { record ->
            CloudDeviceReportPolicy.nextReport(record)?.let { record.orderKey to it }
        }
}
