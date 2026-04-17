package com.framework

import scala.concurrent.duration.FiniteDuration

/**
 * Job definition structures and configuration
 */
case class JobDefinition(stages: List[Stage])

sealed trait Stage {
  def timeout: Option[FiniteDuration]
}

case class SequentialStage(
    runner: RunnerConfig,
    timeout: Option[FiniteDuration] = None
) extends Stage

case class ParallelStage(
    runners: List[RunnerConfig],
    timeout: Option[FiniteDuration] = None
) extends Stage

case class RunnerConfig(name: String, props: Map[String, Any] = Map.empty)
