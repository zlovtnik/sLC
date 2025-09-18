package com.rcs.healthcheck

import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import cats.data.Validated
import com.typesafe.scalalogging.LazyLogging
import org.slf4j.MDC
import java.util.UUID
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import java.time.Instant
import scala.concurrent.Future

object Main extends ZIOAppDefault with LazyLogging {

  private def withCorrelationId[R, E, A](operation: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] = {
    val correlationId = UUID.randomUUID().toString
    ZIO.acquireReleaseWith {
      ZIO.succeed {
        MDC.put("correlationId", correlationId)
        MDC.put("operation", operation)
        MDC.put("timestamp", java.time.Instant.now().toString)
      }
    } { _ =>
      ZIO.succeed(clearMdc("correlationId", "operation", "timestamp"))
    } { _ => zio }
  }

  private def clearMdc(keys: String*): Unit = {
    keys.foreach(MDC.remove)
  }

  private def logHealthCheckResult(result: CheckResult, prefix: String = ""): ZIO[Any, Nothing, Unit] = result match {
    case Validated.Valid(()) =>
      ZIO.succeed {
        MDC.put("healthCheckStatus", "success")
        MDC.put("checkType", if (prefix.isEmpty) "initial" else "periodic")
        val prefixFragment = if (prefix.nonEmpty) s"${prefix.capitalize} " else ""
        logger.info(s"All ${prefixFragment}health checks passed!")
        clearMdc("healthCheckStatus", "checkType")
      }
    case Validated.Invalid(errors) =>
      val prefixFragment = if (prefix.nonEmpty) s"${prefix.capitalize} " else ""
      val logMessage = s"${prefixFragment}health check failures: ${errors.toList.map(_.message).mkString(", ")}"
      ZIO.succeed {
        MDC.put("healthCheckStatus", "failure")
        MDC.put("checkType", if (prefix.isEmpty) "initial" else "periodic")
        MDC.put("errorCount", errors.size.toString)
        if (prefix.isEmpty) logger.error(logMessage) else logger.warn(logMessage)
        clearMdc("healthCheckStatus", "checkType", "errorCount")
      }
  }

  private def logIndividualHealthChecks(checks: List[HealthCheck], prefix: String = ""): ZIO[HealthCheckService, Nothing, Unit] = {
    ZIO.foreach(checks) { check =>
      HealthCheckService.runHealthCheck(check).map { result =>
        val checkName = check match {
          case DatabaseCheck => "Redis Database"
          case InternetCheck => "Internet Connectivity"
          case EndpointCheck(endpoint) => s"Endpoint $endpoint"
        }
        val status = result match {
          case Validated.Valid(_) => "PASSED"
          case Validated.Invalid(_) => "FAILED"
        }
        
        // Use structured logging with MDC
        MDC.put("checkName", checkName)
        MDC.put("checkStatus", status)
        if (prefix.nonEmpty) MDC.put("checkType", "periodic")
        logger.info(s"Health check result: $checkName $status")
        clearMdc("checkName", "checkStatus", "checkType")
      }
    }.unit
  }

  val configLayer: ZLayer[Any, Throwable, AppConfig] = com.rcs.healthcheck.Config.live
  val appLayer: ZLayer[Any, Throwable, HealthCheckService with LogAggregator with LogPersistence with AppConfig] =
    HttpClientZioBackend.layer() ++ configLayer >>> HealthCheckService.live ++ LogAggregator.live ++ LogAggregator.logPersistenceLive ++ configLayer

  val program: ZIO[HealthCheckService with LogAggregator with LogPersistence with AppConfig, Throwable, Unit] = for {
    startTime <- ZIO.succeed(java.lang.System.currentTimeMillis())
    config <- ZIO.service[AppConfig]
    _ <- ZIO.succeed(logger.info(s"Starting ${config.name} v${config.version}"))

    // Quick metrics logging
    _ <- ZIO.succeed(logger.info(s"✅ Application started in ${java.lang.System.currentTimeMillis() - startTime}ms"))
    checks = List(DatabaseCheck, InternetCheck) ++ config.healthCheck.endpoints.map(EndpointCheck)
    _ <- ZIO.succeed(logger.info(s"🔍 Monitoring ${checks.size} health check targets"))
    _ <- ZIO.succeed(logger.info(s"📊 Processing logs from ${config.logAggregation.sources.size} sources"))

    // Initialize health status tracking
    healthStatusRef <- Ref.make[CheckResult](Validated.valid(().asInstanceOf[Unit]))

    // Health checks with correlation
    _ <- withCorrelationId("initial-health-check") {
      for {
        _ <- ZIO.succeed(logger.info("Performing initial health checks..."))
        result <- HealthCheckService.checkAllHealthChecks(checks)
        _ <- logIndividualHealthChecks(checks)
        _ <- logHealthCheckResult(result)
      } yield ()
    }

    // Log aggregation with correlation
    _ <- withCorrelationId("log-aggregation") {
      for {
        _ <- ZIO.succeed(logger.info("Aggregating logs..."))
        stats <- LogAggregator.aggregateStats(config.logAggregation.sources)
        _ <- ZIO.succeed {
          MDC.put("errorCount", stats.errorCount.toString)
          MDC.put("warningCount", stats.warningCount.toString)
          logger.info(s"Log stats: ${stats.errorCount} errors, ${stats.warningCount} warnings")
          clearMdc("errorCount", "warningCount")
        }
        processingPipeline = LogAggregator.streamLogs(config.logAggregation.sources)
          .groupedWithin(config.logAggregation.maxEntries, zio.Duration.fromSeconds(1))
          .mapZIO(chunk => ZIO.serviceWithZIO[LogPersistence](_.persist(chunk)))
        _ <- processingPipeline.runDrain.catchAll(e => ZIO.succeed {
          MDC.put("error", e.getMessage)
          logger.error("Log processing failed", e)
          clearMdc("error")
        }).forkDaemon
      } yield ()
    }

    // Schedule periodic checks with correlation
    _ <- ZIO.succeed(logger.info(s"Setting up periodic health checks every ${config.healthCheck.interval}..."))
    fiber <- withCorrelationId("periodic-health-check") {
      for {
        _ <- ZIO.succeed(logger.info("Running periodic health check..."))
        periodicResult <- HealthCheckService.checkAllHealthChecks(checks)
        _ <- healthStatusRef.set(periodicResult)
        _ <- logIndividualHealthChecks(checks, "[PERIODIC] ")
        _ <- logHealthCheckResult(periodicResult, "periodic ")
      } yield ()
    }
      .repeat(Schedule.spaced(java.time.Duration.ofMillis(config.healthCheck.interval.toMillis)))
      .fork

    // Start HTTP server for health check endpoint
    _ <- ZIO.succeed(logger.info(s"Starting HTTP server on port ${config.server.port}..."))
    actorSystem <- ZIO.attempt(ActorSystem("health-check-system"))
    requestHandler = (request: HttpRequest) => {
      if (request.uri.path.toString() == "/health" && request.method == HttpMethods.GET) {
        Future.successful(HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`application/json`, s"""{"status":"UP","timestamp":"${Instant.now()}"}""")
        ))
      } else {
        Future.successful(HttpResponse(status = StatusCodes.NotFound))
      }
    }
    bindingFuture <- ZIO.attempt(Http(actorSystem).newServerAt("0.0.0.0", config.server.port).bind(requestHandler))
    binding <- ZIO.fromFuture(_ => bindingFuture).catchAll { error =>
      ZIO.fail(new RuntimeException(s"Failed to bind HTTP server on port ${config.server.port}: ${error.getMessage}", error))
    }
    _ <- ZIO.succeed(logger.info(s"HTTP server started at http://localhost:${config.server.port}"))

    _ <- ZIO.succeed(logger.info("Application started successfully. Press Ctrl+C to stop."))
    _ <- ZIO.never
  } yield ()

  override def run: ZIO[Any, Throwable, Unit] = {
    program.provideLayer(appLayer)
  }
}