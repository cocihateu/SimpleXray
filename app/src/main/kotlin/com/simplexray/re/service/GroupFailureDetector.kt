package com.simplexray.re.service

internal sealed interface GroupTraffic {
    data class Routed(val group: String, val node: String) : GroupTraffic
    data class Failed(val group: String, val node: String) : GroupTraffic
}

/** Xray's connection ID joins a tagged business route to its outbound failure. */
internal class GroupFailureDetector(
    private val groupTags: Map<String, String>,
    private val nodeTags: Set<String>,
    private val report: (GroupTraffic) -> Unit,
) {
    private val connections = mutableMapOf<String, GroupTraffic.Routed>()

    fun accept(line: String) {
        val id = CONNECTION_ID.find(line)?.groupValues?.get(1) ?: return
        val routed = BUSINESS_ROUTE.find(line)
        if (routed != null) {
            val marker = routed.groupValues[1].substringBefore('/')
            val group = groupTags[marker] ?: return
            val activity = GroupTraffic.Routed(group, routed.groupValues[2])
            connections[id] = activity
            report(activity)
            return
        }
        if (line.contains("failed to process outbound traffic") ||
            line.contains("failed to process mux outbound traffic")
        ) {
            val route = connections.remove(id) ?: return
            if (route.node in nodeTags && CONNECTION_FAILURE.containsMatchIn(line)) {
                report(GroupTraffic.Failed(route.group, route.node))
            }
        }
    }

    companion object {
        private val CONNECTION_ID = Regex("""\[(\d+)]""")
        private val BUSINESS_ROUTE = Regex("""Hit route rule: \[(.*)] so taking detour \[(.*)] for """)
        private val CONNECTION_FAILURE = Regex(
            "failed to find an available destination|failed to establish connection to server|" +
                "failed to create TCP connection|failed to read response header|" +
                "connection refused|network is unreachable|no route to host|" +
                "connection reset by peer|broken pipe|i/o timeout|" +
                "TLS handshake|remote error: tls",
            RegexOption.IGNORE_CASE,
        )
    }
}
