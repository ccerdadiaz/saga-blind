package sagablind.control

import sagablind.core.SagaDefinition
import sagablind.{SagaLogger, BoundLogger}

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import scala.util.Try

// ── FileWatcher ──────────────────────────────────────────────────────────────
// Watches a directory for .saga files.
// On CREATED → publishes to SagaControl
// On DELETED → withdraws from SagaControl
//
// ENTRY_MODIFY is intentionally not handled — the engine does not support
// versioning. To update a definition: stop, wait, delete, drop new version.
//
// Runs in a dedicated daemon thread — does not block the HTTP server.
// WatchService.take() blocks without consuming CPU — no polling.

class FileWatcher(
  watchDir:   Path,
  registry:   SagaControl,
  logger:     SagaLogger = SagaLogger.noOp,
  onPublish:  SagaDefinition => Unit = _ => (),
  onWithdraw: String => Unit         = _ => (),
):
  private val log: BoundLogger      = logger.forComponent("fw")
  private val watchService: WatchService = FileSystems.getDefault.newWatchService()
  private var running = false

  def start(): Unit =
    if !Files.exists(watchDir) then
      Files.createDirectories(watchDir)

    watchDir.register(watchService, ENTRY_CREATE, ENTRY_DELETE)

    Files.list(watchDir)
      .filter(_.toString.endsWith(".saga"))
      .forEach: path =>
        registry.publish(path) match
          case Right(definition) =>
            log.info(s"published '${definition.id}'")
            onPublish(definition)
          case Left(error) =>
            log.error(s"Failed to publish '$path': $error")

    running = true
    val thread = Thread(() => loop(), "saga-blind-filewatcher")
    thread.setDaemon(true)
    thread.start()
    log.info(s"watching ${watchDir.toAbsolutePath}")

  def stop(): Unit =
    running = false
    Try(watchService.close())

  private def loop(): Unit =
    while running do
      Try(watchService.take()).foreach: key =>
        key.pollEvents().forEach: event =>
          val kind = event.kind()
          if kind != OVERFLOW then
            val filename = event.context().asInstanceOf[Path].toString
            if filename.endsWith(".saga") then
              val fullPath = watchDir.resolve(filename)
              kind match
                case ENTRY_CREATE =>
                  registry.publish(fullPath) match
                    case Right(definition) =>
                      log.info(s"published '${definition.id}'")
                      onPublish(definition)
                    case Left(error) =>
                      log.error(s"Failed to publish '$fullPath': $error")
                case ENTRY_DELETE =>
                  registry.withdraw(filename.stripSuffix(".saga"))
                  log.info(s"withdrawn '$filename'")
                  onWithdraw(filename)
                case _ => ()
        key.reset()
