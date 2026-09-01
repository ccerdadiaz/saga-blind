package sagablind.core

import sagablind.pool.OkvPool
import com.jayway.jsonpath.JsonPath

// ── ParamExtractor ───────────────────────────────────────────────────────────
// Resolves ParamMappings against the OKV pool.
// Produces the Map[String, ujson.Value] that the engine passes to execute/compensate.
//
// Resolution steps:
//   1. Parse the 'from' expression into OkvRef(owner, key, jsonPath)
//   2. Look up owner/key in the pool
//   3. If jsonPath present, apply it to the JSON value
//   4. Bind result to param name

object ParamExtractor:

  def resolve(
    mappings: List[ParamMapping],
    pool:     OkvPool,
  ): Either[String, Map[String, ujson.Value]] =
    mappings.foldLeft(Right(Map.empty): Either[String, Map[String, ujson.Value]]):
      case (Left(err), _) => Left(err)
      case (Right(acc), mapping) =>
        resolveOne(mapping, pool).map(v => acc + (mapping.param -> v))

  private def resolveOne(mapping: ParamMapping, pool: OkvPool): Either[String, ujson.Value] =
    for
      ref   <- OkvRef.parse(mapping.from)
      value <- lookupPool(ref, pool)
      result <- applyPath(ref, value)
    yield result

  private def lookupPool(ref: OkvRef, pool: OkvPool): Either[String, ujson.Value] =
    pool.getByOwner(ref.owner, ref.key)
      .toRight(s"OKV key '${ref.owner}/${ref.key}' not found — owner '${ref.owner}' has not deposited '${ref.key}' yet")

  private def applyPath(ref: OkvRef, value: ujson.Value): Either[String, ujson.Value] =
    ref.jsonPath match
      case None       => Right(value)
      case Some(path) =>
        try
          val raw    = value.toString
          val result = JsonPath.read[Any](raw, s"$$${path}")
          Right(ujson.read(ujson.write(result.toString)))
        catch
          case e: Exception =>
            Left(s"JSONPath '${path}' failed on value from '${ref.owner}/${ref.key}': ${e.getMessage}")
