package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*

internal data class HomePinnedSession(val connection: ConnectionSummary, val session: RemoteSession)
internal fun homePinnedSessions(connections: List<ConnectionSummary>, sessions: Map<String, List<RemoteSession>>, selected: String?): List<HomePinnedSession> =
    connections.filter { it.enabled && (selected == null || it.id == selected) }.flatMap { connection ->
        sessions[connection.id].orEmpty().filter { it.pinned && (it.archivedAt ?: 0) <= 0 }.map { HomePinnedSession(connection, it) }
    }.sortedByDescending { it.session.updatedAt }

internal fun homeProjectSessions(sessions: List<RemoteSession>, running: Set<String>): List<RemoteSession> =
    sessions.filter { !it.pinned && (it.archivedAt ?: 0) <= 0 }.sortedWith(
        compareBy<RemoteSession> { if (it.id in running) 0 else if (it.unread != "read") 1 else 2 }
            .thenByDescending { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id })

// Distinct kinds separate the unassigned group from every possible project ID.
internal fun homeGroupKey(connection: String, project: String?) =
    homeKey(if (project == null) "unassigned-group" else "project-group", connection, project.orEmpty())

internal fun homeGroupLimit(sessions: List<RemoteSession>, running: Set<String>): Int =
    maxOf(5, sessions.count { it.id in running || it.unread != "read" })
