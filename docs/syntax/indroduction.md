
## Relational Operators

wvlet favors lower-case keywords for SQL-like operators. The following is a list of relational operators in wvlet for manipulating table-format data (i.e., relation):

| Operator | Output |
| --- | --- | 
| from `expr` | Rows from the given source table, model, value, or file.  |
| select `expr`, ... | Rows with the given expressions. `select *` is allowed here. |
| select distinct `expr`,... | Rows with distinct values of the given expressions. |
| select `alias` = `expr`, ... | Rows with the given expressions with aliases. |
| select `expr` as `alias`, ... | Rows with the given expressions with aliases. |
| add `expr` (as `alias`)?, ... | Same rows with new columns. |
| exclude `column`, ... | Same rows except the given columns. |
| where `cond` | Rows that satisfy the given condition. |
| transform `column` = `expr`, ... | Same rows with added or updated columns. |  
| group by `column`, ... | Grouped rows by the given columns. Grouping keys can be referenced as `select _1, _2, ...`  in the subsequent operator. This returns `_1: key1, _2: key2, ..., _: list of records` |
| agg `agg_expr`, ... | Rows with the grouping keys in `group by` clause and aggregated values.  This is is a shorthand notation for `select _1, _2, ..., (agg_expr), ...`. In aggr_expr, dot-notation like `_.count`, `(column).sum` can be used for aggregating grouped rows.|
| order by `expr` (asc \| desc)?, ... | Rows sorted by the given expression. 1-origin column indexes can be used like `1`, `2`, |
| limit `n` | Rows up to the given number |
| (left \| right \| cross)? join `table` on `cond or column` | Joined rows with the given condition or the same column |
| pivot on `pivot_column` (in (`v1`, `v2`, ...) )? | Rows whose column values in the pivot column are expanded as columns. |
| pipe `func(args, ...)` | Rows processed by the given table function | 
| pivot on `pivot_column`<br/> (group by `grouping columns`)?<br/> agg `agg_expr` |  Pivoted rows with the given grouping columns and aggregated values.|


## Expressions

One of the major difference from tradtional SQL is that wvlet uses single or double quoted strings for representing string values, and back-quoted strings for referencing column or table names, which might contain space or special characters.

| Operator | Description | 
| --- | --- |
| '(single quote)' | String literal for representing string values, file names, etc. |
| "(double quote)" | Same as single quote strings | 
| \`(back quote)\` | Column or table name, which requires quotations |
| sql"(sql expr)" | SQL expression used for inline expansion |
| sql" ... ${expr} ..." | Interpolated SQL expression with embedded expressions |
| [[expr, ...], ...] | Array of arrays for representing table records |
| [expr, ...] | Array value |
| `_`| underscore refers to the previous input | 
| `agg_func(expr)` over (partition by ... order by ...)  | Window functions for computing aggregate values computed from the entire query result. This follows the same window function syntax with SQL |
| `_1`, `_2`, ... | Refers to grouping keys in the preceding `group by` clause |


### Conditional Expressions

| Operator | Description |
| --- | --- |
| `expr` and `expr` | Logical AND |
| `expr` or  `expr` | Logical OR |
| not `expr` | Logical NOT |
| ! `expr` | Logical NOT |
| `expr` in (v1, v2, ...) | True if the expression value is in the given list |
| `expr` in (from ...) | True if the expression value is in the given list provided by a sub query |
| `expr` not in (`v1`, `v2`, ...) | True if the expression is not in the given list |
| `expr` between `v1` and `v2` | True if the expression value is between v1 and v2, i.e., v1 <= (value) <= v2|
| `expr` like `pattern` | True if the expression matches the given pattern, e.g., , `'abc%'` |
| `expr` is null | True if the expression is null |
| `expr` = null | True if the expression is null |
| `expr` is not null | True if the expression is not null |
| `expr` != null | True if the expression is not null. |
