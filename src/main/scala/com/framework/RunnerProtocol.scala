package com.framework

import akka.actor.typed.ActorRef
import JobProtocol.JobRunnerResponse

/**
 * Protocol messages for JobRunner-level communication
 */
object RunnerProtocol {
  
  sealed trait RunnerCommand
  
  case class StartRunner(ctx: JobContext, replyTo: ActorRef[JobRunnerResponse]) extends RunnerCommand
  
  case object CancelRunner extends RunnerCommand

  // Internal lifecycle steps for JobRunner
  sealed trait InternalCommand extends RunnerCommand
  
  case object ExecuteValidate extends InternalCommand
  
  case object ExecuteInitialize extends InternalCommand
  
  case object ExecuteRun extends InternalCommand
  
  case object ExecuteFinalize extends InternalCommand
  
  // Internal result messages (private to framework)
  private[framework] sealed trait InternalResult extends RunnerCommand
  
  private[framework] case object ValidationSuccess extends InternalResult
  
  private[framework] case class ValidationFailed(error: String) extends InternalResult
  
  private[framework] case class InitializeSuccess(ctx: JobContext) extends InternalResult
  
  private[framework] case class InitializeFailed(error: String) extends InternalResult
  
  private[framework] case class RunSuccess(ctx: JobContext) extends InternalResult
  
  private[framework] case class RunFailed(error: String) extends InternalResult
  
  private[framework] case object FinalizeComplete extends InternalResult
}
