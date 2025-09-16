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
  maxEntries: Int
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

    AppConfig(
      name = config.getString("app.name"),
      version = config.getString("app.version"),
      healthCheck = HealthCheckConfig(
        endpoints = healthCheckConfig.getStringList("endpoints").asScala.toList,
        interval = {
          val duration = healthCheckConfig.getDuration("interval")
          require(duration.toMillis > 0, "Health check interval must be positive")
          scala.concurrent.duration.FiniteDuration(duration.toMillis, scala.concurrent.duration.MILLISECONDS)
        },
        timeout = {
          val duration = healthCheckConfig.getDuration("timeout")
          require(duration.toMillis > 0, "Health check timeout must be positive")
          scala.concurrent.duration.FiniteDuration(duration.toMillis, scala.concurrent.duration.MILLISECONDS)
        },
        connectivityUrl = Option.when(healthCheckConfig.hasPath("connectivity-url"))(
          healthCheckConfig.getString("connectivity-url")
        )
      ),
      logAggregation = LogAggregationConfig(
        sources = logAggregationConfig.getStringList("sources").asScala.toList,
        maxEntries = logAggregationConfig.getInt("max-entries")
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
  }

  val live: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(load())
}