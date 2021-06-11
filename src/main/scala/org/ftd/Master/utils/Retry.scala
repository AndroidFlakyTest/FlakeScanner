package org.ftd.Master.utils

object Retry {

  def retry[T, E <: Throwable](n: Int, fn: => T, onlyException: Class[E]): T = {
    retry(n, fn, (e: Throwable) => onlyException.isInstance(e) )
  }

  @annotation.tailrec
  def retry[T](n: Int, fn: => T, retryCond: (Throwable) => Boolean): T = {
    util.Try { fn } match {
      case util.Success(x) => x
      case util.Failure(e) =>
        if (n > 1 && retryCond(e)) retry(n-1, fn, retryCond) else throw e
    }
  }

}
