package com.tenantcore.db

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/**
 * Every database access goes through one of these transaction scopes. Each one sets the row-level
 * security context with `set_config(..., true)`, which is local to the transaction, so a pooled
 * connection never carries one tenant's context into the next request.
 *
 *  - [tenant]: the runtime role, scoped to one tenant. RLS hides every other tenant's rows.
 *  - [platform]: the runtime role in platform context (platform admins, sessions, tenant registry),
 *    optionally scoped to one tenant as well when a platform admin manages that tenant.
 *  - [anonymous]: the runtime role with no context: only public data (e.g. tenant slugs) is visible.
 *  - [owner]: the schema-owner role. Used only for migrations and bootstrap tasks; still subject to
 *    FORCE ROW LEVEL SECURITY, so it sets the same context.
 *
 * Scopes may nest (a service called from inside another scope). A nested scope joins the outer
 * transaction, switches the context for its block and then restores the outer context exactly, so an
 * inner platform or anonymous lookup can never widen or erase the outer tenant's isolation.
 */
class Db(
    private val appDs: DataSource,
    private val app: JdbcClient,
    private val appTx: TransactionTemplate,
    private val ownerDs: DataSource,
    private val ownerClient: JdbcClient,
    private val ownerTx: TransactionTemplate,
) {
    fun <T> tenant(tenantId: UUID, block: (JdbcClient) -> T): T = run(appDs, appTx, app, tenantId, platform = false, block)

    fun <T> platform(tenantId: UUID? = null, block: (JdbcClient) -> T): T = run(appDs, appTx, app, tenantId, platform = true, block)

    fun <T> anonymous(block: (JdbcClient) -> T): T = run(appDs, appTx, app, null, platform = false, block)

    fun <T> owner(tenantId: UUID? = null, block: (JdbcClient) -> T): T = run(ownerDs, ownerTx, ownerClient, tenantId, platform = true, block)

    private fun set(jdbc: JdbcClient, tenant: String, platform: String) {
        jdbc.sql("SELECT set_config('app.tenant_id', :t, true), set_config('app.is_platform', :p, true)")
            .param("t", tenant).param("p", platform).query().singleRow()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> run(ds: DataSource, tx: TransactionTemplate, jdbc: JdbcClient, tenantId: UUID?, platform: Boolean, block: (JdbcClient) -> T): T {
        val nested = TransactionSynchronizationManager.hasResource(ds)
        return tx.execute {
            val previous = if (nested) jdbc.sql("SELECT coalesce(current_setting('app.tenant_id', true), ''), coalesce(current_setting('app.is_platform', true), '')")
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }.single() else null
            set(jdbc, tenantId?.toString() ?: "", if (platform) "on" else "off")
            try {
                block(jdbc)
            } finally {
                if (previous != null) runCatching { set(jdbc, previous.first, previous.second) }
            }
        } as T
    }
}
