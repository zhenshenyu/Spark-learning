import org.apache.spark.sql.SparkSession
import org.apache.log4j.{Level, Logger}
import scala.io.StdIn


object SparkSqlDemo {
  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.ERROR)
    val rank_file=args(0)
    val access_file=args(1)
    val  spark = SparkSession.builder().appName("SparkSqlDemo").getOrCreate()
    val rank_df=spark.read
      .format("csv")
      .option("header", "true")
      .option("mode", "DROPMALFORMED")
      .csv(rank_file).cache()
    val access_df=spark.read
      .format("com.databricks.spark.csv")
      .option("header", "true")
      .option("mode", "DROPMALFORMED")
      .csv(access_file).cache()
    rank_df.createOrReplaceTempView("rank_table")
    access_df.createOrReplaceTempView("access_table")

    // pre-cache DataFrames
    rank_df.count()
    access_df.count()

    // scan operation
    var start_time=System.currentTimeMillis()
    rank_df.select("*").show()
    var end_time=System.currentTimeMillis()
    println(s"scan operation using ${end_time-start_time} ms")

    // aggregation operation
    start_time=System.currentTimeMillis()
    rank_df
      .groupBy("rank1")
      .agg(("rank1","avg"))
      .show()
    end_time=System.currentTimeMillis()
    println(s"aggregation operation use ${end_time-start_time} ms")

    // join operation
    start_time=System.currentTimeMillis()
    rank_df
      .filter("rank1<20")
      .join(access_df, rank_df("name")===access_df("name"))
      .show()
    end_time=System.currentTimeMillis()
    println(s"join operation use ${end_time-start_time} ms")

    // UDF operation
    // get avg abs differ of ranks
    start_time=System.currentTimeMillis()
    spark.udf.register("differ", (x:Int, y:Int)=>if(x-3*y>=0) x-3*y else 3*y -x)
    spark.sqlContext.sql("select avg(differ(rank2,rank1)) from rank_table").show()
    end_time=System.currentTimeMillis()
    println(s"udf operation (get length of each name) use ${end_time-start_time} ms")

    // interactive
    println("enter your query('exit' to break): ")
    var input=StdIn.readLine()
    while(input!="exit"){
      try{
        start_time=System.currentTimeMillis()
        spark.sqlContext.sql(input).show()
        end_time=System.currentTimeMillis()
        println(s"your query use ${end_time-start_time} ms")
      }
      println("enter your query('exit' to break): ")
      input=StdIn.readLine()
    }
    spark.close()
  }
}
