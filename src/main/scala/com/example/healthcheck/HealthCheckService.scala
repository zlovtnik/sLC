package com.example.healthcheck

import zio._
import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import scala.concurrent.duration._

case class HealthStatus(service: String, status: String, timestamp: Long, details: Option[String])

trait HealthCheckService {
  def checkHealth(endpoint: String): ZIO[Any, Throwable, HealthStatus]
  def checkAllHealths(endpoints: List[String]): ZIO[Any, Throwable, List[HealthStatus]]
}

object HealthCheckService {
  def live: ZLayer[SttpBackend[Task, Any], Nothing, HealthCheckService] =
    ZLayer.fromFunction { backend: SttpBackend[Task, Any] =>
      new HealthCheckService {
        override def checkHealth(endpoint: String): ZIO[Any, Throwable, HealthStatus] = {
          val request = basicRequest
            .get(uri"$endpoint/health")
            .response(asJson[Map[String, String]])

          backend.send(request).flatMap { response =>
            val body = response.body
            val status = body match {
              case Right(_) => "healthy"
              case Left(_) => "unhealthy"
            }
            val details = body match {
              case Right(data: Map[String, String]) => data.get("status").orElse(Some("ok"))
              case Left(error) => Some(error.toString)
            }
            val timestamp = java.lang.System.currentTimeMillis()
            ZIO.succeed(HealthStatus(endpoint, status, timestamp, details))
          }
        }

        override def checkAllHealths(endpoints: List[String]): ZIO[Any, Throwable, List[HealthStatus]] = {
          ZIO.foreachPar(endpoints)(checkHealth)
        }
      }
    }

  def checkHealth(endpoint: String): ZIO[HealthCheckService, Throwable, HealthStatus] =
    ZIO.serviceWithZIO(_.checkHealth(endpoint))

  def checkAllHealths(endpoints: List[String]): ZIO[HealthCheckService, Throwable, List[HealthStatus]] =
    ZIO.serviceWithZIO(_.checkAllHealths(endpoints))
}