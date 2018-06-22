import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.DataFrame
import org.apache.log4j.{Level, Logger}

case class Person(name: String, age: Long)
case class Employee(name:String,deptId:Int,gender:String)
case class Dept(id:Int,name:String)

object SparkSqlDemo {
  def main(args: Array[String]): Unit = {
    EmployeeToDept()
  }

  def EmployeeToDept(): Unit ={
    Logger.getLogger("org").setLevel(Level.ERROR)
    val  spark = SparkSession.builder().master("local[2]").appName("test").getOrCreate()
    import spark.implicits._
    val employees=Seq(Employee("Mike",1,"male"), Employee("Tiger",2,"female"),Employee("Marry",1,"female"),
      Employee("Jonny", 2, "female")).toDF().cache()
    val dept=Seq(Dept(1,"first"), Dept(2, "second")).toDF().cache()
    dept.show()
    employees.show()
    employees
      .join(dept, employees("deptId") === dept("id"))
      .where(employees("gender") === "female")
      .groupBy(dept("id"))
      .agg(("id","count"))
      .show()
    employees.createOrReplaceTempView("employee")
    spark.sql("SELECT deptId as id, count(*) as count FROM employee " +
      "where gender='female' group by deptId").show()
  }
}
