package com.example.healthcheck

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
  interval: scala.concurrent.duration.Duration,
  timeout: scala.concurrent.duration.Duration
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
        interval = scala.concurrent.duration.Duration(healthCheckConfig.getString("interval")),
        timeout = scala.concurrent.duration.Duration(healthCheckConfig.getString("timeout"))
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