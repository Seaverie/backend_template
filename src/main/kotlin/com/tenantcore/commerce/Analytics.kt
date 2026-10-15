package com.tenantcore.commerce

import com.tenantcore.common.Invalid
import com.tenantcore.config.Caller
import com.tenantcore.db.Db
import com.tenantcore.security.Action
import com.tenantcore.security.UserPrincipal
import jakarta.validation.constraints.Pattern
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class SalesPoint(val period: LocalDate, val orders: Long, val revenue: BigDecimal)
data class TopProduct(val variantId: UUID, val sku: String, val name: String, val quantity: Long, val revenue: BigDecimal)
data class SalesReport(
    val from: LocalDate, val to: LocalDate, val interval: String, val timezone: String, val currency: String,
    val orders: Long, val revenue: BigDecimal, val averageOrderValue: BigDecimal, val itemsSold: Long, val refunded: BigDecimal,
    val series: List<SalesPoint>, val topProducts: List<TopProduct>, val byStatus: Map<String, Long>,
)

/**
 * Sales figures computed live from the orders tables. "Sales" are orders in statuses marked
 * counts_as_sale in the lifecycle; days are in the tenant's timezone.
 */
@Service
class AnalyticsService(private val db: Db, private val rows: Rows, private val orders: OrderService) {

    fun sales(p: UserPrincipal, from: LocalDate?, to: LocalDate?, interval: String): SalesReport {
        p.require("analytics", Action.READ)
        return db.tenant(p.tenantId) { j ->
            orders.lifecycle(j, p.tenantId)
            val tz = j.sql("SELECT timezone FROM tenant_settings").query(String::class.java).optional().orElse("UTC")
            val today = LocalDate.now(ZoneId.of(tz))
            val end = to ?: today
            val start = from ?: end.minusDays(29)
            if (start > end) throw Invalid("from must be on or before to.")
            if (ChronoUnit.DAYS.between(start, end) > 731) throw Invalid("The range can be at most two years.")
            val range = "o.placed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE :tz) AND o.placed_at < ((CAST(:to AS date) + 1)::timestamp AT TIME ZONE :tz)"
            val sale = "o.status IN (SELECT code FROM order_statuses WHERE counts_as_sale)"
            fun q(sql: String) = j.sql(sql).param("from", start).param("to", end).param("tz", tz)

            val (count, revenue, refunded) = q("SELECT count(*), coalesce(sum(o.total), 0), coalesce(sum(o.refunded_total), 0) FROM orders o WHERE $range AND $sale")
                .query { rs, _ -> Triple(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)) }.single()
            val items = q("SELECT coalesce(sum(oi.quantity), 0) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE $range AND $sale")
                .query(Long::class.java).single()
            val points = q("""SELECT date_trunc(CAST(:i AS text), o.placed_at AT TIME ZONE :tz)::date, count(*), coalesce(sum(o.total), 0)
                              FROM orders o WHERE $range AND $sale GROUP BY 1""")
                .param("i", interval).query { rs, _ -> rs.getObject(1, LocalDate::class.java) to (rs.getLong(2) to rs.getBigDecimal(3)) }.list().toMap()
            val top = q("""SELECT oi.variant_id, oi.sku, min(oi.name), sum(oi.quantity), sum(oi.line_total) FROM order_items oi JOIN orders o ON o.id = oi.order_id
                           WHERE $range AND $sale GROUP BY oi.variant_id, oi.sku ORDER BY 5 DESC, 4 DESC LIMIT 10""")
                .query { rs, _ -> TopProduct(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBigDecimal(5)) }.list()
            val byStatus = q("SELECT o.status, count(*) FROM orders o WHERE $range GROUP BY o.status ORDER BY 2 DESC")
                .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

            SalesReport(start, end, interval, tz, rows.currency(j), count, revenue,
                if (count == 0L) Money.ZERO else revenue.divide(BigDecimal(count), 2, RoundingMode.HALF_UP), items, refunded,
                periods(start, end, interval).map { d -> points[d]?.let { SalesPoint(d, it.first, it.second) } ?: SalesPoint(d, 0, Money.ZERO) },
                top, byStatus)
        }
    }

    /** Every period in the range, so charts show empty days as zero. Weeks start on Monday, as in PostgreSQL. */
    private fun periods(start: LocalDate, end: LocalDate, interval: String): List<LocalDate> {
        var d = when (interval) {
            "week" -> start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> start.withDayOfMonth(1)
            else -> start
        }
        val out = ArrayList<LocalDate>()
        while (d <= end) {
            out += d
            d = when (interval) { "week" -> d.plusWeeks(1); "month" -> d.plusMonths(1); else -> d.plusDays(1) }
        }
        return out
    }
}

@RestController
@RequestMapping("\${tc.api-prefix}/analytics")
@Validated
class AnalyticsController(private val analytics: AnalyticsService) {
    @GetMapping("/sales")
    fun sales(@RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) from: LocalDate?,
              @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) to: LocalDate?,
              @RequestParam(defaultValue = "day") @Pattern(regexp = "^(day|week|month)$") interval: String) =
        analytics.sales(Caller.user(), from, to, interval)
}
