package com.framework

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import com.framework.RunnerProtocol._
import com.framework.JobProtocol.{JobRunnerResponse, JobSucceeded, JobFailed}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * StatefulJobRunner - A runner that can be pre-allocated and triggered by messages.
 * 
 * Unlike JobRunner (which runs once and stops), StatefulJobRunner:
 * - Can be triggered multiple times with ExecuteStage messages
 * - Maintains idle/running state
 * - Stays alive across multiple executions
 * - Suitable for EagerJobManager's pre-allocation pattern
 */
abstract class StatefulJobRunner(name: String) {

  // User-defined hooks (same as JobRunner)
  protected def validate(ctx: JobContext): Future[Boolean]
  protected def initialize(ctx: JobContext): Future[JobContext]
  protected def run(ctx: JobContext): Future[JobContext]
  protected def finalize(ctx: JobContext, success: Boolean): Future[Unit]

  def apply(): Behavior[RunnerCommand] = idle()

  private def idle(): Behavior[RunnerCommand] = Behaviors.receive { (context, message) =>
    message match {
      case StartRunner(jobCtx, replyTo) =>
        context.log.info(s"[StatefulRunner:$name] Received execution trigger")
        
        // Start lifecycle
        context.pipeToSelf(validate(jobCtx)) {
          case Success(true) => ValidationSuccess
          case Success(false) =>
            context.log.warn(s"[StatefulRunner:$name] Validation returned false")
            ValidationFailed("Validation check failed")
          case Failure(ex) =>
            context.log.error(s"[StatefulRunner:$name] Validation failed", ex)
            ValidationFailed(ex.getMessage)
        }
        running(jobCtx, replyTo, hasError = false, errorReason = None)

      case CancelRunner =>
        context.log.warn(s"[StatefulRunner:$name] Received cancel while idle, ignoring")
        Behaviors.same

      case _ => 
        Behaviors.unhandled
    }
  }

  private def running(
      currentCtx: JobContext,
      replyTo: ActorRef[JobRunnerResponse],
      hasError: Boolean,
      errorReason: Option[String]
  ): Behavior[RunnerCommand] = Behaviors.receive { (context, message) =>
    message match {
      case CancelRunner =>
        context.log.warn(s"[StatefulRunner:$name] Received cancellation during execution")
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some("Cancelled by manager"))

      case ValidationSuccess =>
        context.self ! ExecuteInitialize
        Behaviors.same

      case ValidationFailed(error) =>
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some(s"Validation failed: $error"))

      case ExecuteInitialize =>
        if (hasError) {
          context.self ! ExecuteFinalize
          Behaviors.same
        } else {
          context.log.info(s"[StatefulRunner:$name] Initializing")
          context.pipeToSelf(initialize(currentCtx)) {
            case Success(newCtx) => InitializeSuccess(newCtx)
            case Failure(ex) =>
              context.log.error(s"[StatefulRunner:$name] Initialize failed", ex)
              InitializeFailed(ex.getMessage)
          }
          Behaviors.same
        }

      case InitializeSuccess(newCtx) =>
        context.self ! ExecuteRun
        running(newCtx, replyTo, hasError = false, None)

      case InitializeFailed(error) =>
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some(s"Initialize failed: $error"))

      case ExecuteRun =>
        if (hasError) {
          context.self ! ExecuteFinalize
          Behaviors.same
        } else {
          context.log.info(s"[StatefulRunner:$name] Running")
          context.pipeToSelf(run(currentCtx)) {
            case Success(newCtx) => RunSuccess(newCtx)
            case Failure(ex) =>
              context.log.error(s"[StatefulRunner:$name] Run failed", ex)
              RunFailed(ex.getMessage)
          }
          Behaviors.same
        }

      case RunSuccess(newCtx) =>
        context.self ! ExecuteFinalize
        running(newCtx, replyTo, hasError = false, None)

      case RunFailed(error) =>
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some(s"Run failed: $error"))

      case ExecuteFinalize =>
        context.log.info(s"[StatefulRunner:$name] Finalizing (success=${!hasError})")
        context.pipeToSelf(finalize(currentCtx, !hasError)) {
          case Success(_) => FinalizeComplete
          case Failure(ex) =>
            context.log.error(s"[StatefulRunner:$name] Finalize failed", ex)
            FinalizeComplete
        }
        Behaviors.same

      case FinalizeComplete =>
        context.log.info(s"[StatefulRunner:$name] Execution completed, returning to idle")
        if (hasError) {
          replyTo ! JobRunnerResponse(name, JobFailed(errorReason.getOrElse("Unknown error")))
        } else {
          replyTo ! JobRunnerResponse(name, JobSucceeded(currentCtx))
        }
        idle() // Back to idle, ready for next execution

      case msg: StartRunner =>
        context.log.warn(s"[StatefulRunner:$name] Ignoring StartRunner while already running")
        Behaviors.unhandled

      case _ => Behaviors.unhandled
    }
  }
}
