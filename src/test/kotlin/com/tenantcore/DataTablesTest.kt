package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.LocalDate
import java.util.UUID

abstract class DataTest : IntegrationTest() {
    fun dataTenant() = newTenant(listOf("data_tables", "imports", "files"))

    fun f(key: String, type: String, vararg extra: Pair<String, Any?>) = mapOf("key" to key, "label" to key.replaceFirstChar { it.uppercase() }, "type" to type) + extra

    fun customers(t: Tenant): Res {
        val r = post("/data/tables", mapOf("key" to "customers", "name" to "Customers", "display_field" to "name", "fields" to listOf(
            f("name", "text", "required" to true, "max_length" to 100),
            f("email", "email", "unique" to true),
            f("tier", "select", "choices" to listOf("Gold", "Silver"), "default_value" to "Silver"),
            f("points", "integer", "min" to 0),
            f("balance", "decimal", "decimals" to 2),
            f("birthday", "date"),
            f("website", "url"),
            f("tags", "multi_select", "choices" to listOf("vip", "new")),
            f("notes", "long_text"),
            f("active", "boolean"))), t.token)
        check(r.status == 201) { r.toString() }
        return r
    }

    fun row(t: Tenant, table: String, data: Map<String, Any?>, token: String = t.token): Res = post("/data/tables/$table/rows", mapOf("data" to data), token)

    fun query(t: Tenant, table: String, body: Map<String, Any?>, token: String = t.token) = post("/data/tables/$table/rows/query", body, token)

    fun awaitImport(t: Tenant, id: String): Res {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val r = get("/data/imports/$id", t.token)
            if (r["status"].asText() in setOf("done", "failed", "cancelled")) return r
            Thread.sleep(150)
        }
        error("import $id did not finish")
    }

    fun names(r: Res): List<String> = r["items"].map { it["data"]["name"].asText() }
}

class DataTablesTest : DataTest() {

    @Test
    fun `tables and fields are defined from the dashboard and validated`() {
        val t = dataTenant()
        val c = customers(t)
        assertThat(c["fields"].map { it["key"].asText() }).contains("name", "email", "tags")
        assertThat(c["access"]["manage_schema"].asBoolean()).isTrue()
        assertThat(post("/data/tables", mapOf("key" to "customers", "name" to "Again"), t.token).status).isEqualTo(409)
        fun bad(vararg field: Map<String, Any?>) = post("/data/tables", mapOf("key" to "t${System.nanoTime() % 100000}", "name" to "x", "fields" to field.toList()), t.token).status
        assertThat(bad(f("id", "text"))).isEqualTo(422)                                     // reserved
        assertThat(bad(f("kind", "select"))).isEqualTo(422)                                 // no choices
        assertThat(bad(f("bio", "long_text", "unique" to true))).isEqualTo(422)             // cannot be unique
        assertThat(bad(f("owner", "reference", "reference_table" to "nope"))).isEqualTo(422)
        assertThat(bad(f("n", "integer", "min" to 5, "max" to 1))).isEqualTo(422)
        assertThat(bad(f("n", "integer", "default_value" to "ten"))).isEqualTo(422)
        assertThat(bad(f("n", "money"))).isEqualTo(422)
        assertThat(bad(f("Bad Key", "text"))).isEqualTo(422)
        assertThat(get("/data/tables", t.token)["items"].size()).isEqualTo(1)
        // Module off: refused.
        val other = newTenant(listOf("catalog"))
        assertThat(get("/data/tables", other.token).status).isEqualTo(403)
    }

    @Test
    fun `rows are validated value by value and stored in a normal form`() {
        val t = dataTenant(); customers(t)
        val ok = row(t, "customers", mapOf("name" to "Ann", "email" to "ANN@Example.com", "points" to 10, "balance" to 12.5,
            "birthday" to "1990-05-01", "website" to "https://ann.example", "tags" to listOf("VIP"), "active" to true))
        assertThat(ok.status).isEqualTo(201)
        assertThat(ok["data"]["email"].asText()).isEqualTo("ann@example.com")
        assertThat(ok["data"]["tier"].asText()).isEqualTo("Silver") // default
        assertThat(ok["data"]["tags"][0].asText()).isEqualTo("vip")
        assertThat(ok["data"]["balance"].decimalValue()).isEqualByComparingTo("12.5")

        val bad = row(t, "customers", mapOf("email" to "not-an-email", "points" to -1, "balance" to 1.234, "birthday" to "01/05/1990",
            "website" to "javascript:alert(1)", "tier" to "Platinum", "tags" to listOf("vip", "vip"), "active" to "yes"))
        assertThat(bad.status).isEqualTo(422)
        val problems = bad["error"]["details"].associate { it["loc"].last().asText() to it["msg"].asText() }
        assertThat(problems.keys).containsExactlyInAnyOrder("name", "email", "points", "balance", "birthday", "website", "tier", "tags", "active")
        assertThat(problems["name"]).isEqualTo("is required")
        assertThat(row(t, "customers", mapOf("name" to "X", "nope" to 1)).status).isEqualTo(422)
        assertThat(row(t, "customers", mapOf("name" to "x".repeat(101))).status).isEqualTo(422)

        // Unique ignores case; a version check stops lost updates.
        assertThat(row(t, "customers", mapOf("name" to "Ann 2", "email" to "ann@EXAMPLE.com")).status).isEqualTo(409)
        val id = ok["id"].asText()
        val changed = patch("/data/tables/customers/rows/$id", mapOf("data" to mapOf("points" to 20), "expected_version" to 1), t.token)
        assertThat(changed["version"].asInt()).isEqualTo(2)
        assertThat(changed["data"]["name"].asText()).isEqualTo("Ann")
        assertThat(patch("/data/tables/customers/rows/$id", mapOf("data" to mapOf("points" to 30), "expected_version" to 1), t.token).status).isEqualTo(409)
        assertThat(patch("/data/tables/customers/rows/$id", mapOf("data" to mapOf("name" to null)), t.token).status).isEqualTo(422)
        // Clearing an optional value removes it.
        assertThat(patch("/data/tables/customers/rows/$id", mapOf("data" to mapOf("website" to null)), t.token)["data"]["website"].isNull).isTrue()
        // The email can move to another row once freed.
        patch("/data/tables/customers/rows/$id", mapOf("data" to mapOf("email" to "ann2@example.com")), t.token)
        assertThat(row(t, "customers", mapOf("name" to "Ann 3", "email" to "ann@example.com")).status).isEqualTo(201)
        assertThat(delete("/data/tables/customers/rows/$id", t.token).status).isEqualTo(204)
        assertThat(get("/data/tables/customers/rows/$id", t.token).status).isEqualTo(404)
    }

    @Test
    fun `rows are filtered, searched and sorted`() {
        val t = dataTenant(); customers(t)
        row(t, "customers", mapOf("name" to "Ann Lee", "tier" to "Gold", "points" to 50, "birthday" to "1990-01-01", "tags" to listOf("vip"), "notes" to "likes tea"))
        row(t, "customers", mapOf("name" to "Bob Smith", "points" to 5, "birthday" to "1985-06-15", "tags" to listOf("new", "vip")))
        row(t, "customers", mapOf("name" to "Cara Lee", "points" to 20, "email" to "cara@example.com"))
        fun q(vararg filters: Map<String, Any?>, sort: List<Map<String, Any?>> = emptyList(), text: String? = null) =
            query(t, "customers", mapOf("filters" to filters.toList(), "sort" to sort, "q" to text))

        assertThat(names(q(mapOf("field" to "points", "op" to "gt", "value" to 10), sort = listOf(mapOf("field" to "points", "direction" to "desc")))))
            .containsExactly("Ann Lee", "Cara Lee")
        assertThat(names(q(mapOf("field" to "name", "op" to "contains", "value" to "lee")))).hasSize(2)
        assertThat(names(q(mapOf("field" to "tier", "op" to "eq", "value" to "gold")))).containsExactly("Ann Lee")
        assertThat(names(q(mapOf("field" to "tags", "op" to "has", "value" to "vip")))).containsExactlyInAnyOrder("Ann Lee", "Bob Smith")
        assertThat(names(q(mapOf("field" to "birthday", "op" to "lt", "value" to "1989-01-01")))).containsExactly("Bob Smith")
        assertThat(names(q(mapOf("field" to "email", "op" to "is_empty")))).containsExactlyInAnyOrder("Ann Lee", "Bob Smith")
        assertThat(names(q(mapOf("field" to "points", "op" to "in", "value" to listOf(5, 20))))).containsExactlyInAnyOrder("Bob Smith", "Cara Lee")
        assertThat(names(q(text = "tea"))).containsExactly("Ann Lee")
        assertThat(names(q(sort = listOf(mapOf("field" to "name", "direction" to "desc"))))).containsExactly("Cara Lee", "Bob Smith", "Ann Lee")
        assertThat(get("/data/tables/customers/rows?sort=-points&limit=1", t.token)["items"][0]["data"]["name"].asText()).isEqualTo("Ann Lee")
        assertThat(get("/data/tables/customers/rows?limit=1", t.token)["total"].asInt()).isEqualTo(3)
        // Bad filters are refused, not ignored.
        assertThat(q(mapOf("field" to "name", "op" to "gt", "value" to "a")).status).isEqualTo(422)
        assertThat(q(mapOf("field" to "points", "op" to "eq", "value" to "lots")).status).isEqualTo(422)
        assertThat(q(mapOf("field" to "ghost", "op" to "eq", "value" to 1)).status).isEqualTo(422)
        assertThat(q(mapOf("field" to "points", "op" to "drop table", "value" to 1)).status).isEqualTo(422)
        assertThat(get("/data/tables/customers/rows?sort=name;drop", t.token).status).isEqualTo(422)
    }

    @Test
    fun `roles limit rows to the user's own and hide or protect fields`() {
        val t = dataTenant(); customers(t)
        row(t, "customers", mapOf("name" to "Owner's customer", "points" to 99))
        val role = post("/roles", mapOf("name" to "Sales rep", "permissions" to listOf(
            mapOf("resource" to "data.customers", "action" to "read", "row_scope" to "own", "readable_fields" to listOf("name", "tier", "email")),
            mapOf("resource" to "data.customers", "action" to "create", "writable_fields" to listOf("name", "email")),
            mapOf("resource" to "data.customers", "action" to "update", "row_scope" to "own", "writable_fields" to listOf("name")))), t.token)
        assertThat(role.status).isEqualTo(201)
        val (_, rep) = newUser(t, listOf(role["id"].asText()))

        val mine = row(t, "customers", mapOf("name" to "Rep's customer", "email" to "rep@example.com"), rep)
        assertThat(mine.status).isEqualTo(201)
        assertThat(mine["data"].has("points")).isFalse()
        assertThat(mine["data"]["tier"].asText()).isEqualTo("Silver")
        assertThat(row(t, "customers", mapOf("name" to "x", "points" to 1), rep).status).isEqualTo(403)

        val list = get("/data/tables/customers/rows", rep)
        assertThat(names(list)).containsExactly("Rep's customer")
        val ownerRow = get("/data/tables/customers/rows", t.token)["items"].first { it["data"]["name"].asText() == "Owner's customer" }["id"].asText()
        assertThat(get("/data/tables/customers/rows/$ownerRow", rep).status).isEqualTo(404)
        assertThat(patch("/data/tables/customers/rows/$ownerRow", mapOf("data" to mapOf("name" to "x")), rep).status).isEqualTo(404)
        assertThat(patch("/data/tables/customers/rows/${mine["id"].asText()}", mapOf("data" to mapOf("email" to "e@example.com")), rep).status).isEqualTo(403)
        assertThat(delete("/data/tables/customers/rows/${mine["id"].asText()}", rep).status).isEqualTo(403)
        // Hidden fields cannot be used to probe values.
        assertThat(query(t, "customers", mapOf("filters" to listOf(mapOf("field" to "points", "op" to "gt", "value" to 50))), rep).status).isEqualTo(403)
        assertThat(get("/data/tables/customers/rows?sort=points", rep).status).isEqualTo(403)
        val described = get("/data/tables/customers", rep)
        assertThat(described["fields"].map { it["key"].asText() }).containsExactlyInAnyOrder("name", "tier", "email")
        assertThat(described["access"]["own_rows_only"].asBoolean()).isTrue()
        // Schema changes need data_schema:manage.
        assertThat(post("/data/tables/customers/fields", f("x", "text"), rep).status).isEqualTo(403)
        // The CSV export follows the same rules.
        val csv = String(raw("/api/v1/data/tables/customers/export", rep).body())
        assertThat(csv).contains("Rep's customer").doesNotContain("Owner's customer").doesNotContain("points")
    }

    @Test
    fun `references link tables and protect linked rows`() {
        val t = dataTenant(); customers(t)
        val visits = post("/data/tables", mapOf("key" to "visits", "name" to "Visits", "fields" to listOf(
            f("customer", "reference", "reference_table" to "customers", "required" to true),
            f("referrer", "reference", "reference_table" to "customers", "on_delete" to "set_null"),
            f("amount", "decimal"))), t.token)
        assertThat(visits.status).isEqualTo(201)
        val ann = row(t, "customers", mapOf("name" to "Ann"))["id"].asText()
        val bob = row(t, "customers", mapOf("name" to "Bob"))["id"].asText()
        val v = row(t, "visits", mapOf("customer" to ann, "referrer" to bob, "amount" to 30))
        assertThat(v["refs"]["customer"]["display"].asText()).isEqualTo("Ann")
        assertThat(row(t, "visits", mapOf("customer" to UUID.randomUUID().toString())).status).isEqualTo(422)

        assertThat(delete("/data/tables/customers/rows/$ann", t.token).status).isEqualTo(409)
        assertThat(delete("/data/tables/customers/rows/$bob", t.token).status).isEqualTo(204)
        assertThat(get("/data/tables/visits/rows/${v["id"].asText()}", t.token)["data"]["referrer"].isNull).isTrue()
        assertThat(delete("/data/tables/customers?confirm=customers", t.token).status).isEqualTo(409)

        // Another tenant's row id is simply not found.
        val other = dataTenant(); customers(other)
        val foreign = row(other, "customers", mapOf("name" to "Elsewhere"))["id"].asText()
        assertThat(row(t, "visits", mapOf("customer" to foreign)).status).isEqualTo(422)
        assertThat(get("/data/tables/customers/rows/$ann", other.token).status).isEqualTo(404)
    }

    @Test
    fun `schema changes are checked against the stored rows`() {
        val t = dataTenant(); customers(t)
        row(t, "customers", mapOf("name" to "Ann", "tier" to "Gold", "points" to 1))
        row(t, "customers", mapOf("name" to "Ann", "points" to 2))
        assertThat(post("/data/tables/customers/fields", f("code", "text", "required" to true), t.token).status).isEqualTo(409)
        val added = post("/data/tables/customers/fields", f("country", "text", "required" to true, "default_value" to "TH"), t.token)
        assertThat(added.status).isEqualTo(201)
        assertThat(get("/data/tables/customers/rows", t.token)["items"].all { it["data"]["country"].asText() == "TH" }).isTrue()

        assertThat(patch("/data/tables/customers/fields/name", mapOf("unique" to true), t.token).status).isEqualTo(409) // two "Ann"s
        assertThat(patch("/data/tables/customers/fields/points", mapOf("unique" to true), t.token).status).isEqualTo(200)
        assertThat(row(t, "customers", mapOf("name" to "Cy", "points" to 2)).status).isEqualTo(409)
        assertThat(patch("/data/tables/customers/fields/tier", mapOf("choices" to listOf("Silver")), t.token).status).isEqualTo(409)  // Gold is in use
        assertThat(patch("/data/tables/customers/fields/tier", mapOf("choices" to listOf("Gold", "Silver", "Bronze"), "label" to "Level"), t.token).status).isEqualTo(200)
        assertThat(patch("/data/tables/customers/fields/email", mapOf("required" to true), t.token).status).isEqualTo(409)

        val role = post("/roles", mapOf("name" to "Reader", "permissions" to listOf(
            mapOf("resource" to "data.customers", "action" to "read", "readable_fields" to listOf("name", "points")))), t.token)["id"].asText()
        assertThat(delete("/data/tables/customers/fields/points", t.token).status).isEqualTo(422) // needs confirm
        assertThat(delete("/data/tables/customers/fields/points?confirm=points", t.token).status).isEqualTo(204)
        assertThat(get("/data/tables/customers/rows", t.token)["items"].none { it["data"].has("points") }).isTrue()
        assertThat(get("/roles", t.token)["items"].first { it["id"].asText() == role }["permissions"][0]["readable_fields"].map { it.asText() }).containsExactly("name")
        superuser().use { c ->
            val n = c.createStatement().executeQuery("SELECT count(*) FROM data_rows WHERE tenant_id = '${t.id}' AND data ? 'points'").apply { next() }.getInt(1)
            assertThat(n).isZero()
        }
        assertThat(delete("/data/tables/customers?confirm=customers", t.token).status).isEqualTo(204)
        assertThat(get("/data/tables", t.token)["items"].size()).isZero()
    }

    @Test
    fun `data rows are isolated between tenants inside the database`() {
        val a = dataTenant(); customers(a)
        row(a, "customers", mapOf("name" to "Secret"))
        val b = dataTenant()
        assertThat(get("/data/tables/customers/rows", b.token).status).isEqualTo(404)
        appRole().use { c ->
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${b.id}', true)")
            assertThat(c.createStatement().executeQuery("SELECT count(*) FROM data_rows").apply { next() }.getInt(1)).isZero()
            assertThatThrownBy {
                c.createStatement().execute("INSERT INTO data_rows (tenant_id, table_id) VALUES ('${a.id}', gen_random_uuid())")
            }.isInstanceOf(java.sql.SQLException::class.java)
            c.rollback()
        }
    }
}

class DataImportTest : DataTest() {

    private fun csvUpload(t: Tenant, table: String, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8, query: String = "") =
        uploadTo("/api/v1/data/tables/$table/imports$query", t.token, "customers.csv", text.toByteArray(charset))

    @Test
    fun `csv imports preview, dry-run, then write valid rows and report the rest`() {
        val t = dataTenant(); customers(t)
        val csv = "Name,Email,Tier,Points,Birthday\r\n" +
            "\"Lee, Ann\",ann@example.com,gold,\"1,200\",1990-01-02\r\n" +
            "Bob,bob@example.com,,5,\r\n" +
            ",nobody@example.com,Silver,3,\r\n" +                // missing name
            "Cara,cara@example.com,Platinum,x,1990-13-01\r\n" +    // three bad values
            "\"Dan \"\"The Man\"\"\",dan@example.com,Silver,7,\r\n"
        val up = csvUpload(t, "customers", csv)
        assertThat(up.status).isEqualTo(201)
        assertThat(up["total_rows"].asInt()).isEqualTo(5)
        assertThat(up["preview"][0][0].asText()).isEqualTo("Lee, Ann")
        val mapping = up["suggested_mapping"]
        assertThat(mapping.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("Name", "Email", "Tier", "Points", "Birthday")
        val id = up["id"].asText()

        assertThat(post("/data/imports/$id/start", mapOf("mapping" to mapOf("Email" to "email")), t.token).status).isEqualTo(422) // name is required
        val m = mapper.convertValue(mapping, Map::class.java)
        post("/data/imports/$id/start", mapOf("mapping" to m, "dry_run" to true), t.token)
        val dry = awaitImport(t, id)
        assertThat(dry["status"].asText()).isEqualTo("done")
        assertThat(dry["inserted_rows"].asInt()).isEqualTo(3)
        assertThat(dry["failed_rows"].asInt()).isEqualTo(2)
        assertThat(get("/data/tables/customers/rows", t.token)["total"].asInt()).isZero() // nothing written

        post("/data/imports/$id/start", mapOf("mapping" to m), t.token)
        val done = awaitImport(t, id)
        assertThat(done["inserted_rows"].asInt()).isEqualTo(3)
        assertThat(done["errors"].map { it["row"].asInt() }.toSet()).containsExactlyInAnyOrder(4, 5)
        assertThat(done["errors"].filter { it["row"].asInt() == 5 }.map { it["field"].asText() }).contains("tier", "points", "birthday")
        val rows = get("/data/tables/customers/rows?sort=name", t.token)["items"]
        assertThat(rows.map { it["data"]["name"].asText() }).containsExactly("Bob", "Dan \"The Man\"", "Lee, Ann")
        assertThat(rows[2]["data"]["points"].asInt()).isEqualTo(1200)
        assertThat(rows[2]["data"]["tier"].asText()).isEqualTo("Gold")
        // A real import cannot be run twice.
        assertThat(post("/data/imports/$id/start", mapOf("mapping" to m), t.token).status).isEqualTo(409)

        // Upsert by the unique email updates instead of duplicating.
        val again = csvUpload(t, "customers", "email;points\nbob@example.com;77\nnew@example.com;1\n")
        post("/data/imports/${again["id"].asText()}/start", mapOf("mapping" to mapOf("email" to "email", "points" to "points"), "mode" to "upsert",
            "match_field" to "email"), t.token)
        val up2 = awaitImport(t, again["id"].asText())
        assertThat(up2["updated_rows"].asInt()).isEqualTo(1)
        assertThat(up2["failed_rows"].asInt()).isEqualTo(1) // the new one has no name
        assertThat(query(t, "customers", mapOf("filters" to listOf(mapOf("field" to "email", "op" to "eq", "value" to "bob@example.com"))))["items"][0]["data"]["points"].asInt())
            .isEqualTo(77)
    }

    @Test
    fun `encodings, excel files and references by name`() {
        val t = dataTenant(); customers(t)
        // A Thai Excel CSV is usually Windows-874, not UTF-8.
        val thai = "Name,Birthday\nสมชาย,15/08/1992\n"
        assertThat(csvUpload(t, "customers", thai, java.nio.charset.Charset.forName("windows-874")).status).isEqualTo(422)
        val up = csvUpload(t, "customers", thai, java.nio.charset.Charset.forName("windows-874"), "?encoding=windows-874")
        assertThat(up.status).isEqualTo(201)
        post("/data/imports/${up["id"].asText()}/start", mapOf("mapping" to mapOf("Name" to "name", "Birthday" to "birthday"), "date_format" to "dmy"), t.token)
        assertThat(awaitImport(t, up["id"].asText())["inserted_rows"].asInt()).isEqualTo(1)
        val somchai = get("/data/tables/customers/rows", t.token)["items"][0]
        assertThat(somchai["data"]["name"].asText()).isEqualTo("สมชาย")
        assertThat(somchai["data"]["birthday"].asText()).isEqualTo("1992-08-15")

        post("/data/tables", mapOf("key" to "visits", "name" to "Visits", "fields" to listOf(
            f("customer", "reference", "reference_table" to "customers", "required" to true), f("day", "date"), f("amount", "decimal"))), t.token)
        val out = java.io.ByteArrayOutputStream()
        val wb = org.dhatim.fastexcel.Workbook(out, "test", "1.0")
        val ws = wb.newWorksheet("Visits")
        ws.value(0, 0, "Customer"); ws.value(0, 1, "Day"); ws.value(0, 2, "Amount")
        ws.value(1, 0, "สมชาย"); ws.value(1, 1, LocalDate.of(2026, 3, 4)); ws.style(1, 1).format("yyyy-mm-dd").set(); ws.value(1, 2, 120.5)
        ws.value(2, 0, "Nobody"); ws.value(2, 2, 1)
        wb.finish()
        val x = uploadTo("/api/v1/data/tables/visits/imports", t.token, "visits.xlsx", out.toByteArray())
        assertThat(x.status).isEqualTo(201)
        assertThat(x["format"].asText()).isEqualTo("xlsx")
        post("/data/imports/${x["id"].asText()}/start", mapOf("mapping" to mapOf("Customer" to "customer", "Day" to "day", "Amount" to "amount")), t.token)
        val done = awaitImport(t, x["id"].asText())
        assertThat(done["inserted_rows"].asInt()).isEqualTo(1)
        assertThat(done["errors"][0]["message"].asText()).contains("Nobody")
        val visit = get("/data/tables/visits/rows", t.token)["items"][0]
        assertThat(visit["data"]["day"].asText()).isEqualTo("2026-03-04")
        assertThat(visit["data"]["amount"].decimalValue()).isEqualByComparingTo("120.5")
        assertThat(visit["refs"]["customer"]["display"].asText()).isEqualTo("สมชาย")

        // Not a spreadsheet at all.
        assertThat(uploadTo("/api/v1/data/tables/visits/imports", t.token, "x.xlsx", byteArrayOf(0x50, 0x4B, 3, 4, 1, 2, 3)).status).isEqualTo(422)
    }

    @Test
    fun `an interrupted import resumes after the last committed batch`() {
        val t = dataTenant(); customers(t)
        val csv = "Name\n" + (1..600).joinToString("\n") { "Person $it" } + "\n"
        val id = csvUpload(t, "customers", csv)["id"].asText()
        // As if a worker had committed the first batch (rows 1-250 are not in the table here) and then died.
        superuser().use { c ->
            c.createStatement().execute("""UPDATE data_imports SET status = 'running', mode = 'insert', dry_run = false, date_format = 'iso',
                mapping = '{"Name": "name"}', processed_rows = 250, lease_until = now() - interval '1 minute', attempts = 1 WHERE id = '$id'""")
        }
        val done = awaitImport(t, id)
        assertThat(done["status"].asText()).isEqualTo("done")
        assertThat(done["processed_rows"].asInt()).isEqualTo(600)
        assertThat(done["inserted_rows"].asInt()).isEqualTo(350)
        assertThat(get("/data/tables/customers/rows?limit=1", t.token)["total"].asInt()).isEqualTo(350)
        assertThat(query(t, "customers", mapOf("filters" to listOf(mapOf("field" to "name", "op" to "eq", "value" to "Person 251"))))["total"].asInt()).isEqualTo(1)
        assertThat(query(t, "customers", mapOf("filters" to listOf(mapOf("field" to "name", "op" to "eq", "value" to "Person 250"))))["total"].asInt()).isEqualTo(0)
    }

    @Test
    fun `exports are safe to open in a spreadsheet`() {
        val t = dataTenant(); customers(t)
        row(t, "customers", mapOf("name" to "=HYPERLINK(\"http://evil\")", "notes" to "line1\nline2, with comma", "tags" to listOf("vip", "new"), "points" to 3))
        val res = raw("/api/v1/data/tables/customers/export", t.token)
        assertThat(res.headers().firstValue("content-type").get()).startsWith("text/csv")
        val text = String(res.body(), Charsets.UTF_8)
        assertThat(text).startsWith("﻿id,name,email")
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"")
        assertThat(text).contains("\"line1\nline2, with comma\"")
        assertThat(text).contains("vip; new")
    }

    @Test
    fun `imports need the imports permission and write access to the fields`() {
        val t = dataTenant(); customers(t)
        val r1 = post("/roles", mapOf("name" to "Entry", "permissions" to listOf(
            mapOf("resource" to "data.customers", "action" to "create", "writable_fields" to listOf("name")),
            mapOf("resource" to "data.customers", "action" to "read"))), t.token)
        val (_, noImports) = newUser(t, listOf(r1["id"].asText()))
        assertThat(uploadTo("/api/v1/data/tables/customers/imports", noImports, "a.csv", "Name\nA\n".toByteArray()).status).isEqualTo(403)

        val r2 = post("/roles", mapOf("name" to "Importer", "permissions" to listOf(
            mapOf("resource" to "data.customers", "action" to "create", "writable_fields" to listOf("name")),
            mapOf("resource" to "imports", "action" to "create"), mapOf("resource" to "imports", "action" to "read"))), t.token)
        val (_, importer) = newUser(t, listOf(r2["id"].asText()))
        val up = uploadTo("/api/v1/data/tables/customers/imports", importer, "a.csv", "Name,Points\nA,1\n".toByteArray())
        assertThat(up.status).isEqualTo(201)
        assertThat(call(HttpMethod.POST, "/api/v1/data/imports/${up["id"].asText()}/start",
            mapOf("mapping" to mapOf("Name" to "name", "Points" to "points")), importer).status).isEqualTo(403)
        assertThat(call(HttpMethod.POST, "/api/v1/data/imports/${up["id"].asText()}/start", mapOf("mapping" to mapOf("Name" to "name")), importer).status).isEqualTo(200)
        assertThat(awaitImport(t, up["id"].asText())["inserted_rows"].asInt()).isEqualTo(1)
        // Another tenant cannot see the import.
        assertThat(get("/data/imports/${up["id"].asText()}", dataTenant().token).status).isEqualTo(404)
    }
}
