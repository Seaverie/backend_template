package com.tenantcore.security

import com.tenantcore.common.Forbidden
import java.util.UUID

/**
 * Feature modules a platform admin switches on per tenant. A resource that belongs to a module is only
 * usable while that module is enabled, whatever the caller's role says.
 */
enum class Module(val code: String, val label: String, val description: String, val requires: List<String> = emptyList()) {
    CATALOG("catalog", "Catalog", "Products, variants and the public catalog"),
    INVENTORY("inventory", "Inventory", "Warehouses, bins, stock levels and movements", listOf("catalog")),
    PURCHASING("purchasing", "Purchasing", "Purchase orders and receiving stock", listOf("inventory")),
    ORDERS("orders", "Orders", "Orders, checkout and a configurable order lifecycle", listOf("catalog")),
    PAYMENTS("payments", "Payments", "Payment gateways and taking payments on orders", listOf("orders")),
    RETURNS("returns", "Returns", "Return requests, restocking and refunds", listOf("orders")),
    DATA_TABLES("data_tables", "Custom data tables", "Tables and fields defined from the dashboard"),
    IMPORTS("imports", "Imports", "CSV and Excel imports into custom tables", listOf("data_tables")),
    FILES("files", "Files & images", "Uploads, images and attachments"),
    EMAIL("email", "Email", "Outgoing email: invitations, password resets, notifications"),
    WEBHOOKS("webhooks", "Webhooks", "Signed event notifications to other systems"),
    CUSTOMER_ACCOUNTS("customer_accounts", "Customer accounts", "Let this business's customers sign up and sign in", listOf("email")),
    PUSH("push_notifications", "Push notifications", "Notifications to mobile devices"),
    MAPS("maps", "Maps", "Geocoding and addresses"),
    DELIVERY_TRACKING("delivery_tracking", "Delivery tracking", "Location pings for deliveries", listOf("orders")),
    QR_CODES("qr_codes", "QR codes", "Generate QR codes");

    companion object {
        val byCode = entries.associateBy { it.code }
        /** What a brand-new tenant gets until the platform admin changes it. */
        val defaultsForNewTenant = setOf("catalog", "orders", "files", "email")
    }
}

enum class Action(val value: String) {
    READ("read"), CREATE("create"), UPDATE("update"), DELETE("delete"), MANAGE("manage");

    companion object { fun of(v: String) = entries.firstOrNull { it.value == v } }
}

/**
 * Resources that permissions refer to. Administrative resources only take read/manage. `module` is the
 * module that must be enabled; null means always available.
 */
enum class Resource(val code: String, val admin: Boolean, val module: String?) {
    USERS("users", true, null),
    ROLES("roles", true, null),
    SETTINGS("settings", true, null),
    AUDIT_LOGS("audit_logs", true, null),
    API_KEYS("api_keys", true, null),
    ANALYTICS("analytics", true, "orders"),
    WEBHOOKS("webhooks", true, "webhooks"),
    NOTIFICATIONS("notifications", true, "push_notifications"),
    PRODUCTS("products", false, "catalog"),
    INVENTORY("inventory", false, "inventory"),
    PURCHASING("purchasing", false, "purchasing"),
    ORDERS("orders", false, "orders"),
    PAYMENTS("payments", false, "payments"),
    RMA("rma", false, "returns"),
    IMPORTS("imports", false, "imports"),
    FILES("files", false, "files"),
    CUSTOMERS("customers", false, "customer_accounts"),
    MAPS("maps", false, "maps"),
    DELIVERIES("deliveries", false, "delivery_tracking"),
    /** Defining custom tables and fields (read to see every table's design, manage to change it). */
    DATA_SCHEMA("data_schema", true, "data_tables");

    companion object {
        val byCode = entries.associateBy { it.code }
        val DATA = Regex("^data\\.[a-z][a-z0-9_]{0,62}$")

        fun moduleOf(resource: String): String? =
            if (DATA.matches(resource)) "data_tables" else byCode[resource]?.module

        fun isKnown(resource: String) = resource in byCode || DATA.matches(resource)
        fun isAdmin(resource: String) = byCode[resource]?.admin == true
    }
}

data class Grant(val rowScope: String, val readableFields: List<String>?, val writableFields: List<String>?) {
    fun canRead(field: String) = readableFields == null || field in readableFields
    fun canWrite(field: String) = writableFields == null || field in writableFields
}

sealed interface Principal { val sessionId: UUID }

data class UserPrincipal(
    val tenantId: UUID,
    val userId: UUID,
    override val sessionId: UUID,
    val email: String,
    val isAdmin: Boolean,
    val grants: Map<Pair<String, Action>, Grant>,
    val modules: Set<String>,
    /** Set when the caller is an API key rather than a person; userId is then the key's id. */
    val apiKeyId: UUID? = null,
) : Principal {
    /** How this caller appears in audit logs and histories. */
    val actorType get() = if (apiKeyId != null) "api_key" else "user"

    fun moduleEnabled(module: String?) = module == null || module in modules

    /** The grant for (resource, action); `manage` implies `read`. Null if not allowed. */
    fun grant(resource: String, action: Action): Grant? {
        if (!moduleEnabled(Resource.moduleOf(resource))) return null
        if (isAdmin) return Grant("all", null, null)
        return grants[resource to action] ?: if (action == Action.READ) grants[resource to Action.MANAGE] else null
    }

    fun can(resource: String, action: Action) = grant(resource, action) != null

    fun require(resource: String, action: Action): Grant {
        val module = Resource.moduleOf(resource)
        if (!moduleEnabled(module)) throw Forbidden("The ${Module.byCode[module]?.label ?: module} module is not enabled for this workspace.")
        return grant(resource, action) ?: throw Forbidden("Your role does not allow ${action.value} on $resource.")
    }

    fun requireModule(module: String) {
        if (!moduleEnabled(module)) throw Forbidden("The ${Module.byCode[module]?.label ?: module} module is not enabled for this workspace.")
    }

    fun requireAdmin() { if (!isAdmin) throw Forbidden("Only workspace admins can do this.") }
}

data class PlatformPrincipal(
    val adminId: UUID,
    override val sessionId: UUID,
    val email: String,
    val mfaVerified: Boolean,
) : Principal
