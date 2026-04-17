package com.framework

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.framework.JobProtocol._
import com.framework.RunnerProtocol.{RunnerCommand, StartRunner, CancelRunner}
import scala.concurrent.duration._

/**
 * EagerJobManager - Pre-allocates all runners at startup.
 * 
 * Characteristics:
 * - All runners are created upfront when job starts
 * - Runners stay alive and are triggered by messages
 * - No creation/destruction overhead during execution
 * - Better for jobs with predictable runner sets
 * - Higher initial memory usage
 * - Cleaner supervisor hierarchy
 */
object EagerJobManager {

  sealed trait State
  case object Idle extends State
  case class Initialized(
      definition: JobDefinition,
      allRunners: Map[String, ActorRef[RunnerCommand]],
      currentStage: Int,
      pendingRunners: Set[String],
      context: JobContext
  ) extends State

  def apply(factory: StatefulRunnerFactory): Behavior[JobCommand] = idle(factory)

  private def idle(factory: StatefulRunnerFactory): Behavior[JobCommand] = 
    Behaviors.receive { (context, message) =>
      message match {
        case StartJob(definition) =>
          context.log.info("[EagerJobManager] Starting Job Execution - Pre-allocating runners")
          
          // Extract all unique runner names from all stages
          val allRunnerConfigs = definition.stages.flatMap {
            case SequentialStage(runnerConfig, _) => List(runnerConfig)
            case ParallelStage(runnerConfigs, _) => runnerConfigs
          }.distinctBy(_.name)
          
          // Pre-allocate ALL runners upfront
          val runnerRefs = allRunnerConfigs.map { runnerConfig =>
            context.log.info(s"[EagerJobManager] Pre-allocating runner: ${runnerConfig.name}")
            val runner = factory.createStateful(runnerConfig.name, runnerConfig.props)
            val ref = context.spawn(runner(), runnerConfig.name)
            runnerConfig.name -> ref
          }.toMap
          
          context.log.info(s"[EagerJobManager] Pre-allocated ${runnerRefs.size} runners")
          
          // Start stage 0
          executeStage(0, definition, runnerRefs, JobContext(), context)
          
        case _ =>
          Behaviors.unhandled
      }
    }

  private def executeStage(
      index: Int,
      definition: JobDefinition,
      allRunners: Map[String, ActorRef[RunnerCommand]],
      jobCtx: JobContext,
      actorCtx: akka.actor.typed.scaladsl.ActorContext[JobCommand]
  ): Behavior[JobCommand] = {
    if (index >= definition.stages.size) {
      actorCtx.log.info("[EagerJobManager] All stages completed successfully")
      // Cleanup: stop all runners
      actorCtx.log.info(s"[EagerJobManager] Stopping ${allRunners.size} pre-allocated runners")
      allRunners.values.foreach(actorCtx.stop)
      return Behaviors.stopped
    }

    val stage = definition.stages(index)
    
    // Schedule timeout if configured
    stage.timeout.foreach { duration =>
      actorCtx.scheduleOnce(duration, actorCtx.self, StageTimeout(index))
      actorCtx.log.info(s"[EagerJobManager] Stage $index timeout scheduled for $duration")
    }
    
    stage match {
      case SequentialStage(runnerConfig, _) =>
        actorCtx.log.info(s"[EagerJobManager] Triggering Sequential Stage: ${runnerConfig.name}")
        val runnerRef = allRunners(runnerConfig.name)
        runnerRef ! StartRunner(jobCtx, actorCtx.self)
        running(index, Set(runnerConfig.name), allRunners, jobCtx, definition)

      case ParallelStage(runners, _) =>
        actorCtx.log.info(s"[EagerJobManager] Triggering Parallel Stage with ${runners.size} runners")
        runners.foreach { runnerConfig =>
          val runnerRef = allRunners(runnerConfig.name)
          runnerRef ! StartRunner(jobCtx, actorCtx.self)
        }
        running(index, runners.map(_.name).toSet, allRunners, jobCtx, definition)
    }
  }

  private def running(
      index: Int,
      pending: Set[String],
      allRunners: Map[String, ActorRef[RunnerCommand]],
      jobCtx: JobContext,
      definition: JobDefinition
  ): Behavior[JobCommand] = Behaviors.receive { (context, message) =>
    message match {
      case StageTimeout(stageIndex) if stageIndex == index =>
        context.log.error(s"[EagerJobManager] Stage $index timed out! Cancelling ${pending.size} runners")
        pending.foreach { runnerName =>
          allRunners.get(runnerName).foreach(_ ! CancelRunner)
        }
        // Stop all runners and terminate
        allRunners.values.foreach(context.stop)
        Behaviors.stopped

      case StageTimeout(stageIndex) =>
        context.log.debug(s"[EagerJobManager] Ignoring timeout for stage $stageIndex (current: $index)")
        Behaviors.same

      case JobRunnerResponse(runnerName, result) =>
        val updatedPending = pending - runnerName
        context.log.info(s"[EagerJobManager] Runner $runnerName finished. Pending: ${updatedPending.size}")

        result match {
          case JobSucceeded(newCtx) =>
            val conflicts = jobCtx.data.keySet.intersect(newCtx.data.keySet)
            if (conflicts.nonEmpty) {
              context.log.warn(s"[EagerJobManager] Context merge conflicts: ${conflicts.mkString(", ")}")
            }
            val mergedCtx = JobContext(jobCtx.data ++ newCtx.data)

            if (updatedPending.isEmpty) {
              // All runners for this stage completed - move to next stage
              executeStage(index + 1, definition, allRunners, mergedCtx, context)
            } else {
              // Still waiting for other runners in parallel stage
              running(index, updatedPending, allRunners, mergedCtx, definition)
            }

          case JobFailed(reason) =>
            context.log.error(s"[EagerJobManager] Job failed at stage $index due to $runnerName: $reason")
            // Cancel remaining runners in current stage
            updatedPending.foreach { name =>
              allRunners.get(name).foreach { ref =>
                context.log.info(s"[EagerJobManager] Cancelling runner $name")
                ref ! CancelRunner
              }
            }
            // Stop all runners and terminate
            allRunners.values.foreach(context.stop)
            Behaviors.stopped
        }

      case _ => Behaviors.unhandled
    }
  }
}
