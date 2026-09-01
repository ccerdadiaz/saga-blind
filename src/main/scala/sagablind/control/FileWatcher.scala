package sagablind.control

import sagablind.core.SagaDefinition

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import scala.util.Try

// ── FileWatcher ──────────────────────────────────────────────────────────────
// Watches a directory for .saga files.
// On CREATED → publishes to SagaServiceRegistry
// On DELETED → withdraws from SagaServiceRegistry
// On MODIFIED → not supported by design 
//
// Runs in a dedicated daemon thread — does not block the HTTP server.

class FileWatcher(
  watchDir:   Path,
  registry:   SagaControl,
  onPublish:  SagaDefinition => Unit = _ => (),
  onWithdraw: String => Unit         = _ => (),
  onError:    String => Unit         = msg => System.err.println(s"[FileWatcher] $msg"),
):
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
          case Right(definition) => onPublish(definition)
          case Left(error)       => onError(s"Failed to publish '$path': $error")

    running = true
    val thread = Thread(() => loop(), "saga-blind-filewatcher")
    thread.setDaemon(true)
    thread.start()
    println(s"[FileWatcher] watching ${watchDir.toAbsolutePath}")

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
                      println(s"[FileWatcher] published '${definition.id}'")
                      onPublish(definition)
                    case Left(error) =>
                      onError(s"Failed to publish '$fullPath': $error")
                case ENTRY_DELETE =>
                  registry.withdraw(filename.stripSuffix(".saga"))
                  println(s"[FileWatcher] withdrawn '$filename'")
                  onWithdraw(filename)
                case _ => ()
        key.reset()
