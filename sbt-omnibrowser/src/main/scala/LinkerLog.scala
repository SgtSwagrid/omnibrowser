package com.alecdorrington.omnibrowser.plugin

import org.scalajs.logging.{Level, Logger as LinkerLogger}
import sbt.Logger

/** The Scala.js linker's log, as sbt's `log`. */
private[plugin] final class LinkerLog(logger: Logger) extends LinkerLogger:

  override def log(level: Level, message: => String): Unit = level match
    case Level.Error => logger.error(message)
    case Level.Warn  => logger.warn(message)
    case Level.Info  => logger.info(message)
    case Level.Debug => logger.debug(message)

  override def trace(error: => Throwable): Unit = logger.trace(error)
