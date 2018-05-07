package PageRank
import org.apache.spark.{SparkConf,SparkContext}

object PageRank {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf().setAppName("PageRank")
    val sc = new SparkContext(conf)
    val links1 = sc.textFile("/pagerank/")
  }
}
