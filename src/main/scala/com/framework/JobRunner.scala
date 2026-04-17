package com.framework

import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import com.framework.RunnerProtocol._
import com.framework.JobProtocol.{JobRunnerResponse, JobSucceeded, JobFailed}

import scala.concurrent.Future
import scala.util.{Failure, Success}

abstract class JobRunner(name: String) {

  // User-defined hooks
  protected def validate(ctx: JobContext): Future[Boolean]
  protected def initialize(ctx: JobContext): Future[JobContext]
  protected def run(ctx: JobContext): Future[JobContext]
  protected def finalize(ctx: JobContext, success: Boolean): Future[Unit]

  def apply(): Behavior[RunnerCommand] = Behaviors.setup { context =>
    Behaviors.receiveMessage {
      case StartRunner(jobCtx, replyTo) =>
        context.log.info(s"Runner $name: Starting lifecycle")

        // Start the sequence: Validate -> Initialize -> Run -> Finalize
        context.pipeToSelf(validate(jobCtx)) {
          case Success(true)  => ValidationSuccess
          case Success(false) =>
            context.log.warn(s"Runner $name: Validation returned false")
            ValidationFailed("Validation check failed")
          case Failure(ex) =>
            context.log.error(s"Runner $name: Validation failed with exception", ex)
            ValidationFailed(ex.getMessage)
        }
        running(jobCtx, replyTo, hasError = false, errorReason = None)

      case _ => Behaviors.unhandled
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
        context.log.warn(s"Runner $name: Received cancellation signal")
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some("Cancelled by job manager"))

      case ValidationSuccess =>
        context.self ! ExecuteInitialize
        Behaviors.same

      case ValidationFailed(error) =>
        context.self ! ExecuteFinalize
        running(currentCtx, replyTo, hasError = true, Some(s"Validation failed: $error"))

      case ExecuteInitialize =>
        if (hasError) {
          // Skip initialize if validation failed
          context.self ! ExecuteFinalize
          Behaviors.same
        } else {
          context.log.info(s"Runner $name: Initializing")
          context.pipeToSelf(initialize(currentCtx)) {
            case Success(newCtx) => InitializeSuccess(newCtx)
            case Failure(ex)     =>
              context.log.error(s"Runner $name: Initialize failed", ex)
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
          // Skip run if previous step failed
          context.self ! ExecuteFinalize
          Behaviors.same
        } else {
          context.log.info(s"Runner $name: Running")
          context.pipeToSelf(run(currentCtx)) {
            case Success(newCtx) => RunSuccess(newCtx)
            case Failure(ex)     =>
              context.log.error(s"Runner $name: Run failed", ex)
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
        context.log.info(s"Runner $name: Finalizing (success=${!hasError})")
        context.pipeToSelf(finalize(currentCtx, !hasError)) {
          case Success(_)  => FinalizeComplete
          case Failure(ex) =>
            context.log.error(s"Runner $name: Finalize failed", ex)
            FinalizeComplete
        }
        Behaviors.same

      case FinalizeComplete =>
        context.log.info(s"Runner $name: Completed")
        if (hasError) {
          replyTo ! JobRunnerResponse(name, JobFailed(errorReason.getOrElse("Unknown error")))
        } else {
          replyTo ! JobRunnerResponse(name, JobSucceeded(currentCtx))
        }
        Behaviors.stopped

      case msg: StartRunner =>
        context.log.warn(s"Runner $name: Ignoring StartRunner while already running")
        Behaviors.unhandled

      case _ => Behaviors.unhandled
    }
  }
}
