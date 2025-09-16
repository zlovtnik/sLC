package com.rcs

package object healthcheck {
  import cats.data.ValidatedNel

  type CheckResult = ValidatedNel[String, Unit]
}