package com.rcs.healthcheck

import zio._
import com.typesafe.config.{ConfigFactory, Config => TSConfig}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

case class AppConfig(
  name: String,
  version: String,
  healthCheck: HealthCheckConfig,
  logAggregation: LogAggregationConfig,
  server: ServerConfig,
  logging: LoggingConfig
)

case class HealthCheckConfig(
  endpoints: List[String],
  interval: scala.concurrent.duration.FiniteDuration,
  timeout: scala.concurrent.duration.FiniteDuration,
  connectivityUrl: Option[String]
)

case class LogAggregationConfig(
  sources: List[String],
  maxEntries: Int,
  maxLogFileSize: Long,
  persistDir: String
)

case class ServerConfig(
  host: String,
  port: Int
)

case class LoggingConfig(
  level: String,
  file: String
)

object Config {
  def load(): ZIO[Any, Throwable, AppConfig] = ZIO.attempt {
    val config = ConfigFactory.load()

    val healthCheckConfig = config.getConfig("app.health-check")
    val logAggregationConfig = config.getConfig("app.log-aggregation")
    val serverConfig = config.getConfig("app.server")
    val loggingConfig = config.getConfig("app.logging")

    val appConfig = AppConfig(
      name = config.getString("app.name"),
      version = config.getString("app.version"),
      healthCheck = HealthCheckConfig(
        endpoints = healthCheckConfig.getStringList("endpoints").asScala.toList,
        interval = {
          val duration = healthCheckConfig.getDuration("interval")
          require(duration.toNanos > 0, "Health check interval must be positive")
          scala.concurrent.duration.FiniteDuration(duration.toNanos, scala.concurrent.duration.NANOSECONDS)
        },
        timeout = {
          val duration = healthCheckConfig.getDuration("timeout")
          require(duration.toNanos > 0, "Health check timeout must be positive")
          scala.concurrent.duration.FiniteDuration(duration.toNanos, scala.concurrent.duration.NANOSECONDS)
        },
        connectivityUrl =
          Option.when(healthCheckConfig.hasPath("connectivity-url"))(
            healthCheckConfig.getString("connectivity-url").trim
          ).filter(_.nonEmpty)
      ),
      logAggregation = LogAggregationConfig(
        sources = logAggregationConfig.getStringList("sources").asScala.toList,
        maxEntries = logAggregationConfig.getInt("max-entries"),
        maxLogFileSize = logAggregationConfig.getBytes("max-log-file-size"),
        persistDir = if (logAggregationConfig.hasPath("persist-dir"))
          logAggregationConfig.getString("persist-dir")
        else
          "persisted-logs"
      ),
      server = ServerConfig(
        host = serverConfig.getString("host"),
        port = serverConfig.getInt("port")
      ),
      logging = LoggingConfig(
        level = loggingConfig.getString("level"),
        file = loggingConfig.getString("file")
      )
    )

    // Post-load validation
    require(appConfig.healthCheck.endpoints.nonEmpty, "Health check endpoints list cannot be empty")
    require(appConfig.logAggregation.sources.nonEmpty, "Log aggregation sources list cannot be empty")
    require(appConfig.logAggregation.maxEntries >= 0, s"Log aggregation maxEntries must be >= 0, got ${appConfig.logAggregation.maxEntries}")
    require(appConfig.logAggregation.maxLogFileSize > 0, s"Log aggregation maxLogFileSize must be > 0, got ${appConfig.logAggregation.maxLogFileSize}")
    require(appConfig.logAggregation.maxLogFileSize >= 4096, s"Log aggregation maxLogFileSize must be at least 4 KiB, got ${appConfig.logAggregation.maxLogFileSize}")
    require(appConfig.server.port >= 0 && appConfig.server.port <= 65535, s"Server port must be between 0 and 65535, got ${appConfig.server.port}")

    appConfig
  }

  val live: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(load())
}