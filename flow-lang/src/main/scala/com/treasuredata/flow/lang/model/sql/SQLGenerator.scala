package com.treasuredata.flow.lang.model.sql

import com.treasuredata.flow.lang.model.plan.LogicalPlan
import wvlet.log.LogSupport
import com.treasuredata.flow.lang.model.expr.*
import com.treasuredata.flow.lang.model.plan.*

case class SQLGeneratorConfig(
    indent: Int = 2
)

object SQLGenerator:
  def toSQL(m: LogicalPlan): String = SQLGenerator().print(m)

class SQLGenerator(config: SQLGeneratorConfig = SQLGeneratorConfig()) extends LogSupport:
  private def unknown(e: Any): String =
    if e != null then
      warn(s"Unknown model: ${e} ${e.getClass.getSimpleName}")
      e.toString
    else ""

  private def seqBuilder = Seq.newBuilder[String]

  extension (expr: Expression) def sqlExpr: String = printExpression(expr)

  def print(m: LogicalPlan): String =
    m match
      case InsertInto(table, aliases, query, _) =>
        val b = seqBuilder
        b += "INSERT INTO"
        b += printExpression(table)
        aliases.map { x => b += s"(${x.map(printExpression).mkString(", ")})" }
        b += printRelation(query)
        b.result().mkString(" ")
      case Delete(table, condOpt, _) =>
        val b = seqBuilder
        b += "DELETE FROM"
        b += printExpression(table)
        condOpt.map { x =>
          b += "WHERE"
          b += printExpression(x)
        }
        b.result().mkString(" ")
//      case d: DDL      => printDDL(d)
      case r: Relation => printRelation(r)
      case other       => unknown(other)

  private def findNonEmpty(in: Relation): Option[Relation] =
    // Look for FROM clause candidates inside Project/Aggregate/Filter nodes
    in match
      case EmptyRelation(_) => None
      case other            => Some(other)

  private def collectFilterExpression(stack: List[Relation]): Seq[Expression] =
    // We need to terminate traversal at Project/Aggregate node because these will create another SELECT statement.
    stack.reverse.collect { case f @ Filter(in, filterExpr, _) =>
      filterExpr
    }

  private def printSetOperation(s: SetOperation, context: List[Relation]): String =
    val isDistinct = containsDistinctPlan(context)
    val op = s match
      case Union(relations, _) =>
        if isDistinct then "UNION" else "UNION ALL"
      case Except(left, right, _) =>
        if isDistinct then "EXCEPT" else "EXCEPT ALL"
      case Intersect(relations, _) =>
        if isDistinct then "INTERSECT" else "INTERSECT ALL"
    s.children.map(printRelation).mkString(s" ${op} ")

  private def containsDistinctPlan(context: List[Relation]): Boolean =
    context.exists {
      case e: Distinct => true
      case _           => false
    }

  private def collectChildFilters(r: Relation): List[Filter] =
    r match
      case f @ Filter(in, _, _) =>
        f :: collectChildFilters(in)
      case other =>
        Nil

  private def printQuery(q: Query, context: List[Relation]): String =
    // We need to pull-up Filter operators from child relations to build WHERE clause
    // e.g., Selection(in:Filter(Filter( ...)), ...)
    val childFilters: List[Filter] = collectChildFilters(q.child)
    val nonFilterChild =
      if childFilters.nonEmpty then childFilters.last.child
      else q.child

    val b = Seq.newBuilder[String]
    b += "SELECT *"
    findNonEmpty(nonFilterChild).map { f =>
      b += "FROM"
      b += printRelationWithParenthesesIfNecessary(f)
    }

    val filterSet = q.child match
      case Project(_, _, _) =>
        // Merge parent and child Filters
        collectFilterExpression(context) ++ collectFilterExpression(childFilters)
      case AggregateSelect(_, _, _, _, _) =>
        // We cannot push down parent Filters
        collectFilterExpression(childFilters)
      case n: NamedRelation =>
        collectFilterExpression(childFilters)
      case f: Filter =>
        collectFilterExpression(List(f))
      case t: Transform =>
        collectFilterExpression(childFilters)
      case other =>
        Nil

    if filterSet.nonEmpty then
      b += "WHERE"
      val cond = filterSet.reduce((f1, f2) => And(f1, f2, None))
      b += printExpression(cond)

    b.result().mkString(" ")

  private def printSelection(s: UnaryRelation, context: List[Relation]): String =
    // We need to pull-up Filter operators from child relations to build WHERE clause
    // e.g., Selection(in:Filter(Filter( ...)), ...)
    val childFilters: List[Filter] = collectChildFilters(s.child)
    val nonFilterChild =
      if childFilters.nonEmpty then childFilters.last.child
      else s.child

    val b = Seq.newBuilder[String]
    b += "SELECT"
    if containsDistinctPlan(context) then b += "DISTINCT"

    s match
      case s: Selection =>
        b += (s.selectItems.map(printSelectItem).mkString(", "))
      case other =>
        b += "*"

    findNonEmpty(nonFilterChild).map { f =>
      b += "FROM"
      b += printRelationWithParenthesesIfNecessary(f)
    }

    val filterSet = s match
      case Project(_, _, _) =>
        // Merge parent and child Filters
        collectFilterExpression(context) ++ collectFilterExpression(childFilters)
      case AggregateSelect(_, _, _, _, _) =>
        // We cannot push down parent Filters
        collectFilterExpression(childFilters)
      case n: NamedRelation =>
        collectFilterExpression(childFilters)
      case f: Filter =>
        collectFilterExpression(childFilters)
      case t: Transform =>
        collectFilterExpression(childFilters)

    if filterSet.nonEmpty then
      b += "WHERE"
      val cond = filterSet.reduce((f1, f2) => And(f1, f2, None))
      b += printExpression(cond)

    s match
      case AggregateSelect(_, _, groupingKeys, having, _) =>
        if groupingKeys.nonEmpty then b += s"GROUP BY ${groupingKeys.map(printExpression).mkString(", ")}"
        having.map { h =>
          b += "HAVING"
          b += printExpression(h)
        }
      case _ =>
    b.result().mkString(" ")

  def printRelation(r: Relation): String = printRelation(r, List.empty)

  def printRelation(r: Relation, context: List[Relation] = List.empty): String =
    r match
      case s: SetOperation =>
        // Need to pass the context to disginguish union/union all, etc.
        printSetOperation(s, context)
      case q: Query =>
        q.body match
          case s: Selection => printSelection(s, q :: context)
          case _            => printQuery(q, q :: context)
      case Distinct(in, _) =>
        printRelation(in, r :: context)
      case p @ Project(in, selectItems, _) =>
        printSelection(p, context)
      case a @ AggregateSelect(in, selectItems, groupingKeys, having, _) =>
        printSelection(a, context)
//      case c: CTERelationRef =>
//        c.name
      case TableRef(t, _) =>
        printNameWithQuotationsIfNeeded(t.fullName)
//      case t: TableScan =>
//        printNameWithQuotationsIfNeeded(t.fullName)
      case Limit(in, l, _) =>
        val s = seqBuilder
        s += printRelation(in, context)
        s += s"LIMIT ${printExpression(l)}"
        s.result().mkString(" ")
      case Sort(in, orderBy, _) =>
        val s = seqBuilder
        s += printRelation(in, context)
        s += "ORDER BY"
        s += orderBy.map(x => printExpression(x)).mkString(", ")
        s.result().mkString(" ")
      case n: NamedRelation =>
        s"SELECT * FROM (${printSelection(n, r :: context)}) as ${n.name.sqlExpr}"
      case ParenthesizedRelation(r, _) =>
        s"(${printRelation(r, context)})"
      case AliasedRelation(relation, alias, columnNames, _) =>
        val r = printRelation(relation, context)
        val c = columnNames.map(x => s"(${x.mkString(", ")})").getOrElse("")
        relation match
          case TableRef(x, _) => s"${r} AS ${alias.value}${c}"
//          case TableScan(x, _, _, _)       => s"${r} AS ${alias.sqlExpr}${c}"
          case ParenthesizedRelation(x, _) => s"${r} AS ${alias.value}${c}"
          case Unnest(_, _, _)             => s"${r} AS ${alias.value}${c}"
          case Lateral(_, _)               => s"${r} AS ${alias.value}${c}"
          case _                           => s"(${r}) AS ${alias.value}${c}"
      case Join(joinType, left, right, cond, _) =>
        val l = printRelationWithParenthesesIfNecessary(left)
        val r = printRelationWithParenthesesIfNecessary(right)
        val c = cond match
          case NaturalJoin(_)                => ""
          case JoinUsing(columns, _)         => s" USING (${columns.map(_.sqlExpr).mkString(", ")})"
          case ResolvedJoinUsing(columns, _) => s" USING (${columns.map(_.fullName).mkString(", ")})"
          case JoinOn(expr, _)               => s" ON ${printExpression(expr)}"
          case JoinOnEq(keys, _)             => s" ON ${printExpression(Expression.concatWithEq(keys))}"
        joinType match
          case InnerJoin      => s"${l} JOIN ${r}${c}"
          case LeftOuterJoin  => s"${l} LEFT JOIN ${r}${c}"
          case RightOuterJoin => s"${l} RIGHT JOIN ${r}${c}"
          case FullOuterJoin  => s"${l} FULL OUTER JOIN ${r}${c}"
          case CrossJoin      => s"${l} CROSS JOIN ${r}${c}"
          case ImplicitJoin   => s"${l}, ${r}${c}"
      case Values(exprs, _) =>
        s"(VALUES ${exprs.map(printExpression _).mkString(", ")})"
      case Unnest(cols, ord, _) =>
        val b = seqBuilder
        b += s"UNNEST (${cols.map(printExpression).mkString(", ")})"
        if ord then b += "WITH ORDINALITY"
        b.result().mkString(" ")
      case Lateral(q, _) =>
        val b = seqBuilder
        b += "LATERAL"
        b += s"(${printRelation(q)})"
        b.result().mkString(" ")
      case LateralView(in, exprs, tableAlias, columnAliases, _) =>
        val b = seqBuilder
        b += printRelation(in)
        b += "LATERAL VIEW explode ("
        b += exprs.map(printExpression).mkString(", ")
        b += ")"
        b += printExpression(tableAlias)
        b += "AS"
        b += columnAliases.map(printExpression).mkString(", ")
        b.result().mkString(" ")
      case j: JSONFileScan =>
        s"'${j.path}'"
      case other => unknown(other)

  def printRelationWithParenthesesIfNecessary(r: Relation): String =
    r match
      case _: Selection    => s"(${printRelation(r)})"
      case _: SetOperation => s"(${printRelation(r)})"
      case _: Limit        => s"(${printRelation(r)})"
      case _: Filter       => s"(${printRelation(r)})"
      case _: Sort         => s"(${printRelation(r)})"
      case _: Distinct     => s"(${printRelation(r)})"
      case _               => printRelation(r)

//  def printDDL(e: DDL): String = {
//    e match {
//      case CreateSchema(name, ifNotExists, propsOpt, _) =>
//        val e = if (ifNotExists) "IF NOT EXISTS " else ""
//        val w = propsOpt.map(props => s" WITH (${props.map(printExpression).mkString(", ")})").getOrElse("")
//        s"CREATE SCHEMA ${e}${name.sqlExpr}${w}"
//      case DropSchema(name, ifExists, cascade, _) =>
//        val s = Seq.newBuilder[String]
//        s += "DROP SCHEMA"
//        if (ifExists) {
//          s += "IF EXISTS"
//        }
//        s += name.sqlExpr
//        if (cascade) {
//          s += "CASCADE"
//        }
//        s.result().mkString(" ")
//      case RenameSchema(from, to, _) =>
//        s"ALTER SCHEMA ${from.sqlExpr} RENAME TO ${to.sqlExpr}"
//      case CreateTable(name, ifNotExists, tableElements, _) =>
//        val e     = if (ifNotExists) "IF NOT EXISTS " else ""
//        val elems = tableElements.map(printExpression).mkString(", ")
//        s"CREATE TABLE ${e}${name} (${elems})"
//      case CreateTableAs(name, ifNotExists, columnAliases, query, _) =>
//        val e = if (ifNotExists) "IF NOT EXISTS " else ""
//        val aliases =
//          columnAliases
//                  .map { x => s"(${x.map(printExpression).mkString(", ")})" }.getOrElse("")
//        s"CREATE TABLE ${e}${name.sqlExpr}${aliases} AS ${print(query)}"
//      case DropTable(table, ifExists, _) =>
//        val b = Seq.newBuilder[String]
//        b += "DROP TABLE"
//        if (ifExists) {
//          b += "IF EXISTS"
//        }
//        b += printExpression(table)
//        b.result().mkString(" ")
//      case RenameTable(from, to, _) =>
//        val b = seqBuilder
//        b += "ALTER TABLE"
//        b += printExpression(from)
//        b += "RENAME TO"
//        b += printExpression(to)
//        b.result().mkString(" ")
//      case RenameColumn(table, from, to, _) =>
//        val b = seqBuilder
//        b += "ALTER TABLE"
//        b += printExpression(table)
//        b += "RENAME COLUMN"
//        b += printExpression(from)
//        b += "TO"
//        b += printExpression(to)
//        b.result().mkString(" ")
//      case DropColumn(table, col, _) =>
//        val b = seqBuilder
//        b += "ALTER TABLE"
//        b += printExpression(table)
//        b += "DROP COLUMN"
//        b += printExpression(col)
//        b.result().mkString(" ")
//      case AddColumn(table, colDef, _) =>
//        val b = seqBuilder
//        b += "ALTER TABLE"
//        b += printExpression(table)
//        b += "ADD COLUMN"
//        b += printExpression(colDef)
//        b.result().mkString(" ")
//      case CreateView(name, replace, query, _) =>
//        val b = seqBuilder
//        b += "CREATE"
//        if (replace) {
//          b += "OR REPLACE"
//        }
//        b += "VIEW"
//        b += printExpression(name)
//        b += "AS"
//        b += print(query)
//        b.result().mkString(" ")
//      case DropView(name, ifExists, _) =>
//        val b = seqBuilder
//        b += "DROP VIEW"
//        if (ifExists) {
//          b += "IF EXISTS"
//        }
//        b += printExpression(name)
//        b.result().mkString(" ")
//    }
//  }

  def printSelectItem(e: Expression): String =
    e match
//      case a: ResolvedAttribute =>
//        a.sqlExpr
      case other =>
        printExpression(other)

  def printExpression(e: Expression): String =
    e match
      case i: UnquotedIdentifier =>
        i.value
      case i: BackQuotedIdentifier =>
        s"`${i.value}`"
      case i: QuotedIdentifier =>
        s"'${i.value}'"
      case i: Identifier =>
        i.value
      case s: StringLiteral =>
        s"'${s.value}'"
      case l: Literal =>
        l.stringValue
      case g: GroupingKey =>
        g.child.sqlExpr
      case ParenthesizedExpression(expr, _) =>
        s"(${expr.sqlExpr})"
      case a: Alias =>
        val e = a.expr.sqlExpr
        s"${e} AS ${printNameWithQuotationsIfNeeded(a.name)}"
      case s @ SingleColumn(ex, _, _) =>
        s.fullName
      case m: MultiSourceColumn =>
        m.fullName
      case a: AllColumns =>
        a.fullName
      case a: Attribute =>
        printNameWithQuotationsIfNeeded(a.fullName)
      case SortItem(key, ordering, nullOrdering, _) =>
        val k  = key.sqlExpr
        val o  = ordering.map(x => s" ${x}").getOrElse("")
        val no = nullOrdering.map(x => s" ${x}").getOrElse("")
        s"${k}${o}${no}"
      case FunctionCall(ctx, name, args, _) =>
        val argList = args.map(_.sqlExpr).mkString(", ")
//        val d       = if (distinct) "DISTINCT " else ""
//        val wd = window
//                .map { w =>
//                  val s = Seq.newBuilder[String]
//                  if (w.partitionBy.nonEmpty) {
//                    s += "PARTITION BY"
//                    s += w.partitionBy.map(x => printExpression(x)).mkString(", ")
//                  }
//                  if (w.orderBy.nonEmpty) {
//                    s += "ORDER BY"
//                    s += w.orderBy.map(x => printExpression(x)).mkString(", ")
//                  }
//                  w.frame.map(x => s += x.toString)
//                  s" OVER (${s.result().mkString(" ")})"
//                }
//                .getOrElse("")
//        val f = filter.map(x => s" FILTER (WHERE ${printExpression(x)})").getOrElse("")
        val prefix = ctx.map { c => s"${c.sqlExpr}." }.getOrElse("")
        s"${prefix}${name}(${argList})"
      case Extract(interval, expr, _) =>
        s"EXTRACT(${interval} FROM ${printExpression(expr)})"
      case QName(parts, _) =>
        parts.mkString(".")
      case Cast(expr, tpe, tryCast, _) =>
        val cmd = if tryCast then "TRY_CAST" else "CAST"
        s"${cmd}(${expr.sqlExpr} AS ${tpe})"
      case c: ConditionalExpression =>
        printConditionalExpression(c)
      case ArithmeticBinaryExpr(tpe, left, right, _) =>
        s"${left.sqlExpr} ${tpe.symbol} ${right.sqlExpr}"
      case ArithmeticUnaryExpr(sign, value, _) =>
        s"${sign.symbol} ${value.sqlExpr}"
      case Exists(subQuery, _) =>
        s"EXISTS(${subQuery.sqlExpr})"
      case SubQueryExpression(query, _) =>
        s"(${printRelation(query)})"
      case CaseExpr(operand, whenClauses, defaultValue, _) =>
        val s = Seq.newBuilder[String]
        s += "CASE"
        operand.map(x => s += x.sqlExpr)
        whenClauses.map { w =>
          s += "WHEN"
          s += w.condition.sqlExpr
          s += "THEN"
          s += w.result.sqlExpr
        }
        defaultValue.map { x =>
          s += "ELSE"
          s += x.sqlExpr
        }
        s += "END"
        s.result().mkString(" ")
      case w: WindowFrame =>
        w.toString
//      case SchemaProperty(k, v, _) =>
//        s"${k.sqlExpr} = ${v.sqlExpr}"
      case ColumnDef(name, tpe, _) =>
        s"${name.sqlExpr} ${tpe.sqlExpr}"
      case ColumnType(tpe, _) =>
        tpe
      case ColumnDefLike(table, includeProperties, _) =>
        val inc = if includeProperties then "INCLUDING" else "EXCLUDING"
        s"LIKE ${printExpression(table)} ${inc} PROPERTIES"
      case ArrayConstructor(values, _) =>
        s"ARRAY[${values.map(printExpression).mkString(", ")}]"
      case RowConstructor(values, _) =>
        s"(${values.map(printExpression).mkString(", ")})"
      case Parameter(index, _) =>
        "?"
      case other => unknown(other)

  def printConditionalExpression(c: ConditionalExpression): String =
    c match
      case NoOp(_) => ""
      case Eq(a, b, _) =>
        b match
          case n: NullLiteral =>
            s"${a.sqlExpr} IS ${b.sqlExpr}"
          case _ =>
            s"${a.sqlExpr} = ${b.sqlExpr}"
      case NotEq(a, b, _) =>
        b match
          case n: NullLiteral =>
            s"${a.sqlExpr} IS NOT ${b.sqlExpr}"
          case _ =>
            s"${a.sqlExpr} != ${b.sqlExpr}"
      case And(a, b, _) =>
        s"${a.sqlExpr} AND ${b.sqlExpr}"
      case Or(a, b, _) =>
        s"${a.sqlExpr} OR ${b.sqlExpr}"
      case Not(e, _) =>
        s"NOT ${e.sqlExpr}"
      case LessThan(a, b, _) =>
        s"${a.sqlExpr} < ${b.sqlExpr}"
      case LessThanOrEq(a, b, _) =>
        s"${a.sqlExpr} <= ${b.sqlExpr}"
      case GreaterThan(a, b, _) =>
        s"${a.sqlExpr} > ${b.sqlExpr}"
      case GreaterThanOrEq(a, b, _) =>
        s"${a.sqlExpr} >= ${b.sqlExpr}"
      case Between(e, a, b, _) =>
        s"${e.sqlExpr} BETWEEN ${a.sqlExpr} and ${b.sqlExpr}"
      case NotBetween(e, a, b, _) =>
        s"${e.sqlExpr} NOT BETWEEN ${a.sqlExpr} and ${b.sqlExpr}"
      case IsNull(a, _) =>
        s"${a.sqlExpr} IS NULL"
      case IsNotNull(a, _) =>
        s"${a.sqlExpr} IS NOT NULL"
      case In(a, list, _) =>
        val in = list.map(x => x.sqlExpr).mkString(", ")
        s"${a.sqlExpr} IN (${in})"
      case NotIn(a, list, _) =>
        val in = list.map(x => x.sqlExpr).mkString(", ")
        s"${a.sqlExpr} NOT IN (${in})"
      case InSubQuery(a, in, _) =>
        s"${a.sqlExpr} IN (${printRelation(in)})"
      case NotInSubQuery(a, in, _) =>
        s"${a.sqlExpr} NOT IN (${printRelation(in)})"
      case Like(a, e, _) =>
        s"${a.sqlExpr} LIKE ${e.sqlExpr}"
      case NotLike(a, e, _) =>
        s"${a.sqlExpr} NOT LIKE ${e.sqlExpr}"
      case DistinctFrom(a, e, _) =>
        s"${a.sqlExpr} IS DISTINCT FROM ${e.sqlExpr}"
      case NotDistinctFrom(a, e, _) =>
        s"${a.sqlExpr} IS NOT DISTINCT FROM ${e.sqlExpr}"

  private def printNameWithQuotationsIfNeeded(name: String): String =
    QName.apply(name, None).sqlExpr
