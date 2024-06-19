package com.treasuredata.flow.lang.compiler.analyzer

import com.treasuredata.flow.lang.StatusCode
import com.treasuredata.flow.lang.compiler.Context
import com.treasuredata.flow.lang.model.DataType.{
  EmptyRelationType,
  NamedType,
  RecordType,
  SchemaType
}
import com.treasuredata.flow.lang.model.{DataType, RelationType}
import com.treasuredata.flow.lang.model.expr.Name
import wvlet.airframe.control.IO
import wvlet.airframe.json.JSON
import wvlet.airframe.json.JSON.{
  JSONArray,
  JSONBoolean,
  JSONDouble,
  JSONLong,
  JSONNull,
  JSONObject,
  JSONString,
  JSONValue
}
import wvlet.airframe.ulid.ULID
import wvlet.log.LogSupport

import java.io.File

object JSONAnalyzer extends LogSupport:
  def analyzeJSONFile(path: String): RelationType =
    val json = IO.readAsString(new File(path))
    debug(json)
    val jsonValue = JSON.parse(json)

    guessSchema(jsonValue)

  class TypeCountMap:
    private var map                = Map.empty[DataType, Int]
    def mostFrequentType: DataType = map.maxBy(_._2)._1
    override def toString: String  = map.toString()
    def observe(dataType: DataType): Unit =
      val count = map.getOrElse(dataType, 0)
      map = map.updated(dataType, count + 1)

  private def guessSchema(json: JSONValue): RelationType =
    // json path -> (data type -> count)
    var schema = Map.empty[String, TypeCountMap]

    def traverse(path: String, v: JSONValue): Unit =
      v match
        case a: JSONArray =>
          a.v
            .foreach: x =>
              traverse(path, x)
        case o: JSONObject =>
          o.v
            .foreach: (k, v) =>
              val nextPath =
                if path.isEmpty then
                  k
                else
                  s"${path}.${k}"
              traverse(nextPath, v)
        case _ =>
          val dataType     = guessDataType(v)
          val typeCountMap = schema.getOrElse(path, TypeCountMap())
          typeCountMap.observe(dataType)
          schema = schema.updated(path, typeCountMap)

    traverse("", json)
    val dataTypes = schema.map: (k, typeMap) =>
      val mostFrequentType = typeMap.mostFrequentType
      NamedType(Name.fromString(k), mostFrequentType)

    SchemaType(ULID.newULIDString, dataTypes.toSeq)

  private def guessDataType(v: JSONValue): DataType =
    v match
      case JSONNull =>
        DataType.NullType
      case b: JSONBoolean =>
        DataType.BooleanType
      case s: JSONString =>
        DataType.StringType
      case i: JSONLong =>
        DataType.LongType
      case d: JSONDouble =>
        DataType.DoubleType
      case _ =>
        DataType.AnyType
