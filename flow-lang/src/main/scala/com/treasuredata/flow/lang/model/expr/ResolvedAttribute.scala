package com.treasuredata.flow.lang.model.expr

import com.treasuredata.flow.lang.catalog.Catalog
import com.treasuredata.flow.lang.model.{DataType, NodeLocation}
import com.treasuredata.flow.lang.model.plan.*
import wvlet.log.LogSupport

case class SourceColumn(table: Catalog.Table, column: Catalog.TableColumn):
  def fullName: String = s"${table.name}.${column.name}"

case class ResolvedAttribute(
    name: String,
    override val dataType: DataType,
    // user-given qualifier
    qualifier: Qualifier,
    // If this attribute directly refers to a table column, its source column will be set.
    sourceColumn: Option[SourceColumn],
    nodeLocation: Option[NodeLocation]
) extends Attribute
    with LogSupport:

  override lazy val resolved = true

  override def withQualifier(newQualifier: Qualifier): Attribute =
    this.copy(qualifier = newQualifier)

  override def inputAttributes: Seq[Attribute]  = Seq(this)
  override def outputAttributes: Seq[Attribute] = inputAttributes

  override def toString =
    sourceColumn match
      case Some(c) =>
        s"*${prefix}${typeDescription} <- ${c.fullName}"
      case None =>
        s"*${prefix}${typeDescription}"

  override def sourceColumns: Seq[SourceColumn] =
    sourceColumn.toSeq