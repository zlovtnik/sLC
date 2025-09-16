package com.rcs.healthcheck

import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import java.util.concurrent.TimeUnit
import cats.data.Validated

object Main extends ZIOAppDefault {

  private def logHealthCheckResult(result: CheckResult, prefix: String = ""): ZIO[Any, Nothing, Unit] = result match {
    case Validated.Valid(()) => ZIO.logInfo(s"All ${prefix}health checks passed!")
    case Validated.Invalid(errors) => ZIO.logWarning(s"${prefix.capitalize}health check failures: ${errors.toList.map(_.message).mkString(", ")}")
  }

  val configLayer: ZLayer[Any, Throwable, AppConfig] = com.rcs.healthcheck.Config.live
  val appLayer: ZLayer[Any, Throwable, HealthCheckService with LogAggregator with LogPersistence with AppConfig] =
    HttpClientZioBackend.layer() ++ configLayer >>> HealthCheckService.live ++ LogAggregator.live ++ LogAggregator.logPersistenceLive ++ configLayer

  val program: ZIO[HealthCheckService with LogAggregator with LogPersistence with AppConfig, Throwable, Unit] = for {
    config <- ZIO.service[AppConfig]
    _ <- ZIO.logInfo(s"Starting ${config.name} v${config.version}")

    // Health checks
    _ <- ZIO.logInfo("Performing initial health checks...")
    checks = List(DatabaseCheck, InternetCheck) ++ config.healthCheck.endpoints.map(EndpointCheck)
    result <- HealthCheckService.checkAllHealths(checks)
    _ <- logHealthCheckResult(result)

    // Log aggregation
    _ <- ZIO.logInfo("Aggregating logs...")
    stats <- LogAggregator.aggregateStats(config.logAggregation.sources)
    _ <- ZIO.logInfo(s"Log stats: ${stats.errorCount} errors, ${stats.warningCount} warnings")
    processingPipeline = LogAggregator.streamLogs(config.logAggregation.sources)
      .groupedWithin(1000, zio.Duration.fromSeconds(1))
      .mapZIO(chunk => ZIO.serviceWithZIO[LogPersistence](_.persist(chunk)))
    _ <- processingPipeline.runDrain

    // Schedule periodic checks
    _ <- ZIO.logInfo(s"Setting up periodic health checks every ${config.healthCheck.interval}...")
    fiber <- ZIO.scoped {
      (for {
        _ <- ZIO.logInfo("Running periodic health check...")
        periodicResult <- HealthCheckService.checkAllHealths(checks)
        _ <- logHealthCheckResult(periodicResult, "periodic ")
      } yield ()).repeat(Schedule.spaced(java.time.Duration.ofMillis(config.healthCheck.interval.toMillis))).forkScoped
    }

    _ <- ZIO.logInfo("Application started successfully. Press Ctrl+C to stop.")
    _ <- ZIO.never
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] = {
    program.provideLayer(appLayer)
  }
}