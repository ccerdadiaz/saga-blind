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
