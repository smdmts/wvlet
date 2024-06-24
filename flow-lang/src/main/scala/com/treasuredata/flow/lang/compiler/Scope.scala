package com.treasuredata.flow.lang.compiler

import com.treasuredata.flow.lang.model.DataType
import com.treasuredata.flow.lang.model.DataType.NamedType
import com.treasuredata.flow.lang.model.expr.Name
import com.treasuredata.flow.lang.model.plan.TableDef
import wvlet.log.LogSupport

import scala.collection.mutable

object Scope:
  def empty = Scope(None)

/**
  * Scope manages a list of table, alias, function definitions that are available in the current
  * context.
  */
class Scope(outer: Option[Scope]) extends LogSupport:
  private val types    = mutable.Map.empty[String, DataType].addAll(DataType.knownPrimitiveTypes)
  private val aliases  = mutable.Map.empty[String, String]
  private val tableDef = mutable.Map.empty[String, TableDef]

  def getAllTypes: Map[String, DataType] = types.toMap
  def getAliases: Map[String, String]    = aliases.toMap

  def getAllTableDefs: Map[String, TableDef] = tableDef.toMap

  def addAlias(alias: Name, typeName: String): Unit = aliases.put(alias.fullName, typeName)

  def addTableDef(tbl: TableDef): Unit = tableDef.put(tbl.name.fullName, tbl)

  def addType(dataType: DataType): Unit             = addType(dataType.typeName, dataType)
  def addType(name: Name, dataType: DataType): Unit = addType(name.fullName, dataType)
  def addType(name: String, dataType: DataType): Unit =
    findType(name) match
      case Some(t) if t.isResolved =>
        trace(s"Type ${name} is already defined: ${t.typeDescription}")
      case _ =>
        trace(s"Add type mapping: ${name} -> ${dataType.typeDescription}")
        types.put(name, dataType)

  def getTableDef(name: Name): Option[TableDef] = tableDef.get(name.fullName)

  def newScope: Scope = Scope(Some(this))

  def resolveType(name: String, seen: Set[String] = Set.empty): Option[DataType] =
    if seen.contains(name) then
      None
    else
      findType(name).map(_.resolved) match
        case Some(r) =>
          if r.isResolved then
            Some(r)
          else
            trace(s"${name} -> ${r} is not resolved")
            resolveType(r.baseTypeName, seen + name)
        case other =>
          other

  def findType(name: String, seen: Set[String] = Set.empty): Option[DataType] =
    if seen.contains(name) then
      None
    val tpe = types
      .get(name)
      // search aliases
      .orElse {
        aliases
          .get(name)
          .flatMap { x =>
            trace(s"Found alias ${name} -> ${x}")
            types.get(x)
          }
      }
      // search table def
      .orElse {
        tableDef.get(name).flatMap(_.getType).flatMap(x => findType(x.fullName, seen + name))
      }
    tpe
