package com.rcs.healthcheck

import zio._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import cats.data.ValidatedNel
import cats.implicits._
import redis.clients.jedis.{Jedis}
import scala.concurrent.duration._

// Define a sealed trait for our health checks
sealed trait HealthCheck
case object DatabaseCheck extends HealthCheck
case object InternetCheck extends HealthCheck
case class EndpointCheck(endpoint: String) extends HealthCheck

trait HealthCheckService {
  def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult]
  def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult]
}

object HealthCheckService {
  def live: ZLayer[SttpBackend[Task, Any] with AppConfig, Throwable, HealthCheckService] =
    ZLayer.fromZIO {
      (for {
        backend <- ZIO.service[SttpBackend[Task, Any]]
        config <- ZIO.service[AppConfig]
        connectivityUrl <- ZIO.fromOption(config.healthCheck.connectivityUrl)
          .orElseFail(new RuntimeException("Missing healthCheck.connectivityUrl in application configuration. Please set this in your application.conf or environment variables."))
        redisClient <- ZIO.attempt {
          // Store Redis configuration for creating connections per health check
          config.redis
        }
      } yield new HealthCheckService {
        private val timeoutDuration = zio.Duration.fromNanos(config.healthCheck.timeout.toNanos)

        override def runCheck(check: HealthCheck): ZIO[Any, Nothing, CheckResult] = check match {
          case DatabaseCheck =>
            // Redis connectivity health check using Jedis
            ZIO.attemptBlocking {
              // Create a fresh Jedis connection for each health check
              val jedis = redisClient.password match {
                case Some(pwd) =>
                  val client = new Jedis(redisClient.host, redisClient.port)
                  client.auth("default", pwd)
                  client
                case None =>
                  new Jedis(redisClient.host, redisClient.port)
              }

              try {
                val pong = jedis.ping()
                pong == "PONG"
              } finally {
                jedis.close()
              }
            }.foldZIO(
              error => {
                val errorMsg = error match {
                  case _: java.net.UnknownHostException =>
                    s"Redis host '${redisClient.host}' not found. Please check your Redis configuration."
                  case _: java.net.ConnectException =>
                    s"Cannot connect to Redis at ${redisClient.host}:${redisClient.port}. Is Redis server running?"
                  case _: redis.clients.jedis.exceptions.JedisConnectionException =>
                    s"Redis connection failed: ${error.getMessage}"
                  case _: redis.clients.jedis.exceptions.JedisDataException if error.getMessage.contains("NOAUTH") =>
                    "Redis authentication failed. Please check your Redis password."
                  case _ =>
                    s"Redis health check failed: ${error.getMessage}"
                }
                ZIO.succeed(DatabaseError(errorMsg).invalidNel[Unit])
              },
              success => if (success) ZIO.succeed(().validNel[HealthError])
                         else ZIO.succeed(DatabaseError("Redis ping failed - no PONG response").invalidNel[Unit])
            ).timeout(timeoutDuration)
              .map(_.getOrElse(DatabaseError(s"Redis health check timed out after ${timeoutDuration.toMillis}ms").invalidNel[Unit]))
          case InternetCheck =>
            // Check internet connectivity by pinging configured URL
            val request = basicRequest.get(uri"$connectivityUrl").response(asString)
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(ConnectivityError("Internet is down").invalidNel[Unit]),
              resp =>
                if (resp.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(ConnectivityError(s"Connectivity check returned ${resp.code}").invalidNel[Unit])
            )
            check.race(ZIO.succeed(ConnectivityError("Internet connectivity timed out").invalidNel[Unit]).delay(timeoutDuration))
          case EndpointCheck(endpoint) =>
            val base    = uri"$endpoint"
            val request = basicRequest
              .get(base.addPath("health"))  // safe join, no double slashes
              .response(asString)           // rely on status code
            val check = backend.send(request).foldZIO(
              _ => ZIO.succeed(EndpointError(s"Endpoint $endpoint is unhealthy").invalidNel[Unit]),
              response =>
                if (response.code.isSuccess) ZIO.succeed(().validNel[HealthError])
                else ZIO.succeed(EndpointError(s"Endpoint $endpoint returned error: ${response.code}").invalidNel[Unit])
            )
            // Race with timeout
            check.timeout(timeoutDuration).map(_.getOrElse(EndpointError(s"Endpoint $endpoint timed out").invalidNel[Unit]))
        }

        override def checkAllHealths(checks: List[HealthCheck]): ZIO[Any, Nothing, CheckResult] = {
          ZIO.foreachPar(checks)(runCheck)
            .map(_.combineAll)
        }
      }).tapError { error =>
        ZIO.succeed(
          println(s"Failed to provision HealthCheckService: ${error.getMessage}")
        )
      }
    }

  def runCheck(check: HealthCheck): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.runCheck(check))

  def checkAllHealths(checks: List[HealthCheck]): ZIO[HealthCheckService, Nothing, CheckResult] =
    ZIO.serviceWithZIO(_.checkAllHealths(checks))
}