package com.framework

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.framework.JobProtocol._
import com.framework.RunnerProtocol.{RunnerCommand, StartRunner, CancelRunner}
import scala.concurrent.duration._

/**
 * LazyJobManager - Creates runners on-demand as each stage is executed.
 * 
 * Characteristics:
 * - Runners are created only when their stage starts
 * - Runners are destroyed after stage completion
 * - Memory efficient for large jobs
 * - Dynamic context passing between stages
 */
object LazyJobManager {

  sealed trait State
  case object Idle extends State
  case class RunningStage(
      index: Int,
      pendingRunners: Map[String, ActorRef[RunnerCommand]],
      context: JobContext
  ) extends State

  def apply(factory: RunnerFactory): Behavior[JobCommand] = idle(factory)

  private def idle(factory: RunnerFactory): Behavior[JobCommand] = Behaviors.receive { (context, message) =>
    message match {
      case StartJob(definition) =>
        context.log.info("[LazyJobManager] Starting Job Execution")
        executeStage(0, definition, JobContext(), context, factory)
      case _ =>
        Behaviors.unhandled
    }
  }

  private def executeStage(
      index: Int,
      definition: JobDefinition,
      jobCtx: JobContext,
      actorCtx: akka.actor.typed.scaladsl.ActorContext[JobCommand],
      factory: RunnerFactory
  ): Behavior[JobCommand] = {
    if (index >= definition.stages.size) {
      actorCtx.log.info("[LazyJobManager] All stages completed successfully")
      return idle(factory)
    }

    val stage = definition.stages(index)

    // Schedule timeout if configured
    stage.timeout.foreach { duration =>
      actorCtx.scheduleOnce(duration, actorCtx.self, StageTimeout(index))
      actorCtx.log.info(s"[LazyJobManager] Stage $index timeout scheduled for $duration")
    }

    stage match {
      case SequentialStage(runnerConfig, _) =>
        actorCtx.log.info(s"[LazyJobManager] Executing Sequential Stage: ${runnerConfig.name}")
        val runner = factory.create(runnerConfig.name, runnerConfig.props)
        val runnerRef = actorCtx.spawn(runner(), runnerConfig.name)
        runnerRef ! StartRunner(jobCtx, actorCtx.self)
        running(index, Map(runnerConfig.name -> runnerRef), jobCtx, definition, factory)

      case ParallelStage(runners, _) =>
        actorCtx.log.info(s"[LazyJobManager] Executing Parallel Stage with ${runners.size} runners")
        val runnerRefs = runners.map { runnerConfig =>
          val runner = factory.create(runnerConfig.name, runnerConfig.props)
          val runnerRef = actorCtx.spawn(runner(), runnerConfig.name)
          runnerRef ! StartRunner(jobCtx, actorCtx.self)
          runnerConfig.name -> runnerRef
        }.toMap
        running(index, runnerRefs, jobCtx, definition, factory)
    }
  }

  private def running(
      index: Int,
      pending: Map[String, ActorRef[RunnerCommand]],
      jobCtx: JobContext,
      definition: JobDefinition,
      factory: RunnerFactory
  ): Behavior[JobCommand] = Behaviors.receive { (context, message) =>
    message match {
      case StageTimeout(stageIndex) if stageIndex == index =>
        context.log.error(s"[LazyJobManager] Stage $index timed out! Cancelling ${pending.size} runners")
        pending.values.foreach(_ ! CancelRunner)
        idle(factory)

      case StageTimeout(stageIndex) =>
        context.log.debug(s"[LazyJobManager] Ignoring timeout for stage $stageIndex (current: $index)")
        Behaviors.same

      case JobRunnerResponse(runnerName, result) =>
        val updatedPending = pending - runnerName
        context.log.info(s"[LazyJobManager] Runner $runnerName finished. Pending: ${updatedPending.size}")

        result match {
          case JobSucceeded(newCtx) =>
            val conflicts = jobCtx.data.keySet.intersect(newCtx.data.keySet)
            if (conflicts.nonEmpty) {
              context.log.warn(s"[LazyJobManager] Context merge conflicts detected for keys: ${conflicts.mkString(", ")}")
            }
            val mergedCtx = JobContext(jobCtx.data ++ newCtx.data)

            if (updatedPending.isEmpty) {
              executeStage(index + 1, definition, mergedCtx, context, factory)
            } else {
              running(index, updatedPending, mergedCtx, definition, factory)
            }

          case JobFailed(reason) =>
            context.log.error(s"[LazyJobManager] Job failed at stage $index due to $runnerName: $reason")
            updatedPending.values.foreach { runnerRef =>
              context.log.info(s"[LazyJobManager] Cancelling runner due to failure")
              runnerRef ! CancelRunner
            }
            idle(factory)
        }
      case _ => Behaviors.unhandled
    }
  }
}
