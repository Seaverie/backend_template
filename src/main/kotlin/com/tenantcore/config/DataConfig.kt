package com.tenantcore.config

import com.tenantcore.db.Db
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Two connection pools: the runtime role (`tc_app`, no superuser, no BYPASSRLS) serves requests; the
 * schema owner (`tc_owner`) runs migrations and bootstrap tasks only, through a tiny pool.
 */
@Configuration
@EnableConfigurationProperties(TcProperties::class)
class DataConfig(private val props: TcProperties) {

    private fun pool(name: String, user: String, password: String, size: Int): HikariDataSource {
        require(props.db.url.isNotBlank()) { "TC_DB_URL is required" }
        require(password.isNotBlank() && "CHANGE_ME" !in password) { "Set a real password for database role '$user' (TC_DB_APP_PASSWORD / TC_DB_OWNER_PASSWORD)" }
        return HikariDataSource(HikariConfig().apply {
            poolName = name
            jdbcUrl = props.db.url
            username = user
            this.password = password
            maximumPoolSize = size
            minimumIdle = 1
            connectionInitSql = "SET statement_timeout = ${props.db.statementTimeoutMs}"
            addDataSourceProperty("ApplicationName", "tenantcore-$name")
        })
    }

    @Bean
    @Primary
    fun appDataSource(): DataSource = pool("app", props.db.appUser, props.db.appPassword, props.db.poolSize)

    @Bean
    fun ownerDataSource(): DataSource = pool("owner", props.db.ownerUser, props.db.ownerPassword, 2)

    /** Migrations run as the owner before anything else touches the database. */
    @Bean
    fun flyway(@Qualifier("ownerDataSource") owner: DataSource): Flyway =
        Flyway.configure()
            .dataSource(owner)
            .placeholders(mapOf("appRole" to props.db.appUser, "ownerRole" to props.db.ownerUser))
            .locations("classpath:db/migration")
            .load()
            .also { it.migrate() }

    @Bean
    fun db(
        @Qualifier("appDataSource") app: DataSource,
        @Qualifier("ownerDataSource") owner: DataSource,
        @Suppress("UNUSED_PARAMETER") flyway: Flyway,
    ): Db = Db(
        app, JdbcClient.create(app), TransactionTemplate(DataSourceTransactionManager(app)),
        owner, JdbcClient.create(owner), TransactionTemplate(DataSourceTransactionManager(owner)),
    )
}
