package com.tenantcore.users

import com.tenantcore.audit.Audit
import com.tenantcore.common.Conflict
import com.tenantcore.common.Forbidden
import com.tenantcore.common.Invalid
import com.tenantcore.common.NotFound
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.Module
import com.tenantcore.security.Passwords
import com.tenantcore.security.Resource
import com.tenantcore.security.UserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

// ------------------------------------------------------------------------------------------------
// Roles
// ------------------------------------------------------------------------------------------------

data class PermissionIn(
    @field:NotBlank @field:Size(max = 100) val resource: String,
    @field:Pattern(regexp = "^(read|create|update|delete|manage)$", message = "must be read, create, update, delete or manage") val action: String,
    @field:Pattern(regexp = "^(all|own)$", message = "must be all or own") val rowScope: String = "all",
    @field:Size(max = 100) val readableFields: List<String>? = null,
    @field:Size(max = 100) val writableFields: List<String>? = null,
)
data class PermissionOut(val resource: String, val action: String, val rowScope: String, val readableFields: List<String>?, val writableFields: List<String>?)
data class RoleIn(
    @field:Pattern(regexp = "^[A-Za-z0-9 _.-]{1,100}$", message = "letters, digits, spaces and . _ - (1-100)") val name: String,
    @field:Size(max = 500) val description: String? = null,
    @field:Size(max = 200) val permissions: List<@Valid PermissionIn> = emptyList(),
)
data class RolePatchIn(
    @field:Pattern(regexp = "^[A-Za-z0-9 _.-]{1,100}$", message = "letters, digits, spaces and . _ - (1-100)") val name: String? = null,
    @field:Size(max = 500) val description: String? = null,
    @field:Size(max = 200) val permissions: List<@Valid PermissionIn>? = null,
)
data class RoleOut(val id: UUID, val name: String, val description: String?, val isSystem: Boolean, val userCount: Int, val permissions: List<PermissionOut>)

@Service
class RoleService(
    private val db: Db, private val audit: Audit,
    @org.springframework.context.annotation.Lazy private val schema: com.tenantcore.data.SchemaService,
) {

    fun list(j: JdbcClient, id: UUID? = null): List<RoleOut> {
        val perms = j.sql("SELECT role_id, resource, action, row_scope, readable_fields, writable_fields FROM role_permissions ORDER BY resource, action")
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to PermissionOut(rs.getString(2), rs.getString(3), rs.getString(4), arr(rs.getArray(5)), arr(rs.getArray(6))) }
            .list().groupBy({ it.first }, { it.second })
        return j.sql("""SELECT r.id, r.name, r.description, r.is_system, (SELECT count(*) FROM user_roles ur WHERE ur.role_id = r.id)
                        FROM roles r ${if (id != null) "WHERE r.id = :id" else ""} ORDER BY r.name""")
            .apply { if (id != null) param("id", id) }
            .query { rs, _ ->
                val rid = rs.getObject(1, UUID::class.java)
                RoleOut(rid, rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5), perms[rid].orEmpty())
            }.list()
    }

    /**
     * Rules: the resource must exist; admin resources only take read/manage; field lists and "own" rows
     * apply to data tables only; and a non-admin can never grant something they do not hold themselves.
     */
    fun validate(p: UserPrincipal, perms: List<PermissionIn>) {
        val seen = HashSet<Pair<String, String>>()
        perms.forEachIndexed { i, perm ->
            fun bad(msg: String): Nothing = throw Invalid("Permission ${i + 1} (${perm.resource}:${perm.action}): $msg")
            if (!seen.add(perm.resource to perm.action)) bad("listed twice")
            if (!Resource.isKnown(perm.resource)) bad("unknown resource")
            val isData = Resource.DATA.matches(perm.resource)
            if (Resource.isAdmin(perm.resource) && perm.action !in setOf("read", "manage")) bad("administrative resources take read or manage only")
            if (isData && perm.action == "manage") bad("manage is not a data-table action")
            if (!isData && (perm.rowScope != "all" || perm.readableFields != null || perm.writableFields != null))
                bad("row scope and field lists apply to data tables only")
            listOfNotNull(perm.readableFields, perm.writableFields).flatten().forEach {
                if (!Regex("^[a-z][a-z0-9_]{0,62}$").matches(it)) bad("invalid field name '$it'")
            }
            if (!p.isAdmin) {
                val held = p.grant(perm.resource, Action.of(perm.action)!!) ?: bad("you cannot grant a permission you do not have")
                if (held.rowScope == "own" && perm.rowScope != "own") bad("you can only grant access to own rows")
                fun within(mine: List<String>?, theirs: List<String>?) = mine == null || (theirs != null && mine.containsAll(theirs))
                if (!within(held.readableFields, perm.readableFields) || !within(held.writableFields, perm.writableFields))
                    bad("you cannot grant fields you cannot access")
            }
        }
    }

    private fun insertPerms(j: JdbcClient, tenantId: UUID, roleId: UUID, perms: List<PermissionIn>) = perms.forEach {
        if (Resource.DATA.matches(it.resource)) schema.checkPermission(j, it.resource, it.readableFields.orEmpty() + it.writableFields.orEmpty())
        j.sql("""INSERT INTO role_permissions (tenant_id, role_id, resource, action, row_scope, readable_fields, writable_fields)
                 VALUES (:t, :r, :res, :a, :s, :rf, :wf)""")
            .param("t", tenantId).param("r", roleId).param("res", it.resource).param("a", it.action).param("s", it.rowScope)
            .param("rf", it.readableFields?.toTypedArray()).param("wf", it.writableFields?.toTypedArray()).update()
    }

    fun create(input: RoleIn): RoleOut {
        val p = Caller.user()
        p.require("roles", Action.MANAGE)
        validate(p, input.permissions)
        return db.tenant(p.tenantId) { j ->
            if (j.sql("SELECT count(*) FROM roles WHERE name = :n").param("n", input.name.trim()).query(Long::class.java).single() > 0)
                throw Conflict("A role named '${input.name}' already exists.")
            val id = j.sql("INSERT INTO roles (tenant_id, name, description) VALUES (:t, :n, :d) RETURNING id")
                .param("t", p.tenantId).param("n", input.name.trim()).param("d", input.description).query(UUID::class.java).single()
            insertPerms(j, p.tenantId, id, input.permissions)
            audit.record(j, p.tenantId, "role.create", "roles", id, mapOf("name" to input.name, "permissions" to input.permissions.map { "${it.resource}:${it.action}" }))
            list(j, id).single()
        }
    }

    fun patch(id: UUID, input: RolePatchIn): RoleOut {
        val p = Caller.user()
        p.require("roles", Action.MANAGE)
        input.permissions?.let { validate(p, it) }
        return db.tenant(p.tenantId) { j ->
            val role = list(j, id).singleOrNull() ?: throw NotFound("Role")
            if (role.isSystem) throw Forbidden("Built-in roles cannot be changed.")
            // A non-admin may not edit a role that already holds more than they do.
            if (!p.isAdmin) validate(p, role.permissions.map { PermissionIn(it.resource, it.action, it.rowScope, it.readableFields, it.writableFields) })
            input.name?.let {
                if (it.trim() != role.name && j.sql("SELECT count(*) FROM roles WHERE name = :n").param("n", it.trim()).query(Long::class.java).single() > 0)
                    throw Conflict("A role named '$it' already exists.")
                j.sql("UPDATE roles SET name = :n WHERE id = :id").param("n", it.trim()).param("id", id).update()
            }
            input.description?.let { j.sql("UPDATE roles SET description = :d WHERE id = :id").param("d", it).param("id", id).update() }
            input.permissions?.let {
                j.sql("DELETE FROM role_permissions WHERE role_id = :id").param("id", id).update()
                insertPerms(j, p.tenantId, id, it)
            }
            audit.record(j, p.tenantId, "role.update", "roles", id, mapOf("name" to input.name, "permissions" to input.permissions?.map { "${it.resource}:${it.action}" }))
            list(j, id).single()
        }
    }

    fun delete(id: UUID) {
        val p = Caller.user()
        p.require("roles", Action.MANAGE)
        db.tenant(p.tenantId) { j ->
            val role = list(j, id).singleOrNull() ?: throw NotFound("Role")
            if (role.isSystem) throw Forbidden("Built-in roles cannot be deleted.")
            if (!p.isAdmin) validate(p, role.permissions.map { PermissionIn(it.resource, it.action, it.rowScope, it.readableFields, it.writableFields) })
            j.sql("DELETE FROM roles WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "role.delete", "roles", id, mapOf("name" to role.name, "users_affected" to role.userCount))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun arr(a: java.sql.Array?): List<String>? = (a?.array as? Array<String>)?.toList()
}

// ------------------------------------------------------------------------------------------------
// Users
// ------------------------------------------------------------------------------------------------

data class UserOut(
    val id: UUID, val email: String, val fullName: String?, val isActive: Boolean, val isTenantAdmin: Boolean,
    val roleIds: List<UUID>, val lastLoginAt: OffsetDateTime?, val lockedUntil: OffsetDateTime?, val createdAt: OffsetDateTime,
    /** True until the user accepts their invitation and chooses a password. */
    val invitationPending: Boolean = false, val invitedAt: OffsetDateTime? = null,
)
data class UserCreateIn(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
    @field:Size(max = 200) val fullName: String? = null,
    @field:NotBlank @field:Size(max = 256) val password: String,
    val isTenantAdmin: Boolean = false,
    @field:Size(max = 50) val roleIds: List<UUID> = emptyList(),
)
data class UserPatchIn(
    @field:Size(max = 200) val fullName: String? = null,
    val isActive: Boolean? = null,
    val isTenantAdmin: Boolean? = null,
    /** Clears a lock caused by repeated failed sign-ins. */
    val unlock: Boolean? = null,
)
data class UserRolesIn(@field:Size(max = 50) val roleIds: List<UUID>)
data class ResetPasswordIn(@field:NotBlank @field:Size(max = 256) val password: String)

@Service
class UserService(private val db: Db, private val passwords: Passwords, private val audit: Audit, private val roles: RoleService) {

    fun list(j: JdbcClient, id: UUID? = null, q: String? = null): List<UserOut> {
        val roleMap = j.sql("SELECT user_id, role_id FROM user_roles").query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
            .list().groupBy({ it.first }, { it.second })
        val where = when {
            id != null -> "WHERE id = :id"
            !q.isNullOrBlank() -> "WHERE email ILIKE :q OR full_name ILIKE :q"
            else -> ""
        }
        return j.sql("SELECT id, email, full_name, is_active, is_tenant_admin, last_login_at, locked_until, created_at, password_set, invited_at FROM app_users $where ORDER BY created_at")
            .apply { if (id != null) param("id", id) else if (!q.isNullOrBlank()) param("q", "%${q.replace("%", "\\%").replace("_", "\\_")}%") }
            .query { rs, _ ->
                val uid = rs.getObject(1, UUID::class.java)
                UserOut(uid, rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), roleMap[uid].orEmpty(),
                    rs.getObject(6, OffsetDateTime::class.java), rs.getObject(7, OffsetDateTime::class.java), rs.getObject(8, OffsetDateTime::class.java),
                    !rs.getBoolean(9), rs.getObject(10, OffsetDateTime::class.java))
            }.list()
    }

    /** A non-admin assigning roles may only hand out roles whose every permission they hold. */
    fun checkAssignable(j: JdbcClient, p: UserPrincipal, roleIds: List<UUID>) {
        if (roleIds.isEmpty()) return
        val found = roles.list(j).filter { it.id in roleIds }
        if (found.size != roleIds.toSet().size) throw Invalid("One or more roles do not exist.")
        if (!p.isAdmin) found.forEach { r ->
            runCatching { roles.validate(p, r.permissions.map { PermissionIn(it.resource, it.action, it.rowScope, it.readableFields, it.writableFields) }) }
                .onFailure { throw Forbidden("You cannot assign the role '${r.name}': it has permissions you do not hold.") }
        }
    }

    fun setRoles(j: JdbcClient, tenantId: UUID, userId: UUID, roleIds: List<UUID>) {
        j.sql("DELETE FROM user_roles WHERE user_id = :u").param("u", userId).update()
        roleIds.distinct().forEach {
            j.sql("INSERT INTO user_roles (tenant_id, user_id, role_id) VALUES (:t, :u, :r)").param("t", tenantId).param("u", userId).param("r", it).update()
        }
    }

    fun create(input: UserCreateIn): UserOut {
        val p = Caller.user()
        p.require("users", Action.MANAGE)
        if (input.isTenantAdmin) p.requireAdmin()
        passwords.validatePolicy(input.password)
        val hash = passwords.hash(input.password)
        return db.tenant(p.tenantId) { j ->
            val email = input.email.trim().lowercase()
            if (j.sql("SELECT count(*) FROM app_users WHERE lower(email) = :e").param("e", email).query(Long::class.java).single() > 0)
                throw Conflict("A user with this email already exists.")
            checkAssignable(j, p, input.roleIds)
            val id = j.sql("INSERT INTO app_users (tenant_id, email, password_hash, full_name, is_tenant_admin) VALUES (:t, :e, :h, :n, :a) RETURNING id")
                .param("t", p.tenantId).param("e", email).param("h", hash).param("n", input.fullName).param("a", input.isTenantAdmin)
                .query(UUID::class.java).single()
            setRoles(j, p.tenantId, id, input.roleIds)
            audit.record(j, p.tenantId, "user.create", "users", id, mapOf("email" to email, "is_tenant_admin" to input.isTenantAdmin, "role_ids" to input.roleIds))
            list(j, id).single()
        }
    }

    fun patch(id: UUID, input: UserPatchIn): UserOut {
        val p = Caller.user()
        p.require("users", Action.MANAGE)
        if (id == p.userId && (input.isActive == false || input.isTenantAdmin == false))
            throw Forbidden("You cannot disable yourself or remove your own admin rights.")
        return db.tenant(p.tenantId) { j ->
            val user = list(j, id).singleOrNull() ?: throw NotFound("User")
            if ((user.isTenantAdmin || input.isTenantAdmin == true) && !p.isAdmin) throw Forbidden("Only workspace admins can change workspace admins.")
            input.fullName?.let { j.sql("UPDATE app_users SET full_name = :n WHERE id = :id").param("n", it).param("id", id).update() }
            input.isTenantAdmin?.let { j.sql("UPDATE app_users SET is_tenant_admin = :a WHERE id = :id").param("a", it).param("id", id).update() }
            input.isActive?.let {
                j.sql("UPDATE app_users SET is_active = :a WHERE id = :id").param("a", it).param("id", id).update()
                if (!it) j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'user_disabled' WHERE user_id = :u AND revoked_at IS NULL")
                    .param("u", id).update()
            }
            if (input.unlock == true) j.sql("UPDATE app_users SET locked_until = NULL, failed_logins = 0 WHERE id = :id").param("id", id).update()
            audit.record(j, p.tenantId, "user.update", "users", id,
                mapOf("is_active" to input.isActive, "is_tenant_admin" to input.isTenantAdmin, "unlock" to input.unlock, "full_name_changed" to (input.fullName != null)))
            list(j, id).single()
        }
    }

    fun setUserRoles(id: UUID, roleIds: List<UUID>) {
        val p = Caller.user()
        p.require("users", Action.MANAGE)
        db.tenant(p.tenantId) { j ->
            val user = list(j, id).singleOrNull() ?: throw NotFound("User")
            if (user.isTenantAdmin && !p.isAdmin) throw Forbidden("Only workspace admins can change workspace admins.")
            // Removing roles the caller could not grant is also an escalation path in reverse; require the same.
            checkAssignable(j, p, (roleIds + user.roleIds).distinct())
            setRoles(j, p.tenantId, id, roleIds)
            audit.record(j, p.tenantId, "user.roles", "users", id, mapOf("role_ids" to roleIds))
        }
    }

    fun resetPassword(id: UUID, password: String) {
        val p = Caller.user()
        p.requireAdmin()
        passwords.validatePolicy(password)
        val hash = passwords.hash(password)
        db.tenant(p.tenantId) { j ->
            if (list(j, id).isEmpty()) throw NotFound("User")
            j.sql("UPDATE app_users SET password_hash = :h, failed_logins = 0, locked_until = NULL WHERE id = :id").param("h", hash).param("id", id).update()
            j.sql("UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'password_reset' WHERE user_id = :u AND revoked_at IS NULL").param("u", id).update()
            audit.record(j, p.tenantId, "user.password_reset", "users", id)
        }
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}")
@org.springframework.validation.annotation.Validated
class UserController(private val db: Db, private val users: UserService, private val roles: RoleService) {

    @GetMapping("/users")
    fun list(@RequestParam(required = false) @Size(max = 100) q: String?): Map<String, Any> {
        val p = Caller.user()
        p.require("users", Action.READ)
        return mapOf("items" to db.tenant(p.tenantId) { users.list(it, q = q) })
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: UserCreateIn) = users.create(body)

    @PatchMapping("/users/{id}")
    fun patch(@PathVariable id: UUID, @Valid @RequestBody body: UserPatchIn) = users.patch(id, body)

    @PutMapping("/users/{id}/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setRoles(@PathVariable id: UUID, @Valid @RequestBody body: UserRolesIn) = users.setUserRoles(id, body.roleIds)

    @PostMapping("/users/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetPassword(@PathVariable id: UUID, @Valid @RequestBody body: ResetPasswordIn) = users.resetPassword(id, body.password)

    @GetMapping("/roles")
    fun roles(): Map<String, Any> {
        val p = Caller.user()
        p.require("roles", Action.READ)
        return mapOf("items" to db.tenant(p.tenantId) { roles.list(it) })
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRole(@Valid @RequestBody body: RoleIn) = roles.create(body)

    @PatchMapping("/roles/{id}")
    fun patchRole(@PathVariable id: UUID, @Valid @RequestBody body: RolePatchIn) = roles.patch(id, body)

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRole(@PathVariable id: UUID) = roles.delete(id)

    /** What the role editor can offer: every resource, its allowed actions and whether its module is on. */
    @GetMapping("/permissions/catalog")
    fun catalog(): Map<String, Any> {
        val p = Caller.user()
        p.require("roles", Action.READ)
        return mapOf("items" to Resource.entries.map {
            mapOf(
                "resource" to it.code,
                "actions" to if (it.admin) listOf("read", "manage") else listOf("read", "create", "update", "delete", "manage"),
                "module" to it.module,
                "module_label" to it.module?.let { m -> Module.byCode[m]?.label },
                "module_enabled" to p.moduleEnabled(it.module),
            )
        })
    }
}
