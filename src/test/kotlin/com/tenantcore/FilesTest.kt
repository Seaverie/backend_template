package com.tenantcore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

class FilesTest : IntegrationTest() {

    private fun png(w: Int = 1200, h: Int = 800): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color(79, 70, 229); fillRect(0, 0, w, h); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun withFiles(): Tenant = newTenant(modules = listOf("files"))

    @Test
    fun `images are detected from their bytes, measured and get a thumbnail`() {
        val t = withFiles()
        val bytes = png()
        val r = upload(t.token, "photo.jpg", bytes, "?purpose=image") // wrong extension on purpose
        assertThat(r.status).isEqualTo(201)
        assertThat(r["content_type"].asText()).isEqualTo("image/png")
        assertThat(r["width"].asInt()).isEqualTo(1200)
        assertThat(r["height"].asInt()).isEqualTo(800)
        assertThat(r["has_thumbnail"].asBoolean()).isTrue()
        assertThat(r["sha256"].asText()).isEqualTo(sha(bytes))
        assertThat(r["url"].isNull).isTrue() // private by default

        val id = r["id"].asText()
        val content = raw("/api/v1/files/$id/content", t.token)
        assertThat(content.statusCode()).isEqualTo(200)
        assertThat(content.body()).isEqualTo(bytes)
        assertThat(content.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff")
        val thumb = raw("/api/v1/files/$id/thumbnail", t.token)
        val small = ImageIO.read(thumb.body().inputStream())
        assertThat(maxOf(small.width, small.height)).isEqualTo(480)

        assertThat(get("/files?purpose=image", t.token)["total"].asInt()).isEqualTo(1)
        assertThat(get("/files/usage", t.token)["used_bytes"].asLong()).isEqualTo(bytes.size.toLong())
    }

    @Test
    fun `dangerous or unknown files are refused`() {
        val t = withFiles()
        assertThat(upload(t.token, "page.html", "<html><script>alert(1)</script></html>".toByteArray()).status).isEqualTo(415)
        assertThat(upload(t.token, "logo.svg", "<svg xmlns='http://www.w3.org/2000/svg'><script/></svg>".toByteArray()).status).isEqualTo(415)
        assertThat(upload(t.token, "notes.txt", "hello <script>alert(1)</script>".toByteArray()).status).isEqualTo(415)
        assertThat(upload(t.token, "setup.exe", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0, 3, 0, 0, 0)).status).isEqualTo(415)
        assertThat(upload(t.token, "empty.txt", ByteArray(0)).status).isEqualTo(422)
        // Text named like an image is stored as text, never served as an image.
        assertThat(upload(t.token, "fake.png", "just text".toByteArray())["content_type"].asText()).isEqualTo("text/plain")
        assertThat(upload(t.token, "data.csv", "a,b\n1,2\n".toByteArray())["content_type"].asText()).isEqualTo("text/csv")
        assertThat(upload(t.token, "doc.pdf", "%PDF-1.4\n%%EOF".toByteArray())["content_type"].asText()).isEqualTo("application/pdf")
    }

    @Test
    fun `size limit and quota are configurable per tenant`() {
        val t = withFiles()
        val token = platformToken()
        assertThat(put("/platform/tenants/${t.id}/modules/files/config", mapOf("max_file_mb" to 1, "quota_mb" to 2), token).status).isEqualTo(200)
        val big = ByteArray(1_500_000) { 'a'.code.toByte() }
        assertThat(upload(t.token, "big.txt", big).status).isEqualTo(413)
        val ok = ByteArray(900_000) { 'b'.code.toByte() }
        assertThat(upload(t.token, "a.txt", ok).status).isEqualTo(201)
        assertThat(upload(t.token, "b.txt", ok).status).isEqualTo(201)
        val over = upload(t.token, "c.txt", ok)
        assertThat(over.status).isEqualTo(413)
        assertThat(over["error"]["message"].asText()).contains("quota")
        assertThat(get("/files/usage", t.token)["quota_bytes"].asLong()).isEqualTo(2L * 1024 * 1024)
    }

    @Test
    fun `the files module and permissions gate everything`() {
        val off = newTenant(modules = listOf("catalog"))
        assertThat(upload(off.token, "a.txt", "x".toByteArray()).status).isEqualTo(403)
        val t = withFiles()
        val r = post("/roles", mapOf("name" to "Uploader", "permissions" to listOf(
            mapOf("resource" to "files", "action" to "create"), mapOf("resource" to "files", "action" to "read"))), t.token)["id"].asText()
        val (_, uploader) = newUser(t, listOf(r))
        val (_, nobody) = newUser(t)
        val ownerFile = upload(t.token, "owner.txt", "owner".toByteArray())["id"].asText()
        val mine = upload(uploader, "mine.txt", "mine".toByteArray())["id"].asText()
        assertThat(get("/files", nobody).status).isEqualTo(403)
        assertThat(upload(nobody, "x.txt", "x".toByteArray()).status).isEqualTo(403)
        // Uploaders delete their own files but not other people's.
        assertThat(delete("/files/$ownerFile", uploader).status).isEqualTo(403)
        assertThat(delete("/files/$mine", uploader).status).isEqualTo(204)
        assertThat(get("/files/$mine", t.token).status).isEqualTo(404)
    }

    @Test
    fun `tenants cannot reach each other's files`() {
        val a = withFiles(); val b = withFiles()
        val f = upload(a.token, "secret.txt", "a's secret".toByteArray())["id"].asText()
        assertThat(get("/files/$f", b.token).status).isEqualTo(404)
        assertThat(raw("/api/v1/files/$f/content", b.token).statusCode()).isEqualTo(404)
        assertThat(delete("/files/$f", b.token).status).isEqualTo(404)
        // Even made public, it is only reachable under its own tenant's path.
        patch("/files/$f", mapOf("is_public" to true), a.token)
        assertThat(raw("/api/v1/public/files/${b.id}/$f").statusCode()).isEqualTo(404)
        assertThat(raw("/api/v1/public/files/${a.id}/$f").statusCode()).isEqualTo(200)
    }

    @Test
    fun `private files open only through a valid signed link`() {
        val t = withFiles()
        val f = upload(t.token, "contract.pdf", "%PDF-1.4 secret".toByteArray())["id"].asText()
        assertThat(raw("/api/v1/public/files/${t.id}/$f").statusCode()).isEqualTo(404)
        val link = post("/files/$f/link", null, t.token)["url"].asText()
        assertThat(raw(link).statusCode()).isEqualTo(200)
        assertThat(raw(link).headers().firstValue("Cache-Control")).hasValue("private, no-store")
        assertThat(raw(link.replace(Regex("sig=.{4}"), "sig=AAAA")).statusCode()).isEqualTo(404)
        val exp = Regex("exp=(\\d+)").find(link)!!.groupValues[1].toLong()
        assertThat(raw(link.replace("exp=$exp", "exp=${exp + 1000}")).statusCode()).isEqualTo(404) // signature covers expiry
        val other = withFiles()
        assertThat(raw(link.replace(t.id, other.id)).statusCode()).isEqualTo(404)
    }

    @Test
    fun `a logo becomes public and appears in settings`() {
        val t = withFiles()
        val logo = upload(t.token, "logo.png", png(300, 100))["id"].asText()
        val doc = upload(t.token, "doc.pdf", "%PDF-1.4".toByteArray())["id"].asText()
        assertThat(put("/settings", mapOf("logo_file_id" to doc), t.token).status).isEqualTo(422)
        val s = put("/settings", mapOf("logo_file_id" to logo), t.token)
        assertThat(s.status).isEqualTo(200)
        val url = s["logo_url"].asText()
        assertThat(raw(url).statusCode()).isEqualTo(200) // no sign-in needed
        assertThat(raw(url).headers().firstValue("Cache-Control").get()).startsWith("public")
        assertThat(get("/auth/me", t.token)["settings"]["logo_file_id"].asText()).isEqualTo(logo)
        assertThat(put("/settings", mapOf("remove_logo" to true), t.token)["logo_url"].isNull).isTrue()
        assertThat(put("/settings", mapOf("logo_file_id" to UUID.randomUUID()), t.token).status).isEqualTo(404)
    }

    @Test
    fun `storage can be switched to S3 from the dashboard without breaking existing files`() {
        val t = withFiles()
        val token = platformToken()
        val before = upload(t.token, "local.txt", "on disk".toByteArray())["id"].asText()
        val s3Settings = mapOf("endpoint" to "http://${s3.host}:${s3.getMappedPort(9090)}", "region" to "us-east-1", "bucket" to "tc-test",
            "access_key_id" to "test", "path_style" to true, "prefix" to "tenantcore")
        // A bucket that does not exist is rejected before it is saved.
        assertThat(put("/platform/storage", mapOf("driver" to "s3", "s3" to s3Settings + ("bucket" to "no-such-bucket"), "s3_secret_access_key" to "x"), token).status)
            .isEqualTo(422)
        val saved = put("/platform/storage", mapOf("driver" to "s3", "s3" to s3Settings, "s3_secret_access_key" to "test-secret"), token)
        assertThat(saved.status).isEqualTo(200)
        assertThat(saved["s3_secret_set"].asBoolean()).isTrue()
        assertThat(saved.body.toString()).doesNotContain("test-secret")
        try {
            assertThat(post("/platform/storage/test", null, token)["ok"].asBoolean()).isTrue()
            val inS3 = upload(t.token, "cloud.png", png(64, 64))["id"].asText()
            superuser().use { c ->
                val rs = c.createStatement().executeQuery("SELECT storage FROM files WHERE id IN ('$before', '$inS3') ORDER BY created_at")
                val stores = generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                assertThat(stores).containsExactly("local", "s3")
            }
            assertThat(raw("/api/v1/files/$inS3/thumbnail", t.token).statusCode()).isEqualTo(200)
            assertThat(String(raw("/api/v1/files/$before/content", t.token).body())).isEqualTo("on disk")
        } finally {
            put("/platform/storage", mapOf("driver" to "local"), token)
        }
        // Back on local disk: the file in S3 is still served from S3.
        val inS3Again = get("/files?q=cloud", t.token)["items"][0]["id"].asText()
        assertThat(raw("/api/v1/files/$inS3Again/content", t.token).statusCode()).isEqualTo(200)
    }
}
