package com.tenantcore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
class TenantCoreApplication

fun main(args: Array<String>) {
    runApplication<TenantCoreApplication>(*args)
}
