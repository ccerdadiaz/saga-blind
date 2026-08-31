package sagablind

import ujson.*

// ── SagaRoutes ────────────────────────────────────────────────────────────────
// HTTP routes — thin layer over SagaRuntime. No business logic here.
//
// Routes:
//   POST /sagas/launch    — launch a new saga instance
//   GET  /sagas           — list all saga instances
//   GET  /sagas/available — list registered saga definitions

class SagaRoutes(runtime: SagaRuntime) extends cask.Routes:

  @cask.postJson("/sagas/launch")
  def launch(definition: String, params: ujson.Value = ujson.Obj()): cask.Response[String] =
    val paramsMap = params.objOpt.map(_.toMap).getOrElse(Map.empty)
    runtime.launch(definition, paramsMap) match
      case Right(sagaId) =>
        cask.Response(
          ujson.Obj("sagaId" -> sagaId.value, "status" -> "launched").toString,
          statusCode = 202,
          headers    = Seq("Content-Type" -> "application/json"),
        )
      case Left(error) =>
        cask.Response(
          ujson.Obj("error" -> error).toString,
          statusCode = 400,
          headers    = Seq("Content-Type" -> "application/json"),
        )

  @cask.get("/sagas")
  def list(): cask.Response[String] =
    val rows = runtime.list().map: row =>
      ujson.Obj(
        "sagaId"     -> row.sagaId.value,
        "definition" -> row.definition,
        "status"     -> row.status.toString,
        "startedAt"  -> row.startedAt,
        "updatedAt"  -> row.updatedAt,
      )
    cask.Response(
      ujson.Arr(rows*).toString,
      statusCode = 200,
      headers    = Seq("Content-Type" -> "application/json"),
    )

  @cask.get("/sagas/available")
  def available(): cask.Response[String] =
    cask.Response(
      ujson.Arr(runtime.available().map(ujson.Str(_))*).toString,
      statusCode = 200,
      headers    = Seq("Content-Type" -> "application/json"),
    )

  initialize()


// ── Control routes ────────────────────────────────────────────────────────────

  @cask.post("/sagas/definitions/:name/pause")
  def pause(name: String): cask.Response[String] =
    runtime.pause(name) match
      case Right(()) => ok(s"Saga '$name' paused")
      case Left(err) => bad(err)

  @cask.post("/sagas/definitions/:name/continue")
  def continue(name: String): cask.Response[String] =
    runtime.continue(name) match
      case Right(()) => ok(s"Saga '$name' resumed")
      case Left(err) => bad(err)

  @cask.post("/sagas/definitions/:name/stop")
  def stop(name: String): cask.Response[String] =
    runtime.stop(name) match
      case Right(()) => ok(s"Saga '$name' stopped — in-flight instances will be compensated")
      case Left(err) => bad(err)

  @cask.delete("/sagas/definitions/:name")
  def remove(name: String): cask.Response[String] =
    runtime.remove(name) match
      case Right(()) => ok(s"Saga '$name' removed")
      case Left(err) => bad(err)

  @cask.get("/sagas/definitions")
  def definitions(): cask.Response[String] =
    val entries = runtime.definitions().map: entry =>
      ujson.Obj(
        "name"   -> entry.definition.id,
        "status" -> entry.status.toString,
        "jar"    -> entry.definition.jarPath,
      )
    cask.Response(
      ujson.Arr(entries*).toString,
      statusCode = 200,
      headers    = Seq("Content-Type" -> "application/json"),
    )

  private def ok(msg: String): cask.Response[String] =
    cask.Response(
      ujson.Obj("message" -> msg).toString,
      statusCode = 200,
      headers    = Seq("Content-Type" -> "application/json"),
    )

  private def bad(err: String): cask.Response[String] =
    cask.Response(
      ujson.Obj("error" -> err).toString,
      statusCode = 400,
      headers    = Seq("Content-Type" -> "application/json"),
    )
