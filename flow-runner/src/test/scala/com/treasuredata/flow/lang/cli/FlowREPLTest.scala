package com.treasuredata.flow.lang.cli

import wvlet.airspec.AirSpec

class FlowREPLTest extends AirSpec:
  test("help") {
    FlowCli.main("repl -c 'help'")
  }

  test("model in the working folder") {
    FlowCli.main("repl -w spec/model1 -c 'from person_filter(2)'")
  }

  test("def new model") {
    FlowCli
      .main("repl -w spec/model1 -c 'model m(v:int) = from person where id = v end' -c 'from m(1)'")
  }

  test("limit shown rows") {
    FlowCli.main(
      """repl -c "from 'https://shell.duckdb.org/data/tpch/0_01/parquet/customer.parquet' select c_custkey, c_name, c_nationkey" """
    )
  }
