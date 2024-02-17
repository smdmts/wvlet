package com.treasuredata.flow.lang.model

import com.treasuredata.flow.lang.model.expr.*
import com.treasuredata.flow.lang.model.plan.*

/*
 * SQL statements for changing the table schema or catalog
 */
sealed trait DDL extends LogicalPlan with LeafPlan

case class SchemaDef(
    name: String,
    columns: Seq[ColumnDef],
    nodeLocation: Option[NodeLocation]
) extends DDL

case class CreateSchema(
    schema: QName,
    ifNotExists: Boolean,
    properties: Option[Seq[SchemaProperty]],
    nodeLocation: Option[NodeLocation]
) extends DDL

case class DropDatabase(database: QName, ifExists: Boolean, cascade: Boolean, nodeLocation: Option[NodeLocation])
    extends DDL

case class RenameDatabase(database: QName, renameTo: Identifier, nodeLocation: Option[NodeLocation]) extends DDL

case class CreateTable(
    table: QName,
    ifNotExists: Boolean,
    tableElems: Seq[TableElement],
    nodeLocation: Option[NodeLocation]
) extends DDL

case class DropTable(table: QName, ifExists: Boolean, nodeLocation: Option[NodeLocation]) extends DDL

case class RenameTable(table: QName, renameTo: QName, nodeLocation: Option[NodeLocation]) extends DDL

case class RenameColumn(table: QName, column: Identifier, renameTo: Identifier, nodeLocation: Option[NodeLocation])
    extends DDL

case class DropColumn(table: QName, column: Identifier, nodeLocation: Option[NodeLocation]) extends DDL

case class AddColumn(table: QName, column: ColumnDef, nodeLocation: Option[NodeLocation]) extends DDL

case class CreateView(viewName: QName, replace: Boolean, query: Relation, nodeLocation: Option[NodeLocation])
    extends DDL

case class DropView(viewName: QName, ifExists: Boolean, nodeLocation: Option[NodeLocation]) extends DDL

/**
  * A base trait for all update operations (e.g., add/delete the table contents).
  */
trait Update extends LogicalPlan

case class CreateTableAs(
    table: QName,
    ifNotEotExists: Boolean,
    columnAliases: Option[Seq[Identifier]],
    query: Relation,
    nodeLocation: Option[NodeLocation]
) extends DDL
    with Update
    with UnaryRelation:
  override def child: Relation = query

case class InsertInto(
    table: QName,
    columnAliases: Option[Seq[Identifier]],
    query: Relation,
    nodeLocation: Option[NodeLocation]
) extends Update
    with UnaryRelation:
  override def child: Relation = query

case class Delete(table: QName, where: Option[Expression], nodeLocation: Option[NodeLocation])
    extends Update
    with LeafPlan {}
