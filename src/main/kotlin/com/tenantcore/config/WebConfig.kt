package com.tenantcore.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

/**
 * Serves the dashboard (built into classpath:/static) from the same address as the API. Hashed asset
 * files are cached for a year; any other path that is not a file returns index.html, so the dashboard's
 * own routes (/orders, /settings...) work on reload. API paths never fall back to the dashboard.
 */
@Configuration
class WebConfig(private val props: TcProperties) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(path: String, location: Resource): Resource? {
                    val api = props.apiPrefix.trimStart('/')
                    if (path.startsWith(api) || path.startsWith("actuator") || path == "health" || path == "ready") return null
                    val file = location.createRelative(path)
                    if (path.isNotEmpty() && file.exists() && file.isReadable) return file
                    if (path.substringAfterLast('/').contains('.')) return null   // a missing file is a 404, not the app
                    return ClassPathResource("static/index.html").takeIf { it.exists() }
                }
            })
    }
}
