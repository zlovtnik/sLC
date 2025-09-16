package com.example.healthcheck

import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import java.util.concurrent.TimeUnit

object Main extends ZIOAppDefault {

  val appLayer: ZLayer[Any, Throwable, HealthCheckService with LogAggregator with AppConfig] =
    HttpClientZioBackend.layer() >>> HealthCheckService.live ++ LogAggregator.live ++ com.example.healthcheck.Config.live

  val program: ZIO[HealthCheckService with LogAggregator with AppConfig, Throwable, Unit] = for {
    config <- ZIO.service[AppConfig]
    _ <- ZIO.logInfo(s"Starting ${config.name} v${config.version}")

    // Health checks
    _ <- ZIO.logInfo("Performing initial health checks...")
    healthStatuses <- HealthCheckService.checkAllHealths(config.healthCheck.endpoints)
    _ <- ZIO.foreach(healthStatuses)(status =>
      ZIO.logInfo(s"Health check for ${status.service}: ${status.status}")
    )

    // Log aggregation
    _ <- ZIO.logInfo("Aggregating logs...")
    logs <- LogAggregator.aggregateLogs(config.logAggregation.sources)
    _ <- ZIO.logInfo(s"Aggregated ${logs.size} log entries")
    _ <- ZIO.foreach(logs.take(10))(log => // Show first 10 logs
      ZIO.logInfo(s"Log from ${log.source}: [${log.level}] ${log.message}")
    )

    // Schedule periodic checks
    _ <- ZIO.logInfo(s"Setting up periodic health checks every ${config.healthCheck.interval}...")
    _ <- (for {
      _ <- ZIO.logInfo("Running periodic health check...")
      statuses <- HealthCheckService.checkAllHealths(config.healthCheck.endpoints)
      unhealthy = statuses.filter(_.status == "unhealthy")
      _ <- ZIO.when(unhealthy.nonEmpty)(
        ZIO.logWarning(s"Found ${unhealthy.size} unhealthy services: ${unhealthy.map(_.service).mkString(", ")}")
      )
    } yield ()).repeat(Schedule.spaced(java.time.Duration.ofMillis(config.healthCheck.interval.toMillis))).fork

    _ <- ZIO.logInfo("Application started successfully. Press Ctrl+C to stop.")
    _ <- ZIO.never
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] = {
    program.provideLayer(appLayer)
  }
}