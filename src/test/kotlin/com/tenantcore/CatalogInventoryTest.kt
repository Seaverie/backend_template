package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogTest : CommerceTest() {

    private val png = java.io.ByteArrayOutputStream().also {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test
    fun `products with variants are created, searched, edited and validated`() {
        val t = shop()
        val r = post("/products", mapOf("name" to "Café T-Shirt", "description" to "Soft", "status" to "active", "attributes" to mapOf("material" to "cotton"),
            "variants" to listOf(mapOf("sku" to "TS-S", "price" to dec("12.50"), "options" to mapOf("size" to "S")),
                mapOf("sku" to "TS-M", "price" to dec("12.50"), "options" to mapOf("size" to "M"), "barcode" to "8850001")) ), t.token)
        assertThat(r.status).isEqualTo(201)
        assertThat(r["slug"].asText()).isEqualTo("cafe-t-shirt")
        assertThat(r["variants"].size()).isEqualTo(2)
        assertThat(r["variants"][0]["stock"]["available"].asInt()).isZero() // inventory module is on
        val id = r["id"].asText()

        assertThat(get("/products?q=TS-M", t.token)["total"].asInt()).isEqualTo(1)
        assertThat(get("/products?q=8850001", t.token)["total"].asInt()).isEqualTo(1)
        assertThat(get("/products?status=draft", t.token)["total"].asInt()).isZero()

        // Validation: bad price precision, duplicate SKU, unknown field (a client cannot invent columns).
        assertThat(post("/products", mapOf("name" to "x", "variants" to listOf(mapOf("sku" to "A1", "price" to dec("1.234")))), t.token).status).isEqualTo(422)
        assertThat(post("/products", mapOf("name" to "x", "variants" to listOf(mapOf("sku" to "TS-S", "price" to 1))), t.token).status).isEqualTo(409)
        assertThat(post("/products", mapOf("name" to "x", "variants" to emptyList<Any>()), t.token).status).isEqualTo(422)
        assertThat(post("/products", mapOf("name" to "x", "hack" to 1, "variants" to listOf(mapOf("sku" to "Z1", "price" to 1))), t.token).status).isEqualTo(422)

        val patched = patch("/products/$id", mapOf("name" to "Café Tee", "status" to "archived"), t.token)
        assertThat(patched["name"].asText()).isEqualTo("Café Tee")
        assertThat(patched["slug"].asText()).isEqualTo("cafe-t-shirt")

        val vid = r["variants"][1]["id"].asText()
        val v = patch("/products/$id/variants/$vid", mapOf("price" to dec("15"), "clear" to listOf("barcode")), t.token)
        assertThat(v["variants"].first { it["id"].asText() == vid }["price"].decimalValue()).isEqualByComparingTo("15")
        assertThat(v["variants"].first { it["id"].asText() == vid }["barcode"].isNull).isTrue()
        assertThat(patch("/products/$id/variants/$vid", mapOf("clear" to listOf("price")), t.token).status).isEqualTo(422)

        val added = post("/products/$id/variants", mapOf("sku" to "TS-L", "price" to 13), t.token)
        assertThat(added["variants"].size()).isEqualTo(3)
        val lid = added["variants"].first { it["sku"].asText() == "TS-L" }["id"].asText()
        assertThat(delete("/products/$id/variants/$lid", t.token).status).isEqualTo(200)
    }

    @Test
    fun `categories nest without loops`() {
        val t = shop()
        val a = post("/product-categories", mapOf("name" to "Clothing"), t.token)
        val b = post("/product-categories", mapOf("name" to "Shirts", "parent_id" to a["id"].asText()), t.token)
        assertThat(b["parent_id"].asText()).isEqualTo(a["id"].asText())
        assertThat(patch("/product-categories/${a["id"].asText()}", mapOf("parent_id" to b["id"].asText()), t.token).status).isEqualTo(422)
        val p = post("/products", mapOf("name" to "Polo", "category_id" to b["id"].asText(), "variants" to listOf(mapOf("sku" to "P1", "price" to 5))), t.token)
        product(t) // not in the category
        assertThat(get("/products?category_id=${b["id"].asText()}", t.token)["total"].asInt()).isEqualTo(1)
        assertThat(get("/product-categories", t.token)["items"].first { it["slug"].asText() == "shirts" }["product_count"].asInt()).isEqualTo(1)
        // Deleting the category keeps the product.
        assertThat(delete("/product-categories/${b["id"].asText()}", t.token).status).isEqualTo(204)
        assertThat(get("/products/${p["id"].asText()}", t.token)["category_id"].isNull).isTrue()
    }

    @Test
    fun `product images must be the tenant's images and become public`() {
        val t = shop()
        val img = upload(t.token, "shirt.png", png)
        val doc = upload(t.token, "notes.txt", "hello".toByteArray())
        val p = product(t)
        assertThat(patch("/products/${p.id}", mapOf("image_file_ids" to listOf(doc["id"].asText())), t.token).status).isEqualTo(422)
        val r = patch("/products/${p.id}", mapOf("image_file_ids" to listOf(img["id"].asText())), t.token)
        assertThat(r.status).isEqualTo(200)
        val url = r["images"][0]["url"].asText()
        assertThat(raw(url).statusCode()).isEqualTo(200) // public now, no sign-in
        assertThat(get("/files/${img["id"].asText()}", t.token)["purpose"].asText()).isEqualTo("product")

        // Another tenant's image cannot be attached.
        val other = shop()
        val foreign = upload(other.token, "x.png", png)
        assertThat(patch("/products/${p.id}", mapOf("image_file_ids" to listOf(foreign["id"].asText())), t.token).status).isEqualTo(422)
        // Deleting the file removes it from the product.
        delete("/files/${img["id"].asText()}", t.token)
        assertThat(get("/products/${p.id}", t.token)["images"].size()).isZero()
    }

    @Test
    fun `products with history cannot be deleted, only archived`() {
        val t = shop()
        val clean = product(t)
        assertThat(delete("/products/${clean.id}", t.token).status).isEqualTo(204)
        val used = product(t)
        warehouse(t); addStock(t, used.variantId, 3)
        val res = delete("/products/${used.id}", t.token)
        assertThat(res.status).isEqualTo(409)
        assertThat(res["error"]["message"].asText()).contains("Archive")
    }

    @Test
    fun `module switches and permissions gate the catalog, and tenants are isolated`() {
        val t = newTenant(listOf("orders", "catalog"))
        val p = product(t)
        val token = platformToken()
        put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("orders" to false, "catalog" to false)), token)
        val off = get("/products", login(t.slug, t.ownerEmail)["access_token"].asText())
        assertThat(off.status).isEqualTo(403)
        assertThat(off["error"]["message"].asText()).contains("Catalog")
        put("/platform/tenants/${t.id}/modules", mapOf("modules" to mapOf("catalog" to true)), token)
        val owner = login(t.slug, t.ownerEmail)["access_token"].asText()

        val reader = post("/roles", mapOf("name" to "Catalog viewer", "permissions" to listOf(mapOf("resource" to "products", "action" to "read"))), owner)
        val (_, member) = newUser(t, listOf(reader["id"].asText()))
        assertThat(get("/products", member)["total"].asInt()).isEqualTo(1)
        assertThat(post("/products", mapOf("name" to "x", "variants" to listOf(mapOf("sku" to "Q1", "price" to 1))), member).status).isEqualTo(403)
        assertThat(patch("/products/${p.id}", mapOf("name" to "x"), member).status).isEqualTo(403)

        val other = shop()
        assertThat(get("/products/${p.id}", other.token).status).isEqualTo(404)
        assertThat(get("/products", other.token)["total"].asInt()).isZero()
        // Two tenants can use the same SKU.
        assertThat(post("/products", mapOf("name" to "Same", "variants" to listOf(mapOf("sku" to p.sku, "price" to 1))), other.token).status).isEqualTo(201)
    }
}

class InventoryTest : CommerceTest() {

    @Test
    fun `warehouses get a main bin and the first one is the default`() {
        val t = shop()
        val a = post("/inventory/warehouses", mapOf("code" to "BKK", "name" to "Bangkok"), t.token)
        assertThat(a["is_default"].asBoolean()).isTrue()
        assertThat(a["bins"].map { it["code"].asText() }).containsExactly("MAIN")
        val b = post("/inventory/warehouses", mapOf("code" to "CNX", "name" to "Chiang Mai"), t.token)
        assertThat(b["is_default"].asBoolean()).isFalse()
        assertThat(post("/inventory/warehouses", mapOf("code" to "BKK", "name" to "Dup"), t.token).status).isEqualTo(409)
        assertThat(patch("/inventory/warehouses/${b["id"].asText()}", mapOf("is_default" to true), t.token)["is_default"].asBoolean()).isTrue()
        assertThat(get("/inventory/warehouses", t.token)["items"].count { it["is_default"].asBoolean() }).isEqualTo(1)
        assertThat(patch("/inventory/warehouses/${b["id"].asText()}", mapOf("is_active" to false), t.token).status).isEqualTo(422)
        val bin = post("/inventory/warehouses/${a["id"].asText()}/bins", mapOf("code" to "A-01"), t.token)
        assertThat(bin["bins"].size()).isEqualTo(2)
    }

    @Test
    fun `adjustments, counts and transfers keep an exact ledger`() {
        val t = shop()
        val w = warehouse(t)
        val shelf = post("/inventory/warehouses/${w.id}/bins", mapOf("code" to "SHELF"), t.token)["bins"].first { it["code"].asText() == "SHELF" }["id"].asText()
        val p = product(t)
        assertThat(addStock(t, p.variantId, 10)["on_hand"].asInt()).isEqualTo(10)
        // A stock count sets the number directly.
        val counted = post("/inventory/adjustments", mapOf("variant_id" to p.variantId, "bin_id" to w.mainBin, "set_to" to 8, "note" to "count"), t.token)
        assertThat(counted["on_hand"].asInt()).isEqualTo(8)
        val moved = post("/inventory/transfers", mapOf("variant_id" to p.variantId, "from_bin_id" to w.mainBin, "to_bin_id" to shelf, "quantity" to 3), t.token)
        assertThat(moved["on_hand"].asInt()).isEqualTo(8)
        assertThat(moved["bins"].associate { it["bin_code"].asText() to it["on_hand"].asInt() }).isEqualTo(mapOf("MAIN" to 5, "SHELF" to 3))
        // Cannot remove more than is there, or transfer more than is available.
        assertThat(post("/inventory/adjustments", mapOf("variant_id" to p.variantId, "bin_id" to shelf, "quantity" to -4), t.token).status).isEqualTo(409)
        assertThat(post("/inventory/transfers", mapOf("variant_id" to p.variantId, "from_bin_id" to shelf, "to_bin_id" to w.mainBin, "quantity" to 4), t.token).status).isEqualTo(409)
        assertThat(post("/inventory/adjustments", mapOf("variant_id" to p.variantId, "quantity" to 1, "set_to" to 1), t.token).status).isEqualTo(422)

        val ledger = get("/inventory/movements?variant_id=${p.variantId}", t.token)["items"]
        assertThat(ledger.map { it["quantity"].asInt() }.sum()).isEqualTo(8)
        assertThat(ledger.map { it["kind"].asText() }.toSet()).containsExactlyInAnyOrder("adjustment", "transfer")

        // An untracked variant has no stock to adjust.
        val service = product(t, track = false)
        assertThat(post("/inventory/adjustments", mapOf("variant_id" to service.variantId, "quantity" to 1), t.token).status).isEqualTo(422)
    }

    @Test
    fun `low stock report uses each variant's threshold`() {
        val t = shop()
        warehouse(t)
        val low = product(t, name = "Low")
        val ok = product(t, name = "Ok")
        patch("/products/${low.id}/variants/${low.variantId}", mapOf("low_stock_threshold" to 5), t.token)
        addStock(t, low.variantId, 4); addStock(t, ok.variantId, 50)
        val items = get("/inventory/stock?low_only=true", t.token)["items"]
        assertThat(items.map { it["sku"].asText() }).containsExactly(low.sku)
        assertThat(items[0]["is_low"].asBoolean()).isTrue()
    }

    @Test
    fun `the stock ledger is append-only and stock rows obey their limits in the database`() {
        val t = shop()
        warehouse(t)
        val p = product(t)
        addStock(t, p.variantId, 2)
        appRole().use { c ->
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${t.id}', true)")
            assertThatThrownBy { c.createStatement().execute("DELETE FROM stock_movements") }.hasMessageContaining("permission denied")
            c.rollback()
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${t.id}', true)")
            assertThatThrownBy { c.createStatement().execute("UPDATE stock_levels SET reserved = on_hand + 1") }.hasMessageContaining("check constraint")
            c.rollback()
            c.createStatement().execute("SELECT set_config('app.tenant_id', '${t.id}', true)")
            assertThatThrownBy { c.createStatement().execute("UPDATE stock_levels SET on_hand = -1") }.hasMessageContaining("check constraint")
            c.rollback()
        }
        superuser().use { c -> assertThatThrownBy { c.createStatement().execute("DELETE FROM stock_movements") }.hasMessageContaining("append-only") }
    }

    @Test
    fun `purchase orders receive stock into their warehouse`() {
        val t = shop()
        val w = warehouse(t)
        val other = warehouse(t)
        val p1 = product(t); val p2 = product(t)
        val sup = post("/suppliers", mapOf("name" to "Acme Supply", "email" to "Sales@Acme.example"), t.token)
        assertThat(sup["email"].asText()).isEqualTo("sales@acme.example")
        val po = post("/purchase-orders", mapOf("supplier_id" to sup["id"].asText(), "warehouse_id" to w.id,
            "lines" to listOf(mapOf("variant_id" to p1.variantId, "quantity" to 10, "unit_cost" to dec("2.50")),
                mapOf("variant_id" to p2.variantId, "quantity" to 4, "unit_cost" to 1))), t.token)
        assertThat(po.status).isEqualTo(201)
        assertThat(po["total"].decimalValue()).isEqualByComparingTo("29.00")
        val id = po["id"].asText()
        val l1 = po["lines"].first { it["variant_id"].asText() == p1.variantId }["id"].asText()
        val l2 = po["lines"].first { it["variant_id"].asText() == p2.variantId }["id"].asText()

        assertThat(post("/purchase-orders/$id/receive", mapOf("lines" to listOf(mapOf("line_id" to l1, "quantity" to 1))), t.token).status).isEqualTo(409) // still a draft
        assertThat(post("/purchase-orders/$id/submit", null, t.token)["status"].asText()).isEqualTo("ordered")
        assertThat(patch("/purchase-orders/$id", mapOf("notes" to "x"), t.token).status).isEqualTo(409)

        val part = post("/purchase-orders/$id/receive", mapOf("lines" to listOf(mapOf("line_id" to l1, "quantity" to 6))), t.token)
        assertThat(part["status"].asText()).isEqualTo("partially_received")
        assertThat(post("/purchase-orders/$id/receive", mapOf("lines" to listOf(mapOf("line_id" to l1, "quantity" to 5))), t.token).status).isEqualTo(422)
        assertThat(post("/purchase-orders/$id/receive", mapOf("lines" to listOf(mapOf("line_id" to l2, "quantity" to 1, "bin_id" to other.mainBin))), t.token).status)
            .isEqualTo(422) // wrong warehouse
        val done = post("/purchase-orders/$id/receive", mapOf("lines" to listOf(mapOf("line_id" to l1, "quantity" to 4), mapOf("line_id" to l2, "quantity" to 4))), t.token)
        assertThat(done["status"].asText()).isEqualTo("received")
        assertThat(stockOf(t, p1.sku)["on_hand"].asInt()).isEqualTo(10)
        assertThat(get("/inventory/movements?kind=purchase", t.token)["total"].asInt()).isEqualTo(3)
        assertThat(post("/purchase-orders/$id/cancel", null, t.token).status).isEqualTo(409)
        assertThat(delete("/purchase-orders/$id", t.token).status).isEqualTo(409)
    }
}
