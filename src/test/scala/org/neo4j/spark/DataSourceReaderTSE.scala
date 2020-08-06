package org.neo4j.spark

import java.sql.Timestamp
import java.time.{LocalDateTime, OffsetDateTime, ZoneOffset}

import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.spark.SparkException
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.catalyst.util.ArrayBasedMapData
import org.junit.Assert._
import org.junit.Test
import org.neo4j.driver.summary.ResultSummary
import org.neo4j.driver.{SessionConfig, Transaction, TransactionWork}

import scala.collection.JavaConverters._

class DataSourceReaderTSE extends SparkConnectorScalaBaseTSE {

  @Test
  def testReadNodeHasIdField(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {name: 'John'})")

    /**
     * utnaf: Since we can't be sure we are in total isolation, and the id is generated
     * internally by neo4j, we just check that the <id> field is an integer and is greater
     * than -1
     */
    assertTrue(df.select("<id>").collectAsList().get(0).getLong(0) > -1)
  }

  @Test
  def testReadNodeHasLabelsField(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person:Customer {name: 'John'})")

    val result = df.select("<labels>").collectAsList().get(0).getAs[Seq[String]](0)

    assertEquals("Person", result.head)
    assertEquals("Customer", result(1))
  }

  @Test
  def testReadNodeHasUnusualLabelsField(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:`Foo Bar`:Person {name: 'John'})")

    val result = df.select("<labels>").collectAsList().get(0).getAs[Seq[String]](0)

    assertEquals(Set("Person", "Foo Bar"), result.toSet[String])
  }

  @Test
  def testReadNodeWithString(): Unit = {
    val name: String = "John"
    val df: DataFrame = initTest(s"CREATE (p:Person {name: '$name'})")

    assertEquals(name, df.select("name").collectAsList().get(0).getString(0))
  }

  @Test
  def testReadNodeWithLong(): Unit = {
    val age: Long = 42
    val df: DataFrame = initTest(s"CREATE (p:Person {age: $age})")

    assertEquals(age, df.select("age").collectAsList().get(0).getLong(0))
  }

  @Test
  def testReadNodeWithDouble(): Unit = {
    val score: Double = 3.14
    val df: DataFrame = initTest(s"CREATE (p:Person {score: $score})")

    assertEquals(score, df.select("score").collectAsList().get(0).getDouble(0), 0)
  }

  @Test
  def testReadNodeWithLocalTime(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {aTime: localtime({hour:12, minute: 23, second: 0, millisecond: 294})})")

    val result = df.select("aTime").collectAsList().get(0).getAs[GenericRowWithSchema](0)

    assertEquals("local-time", result.get(0))
    assertEquals("12:23:00.294", result.get(1))
  }

  @Test
  def testReadNodeWithTime(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {aTime: time({hour:12, minute: 23, second: 0, millisecond: 294})})")

    val result = df.select("aTime").collectAsList().get(0).getAs[GenericRowWithSchema](0)

    assertEquals("offset-time", result.get(0))
    assertEquals("12:23:00.294Z", result.get(1))
  }

  @Test
  def testReadNodeWithLocalDateTime(): Unit = {
    val localDateTime = "2007-12-03T10:15:30"
    val df: DataFrame = initTest(s"CREATE (p:Person {aTime: localdatetime('$localDateTime')})")

    val result = df.select("aTime").collectAsList().get(0).getTimestamp(0)


    assertEquals(Timestamp.from(LocalDateTime.parse(localDateTime).toInstant(ZoneOffset.UTC)), result)
  }

  @Test
  def testReadNodeWithZonedDateTime(): Unit = {
    val datetime = "2015-06-24T12:50:35.556+01:00"
    val df: DataFrame = initTest(s"CREATE (p:Person {aTime: datetime('$datetime')})")

    val result = df.select("aTime").collectAsList().get(0).getTimestamp(0)


    assertEquals(Timestamp.from(OffsetDateTime.parse(datetime).toInstant), result)
  }

  @Test
  def testReadNodeWithPoint(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {location: point({x: 12.12, y: 13.13})})")

    val res = df.select("location").collectAsList().get(0).getAs[GenericRowWithSchema](0);

    assertEquals("point-2d", res.get(0))
    assertEquals(7203, res.get(1))
    assertEquals(12.12, res.get(2))
    assertEquals(13.13, res.get(3))
  }

  @Test
  def testReadNodeWithGeoPoint(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {location: point({longitude: 12.12, latitude: 13.13})})")

    val res = df.select("location").collectAsList().get(0).getAs[GenericRowWithSchema](0);

    assertEquals("point-2d", res.get(0))
    assertEquals(4326, res.get(1))
    assertEquals(12.12, res.get(2))
    assertEquals(13.13, res.get(3))
  }

  @Test
  def testReadNodeWithPoint3D(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {location: point({x: 12.12, y: 13.13, z: 1})})")

    val res = df.select("location").collectAsList().get(0).getAs[GenericRowWithSchema](0)

    assertEquals("point-3d", res.get(0))
    assertEquals(9157, res.get(1))
    assertEquals(12.12, res.get(2))
    assertEquals(13.13, res.get(3))
    assertEquals(1.0, res.get(4))
  }

  @Test
  def testReadNodeWithDate(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {born: date('2009-10-10')})")

    val list = df.select("born").collectAsList()
    val res = list.get(0).getDate(0)

    assertEquals(java.sql.Date.valueOf("2009-10-10"), res)
  }

  @Test
  def testReadNodeWithDuration(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {range: duration({days: 14, hours:16, minutes: 12})})")

    val list = df.select("range").collectAsList()
    val res = list.get(0).getAs[GenericRowWithSchema](0)

    assertEquals("duration", res(0))
    assertEquals(0L, res(1))
    assertEquals(14L, res(2))
    assertEquals(58320L, res(3))
    assertEquals(0, res(4))
    assertEquals("P0M14DT58320S", res(5))
  }

  @Test
  def testReadNodeWithStringArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {names: ['John', 'Doe']})")

    val res = df.select("names").collectAsList().get(0).getAs[Seq[String]](0)

    assertEquals("John", res.head)
    assertEquals("Doe", res(1))
  }

  @Test
  def testReadNodeWithLongArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {ages: [22, 23]})")

    val res = df.select("ages").collectAsList().get(0).getAs[Seq[Long]](0)

    assertEquals(22, res.head)
    assertEquals(23, res(1))
  }

  @Test
  def testReadNodeWithDoubleArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {scores: [22.33, 44.55]})")

    val res = df.select("scores").collectAsList().get(0).getAs[Seq[Double]](0)

    assertEquals(22.33, res.head, 0)
    assertEquals(44.55, res(1), 0)
  }

  @Test
  def testReadNodeWithLocalTimeArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {someTimes: [localtime({hour:12}), localtime({hour:1, minute: 3})]})")

    val res = df.select("someTimes").collectAsList().get(0).getAs[Seq[GenericRowWithSchema]](0)

    assertEquals("local-time", res.head.get(0))
    assertEquals("12:00:00", res.head.get(1))
    assertEquals("local-time", res(1).get(0))
    assertEquals("01:03:00", res(1).get(1))
  }

  @Test
  def testReadNodeWithBooleanArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {bools: [true, false]})")

    val res = df.select("bools").collectAsList().get(0).getAs[Seq[Boolean]](0)

    assertEquals(true, res.head)
    assertEquals(false, res(1))
  }

  @Test
  def testReadNodeWithPointArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {locations: [point({x: 11, y: 33.111}), point({x: 22, y: 44.222})]})")

    val res = df.select("locations").collectAsList().get(0).getAs[Seq[GenericRowWithSchema]](0)

    assertEquals("point-2d", res.head.get(0))
    assertEquals(7203, res.head.get(1))
    assertEquals(11.0, res.head.get(2))
    assertEquals(33.111, res.head.get(3))

    assertEquals("point-2d", res(1).get(0))
    assertEquals(7203, res(1).get(1))
    assertEquals(22.0, res(1).get(2))
    assertEquals(44.222, res(1).get(3))
  }

  @Test
  def testReadNodeWithGeoPointArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {locations: [point({longitude: 11, latitude: 33.111}), point({longitude: 22, latitude: 44.222})]})")

    val res = df.select("locations").collectAsList().get(0).getAs[Seq[GenericRowWithSchema]](0)

    assertEquals("point-2d", res.head.get(0))
    assertEquals(4326, res.head.get(1))
    assertEquals(11.0, res.head.get(2))
    assertEquals(33.111, res.head.get(3))

    assertEquals("point-2d", res(1).get(0))
    assertEquals(4326, res(1).get(1))
    assertEquals(22.0, res(1).get(2))
    assertEquals(44.222, res(1).get(3))
  }

  @Test
  def testReadNodeWithPoint3DArray(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {locations: [point({x: 11, y: 33.111, z: 12}), point({x: 22, y: 44.222, z: 99.1})]})")

    val res = df.select("locations").collectAsList().get(0).getAs[Seq[GenericRowWithSchema]](0)

    assertEquals("point-3d", res.head.get(0))
    assertEquals(9157, res.head.get(1))
    assertEquals(11.0, res.head.get(2))
    assertEquals(33.111, res.head.get(3))
    assertEquals(12.0, res.head.get(4))

    assertEquals("point-3d", res(1).get(0))
    assertEquals(9157, res(1).get(1))
    assertEquals(22.0, res(1).get(2))
    assertEquals(44.222, res(1).get(3))
    assertEquals(99.1, res(1).get(4))
  }

  @Test
  def testReadNodeWithArrayDate(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {dates: [date('2009-10-10'), date('2009-10-11')]})")

    val res = df.select("dates").collectAsList().get(0).getAs[Seq[java.sql.Date]](0)

    assertEquals(java.sql.Date.valueOf("2009-10-10"), res.head)
    assertEquals(java.sql.Date.valueOf("2009-10-11"), res(1))
  }

  @Test
  def testReadNodeWithArrayZonedDateTime(): Unit = {
    val datetime1 = "2015-06-24T12:50:35.556+01:00"
    val datetime2 = "2015-06-23T12:50:35.556+01:00"
    val df: DataFrame = initTest(s"""
     CREATE (p:Person {aTime: [
      datetime('$datetime1'),
      datetime('$datetime2')
     ]})
     """)

    val result = df.select("aTime").collectAsList().get(0).getAs[Seq[Timestamp]](0)

    assertEquals(Timestamp.from(OffsetDateTime.parse(datetime1).toInstant), result.head)
    assertEquals(Timestamp.from(OffsetDateTime.parse(datetime2).toInstant), result(1))
  }

  @Test
  def testReadNodeWithArrayDurations(): Unit = {
    val df: DataFrame = initTest(s"CREATE (p:Person {durations: [duration({months: 0.75}), duration({weeks: 2.5})]})")

    val res = df.select("durations").collectAsList().get(0).getAs[Seq[GenericRowWithSchema]](0)

    assertEquals("duration", res.head.get(0))
    assertEquals(0L, res.head.get(1))
    assertEquals(22L, res.head.get(2))
    assertEquals(71509L, res.head.get(3))
    assertEquals(500000000, res.head.get(4))
    assertEquals("P0M22DT71509.500000000S", res.head.get(5))

    assertEquals("duration", res(1).get(0))
    assertEquals(0L, res(1).get(1))
    assertEquals(17L, res(1).get(2))
    assertEquals(43200L, res(1).get(3))
    assertEquals(0, res(1).get(4))
    assertEquals("P0M17DT43200S", res(1).get(5))
  }

  @Test
  def testReadNodeRepartition(): Unit = {
    val fixtureQuery: String =
      """UNWIND range(1,100) as id
        |CREATE (p:Person {id:id,ids:[id,id]}) WITH collect(p) as people
        |UNWIND people as p1
        |UNWIND range(1,10) as friend
        |WITH p1, people[(p1.id + friend) % size(people)] as p2
        |CREATE (p1)-[:KNOWS]->(p2)
        |RETURN *
    """.stripMargin

    val df: DataFrame = initTest(fixtureQuery)
    val repartitionedDf = df.repartition(10)

    df.printSchema()

    assertEquals(10, repartitionedDf.rdd.getNumPartitions)
    val numNode = repartitionedDf.collect().length
    assertEquals(100, numNode)
  }

  @Test
  def testMultiDbJoin(): Unit = {
    SparkConnectorScalaSuiteIT.driver.session(SessionConfig.forDatabase("db1"))
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(
            """
      CREATE (p1:Person:Customer {name: 'John Doe'}),
       (p2:Person:Customer {name: 'Mark Brown'}),
       (p3:Person:Customer {name: 'Cindy White'})
      """).consume()
        })

    SparkConnectorScalaSuiteIT.driver.session(SessionConfig.forDatabase("db2"))
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(
            """
      CREATE (p1:Person:Employee {name: 'Jane Doe'}),
       (p2:Person:Employee {name: 'John Doe'})
      """).consume()
        })

    val df1 = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("database", "db1")
      .option("labels", "Person")
      .load()

    val df2 = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("database", "db2")
      .option("labels", "Person")
      .load()

    assertEquals(3, df1.count())
    assertEquals(2, df2.count())

    val dfJoin = df1.join(df2, df1("name") === df2("name"))
    assertEquals(1, dfJoin.count())
  }

  @Test
  def testRelationshipsFlatten(): Unit = {
    val total = 100
    val fixtureQuery: String =
      s"""UNWIND range(1, $total) as id
        |CREATE (pr:Product {id: id * rand(), name: 'Product ' + id})
        |CREATE (pe:Person {id: id, fullName: 'Person ' + id})
        |CREATE (pe)-[:BOUGHT{when: rand(), quantity: rand() * 1000}]->(pr)
        |RETURN *
    """.stripMargin

    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(fixtureQuery).consume()
        })

    val df: DataFrame = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "BOUGHT")
      .option("relationship.nodes.map", "false")
      .option("relationship.source.labels", ":Person")
      .option("relationship.target.labels", ":Product")
      .load()

    val count = df.collectAsList()
      .asScala
      .filter(row =>row.getAs[Long]("<rel.id>") != null
        && row.getAs[String]("<rel.type>") != null
        && row.getAs[Long]("rel.when") != null
        && row.getAs[Long]("rel.quantity") != null
        && row.getAs[Long]("<source.id>") != null
        && row.getAs[Long]("source.id") != null
        && !row.getAs[List[String]]("<source.labels>").isEmpty
        && row.getAs[String]("source.fullName") != null
        && row.getAs[Long]("<target.id>") != null
        && row.getAs[Long]("target.id") != null
        && !row.getAs[List[String]]("<target.labels>").isEmpty
        && row.getAs[String]("target.name") != null)
      .size
    assertEquals(total, count)
  }

  @Test
  def testRelationshipsMap(): Unit = {
    val total = 100
    val fixtureQuery: String =
      s"""UNWIND range(1, $total) as id
         |CREATE (pr:Product {id: id * rand(), name: 'Product ' + id})
         |CREATE (pe:Person {id: id, fullName: 'Person ' + id})
         |CREATE (pe)-[:BOUGHT{when: rand(), quantity: rand() * 1000}]->(pr)
         |RETURN *
    """.stripMargin

    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(fixtureQuery).consume()
        })

    val df: DataFrame = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("relationship", "BOUGHT")
      .option("relationship.source.labels", ":Person")
      .option("relationship.target.labels", ":Product")
      .load()

    val rows = df.collectAsList().asScala
    val count = rows
      .filter(row =>row.getAs[Long]("<rel.id>") != null
        && row.getAs[String]("<rel.type>") != null
        && row.getAs[Long]("rel.when") != null
        && row.getAs[Long]("rel.quantity") != null
        && row.getAs[Map[String, String]]("<source>") != null
        && row.getAs[Map[String, String]]("<target>") != null)
      .size
    assertEquals(total, count)

    val countSourceMap = rows.map(row => row.getAs[Map[String, String]]("<source>"))
      .filter(row => row.keys == Set("id", "fullName", "<id>", "<labels>"))
      .size
    assertEquals(total, countSourceMap)
    val countTargetMap = rows.map(row => row.getAs[Map[String, String]]("<target>"))
      .filter(row => row.keys == Set("id", "name", "<id>", "<labels>"))
      .size
    assertEquals(total, countTargetMap)
  }

  @Test
  def testQueries(): Unit = {
    val dfMap = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "RETURN {a: 1, b: '3'} AS map")
      .load()
    val map = dfMap.collect()(0).getAs[Map[String, String]]("map")
    val expectedMap = Map("a" -> "1", "b" -> "3")
    assertEquals(expectedMap, map)

    val dfArrayMap = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "RETURN [{a: 1, b: '3'}, {a: 'foo'}] AS listMap")
      .load()
    val listMap = dfArrayMap.collect()(0).getAs[Seq[_]]("listMap").toList
    val expectedListMap = Seq(Map("a" -> "1", "b" -> "3"), Map("a" -> "foo"))
    assertEquals(expectedListMap, listMap)

    val dfArray = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "RETURN [1, 'foo'] AS list")
      .load()
    val list = dfArray.collect()(0).getAs[Seq[_]]("list")
    val expectedList = Seq("1", "foo")
    assertEquals(expectedList, list)
  }

  @Test
  def testComplexQuery(): Unit = {
    val total = 100
    val fixtureQuery: String =
      s"""UNWIND range(1, $total) as id
         |CREATE (pr:Product {id: id * rand(), name: 'Product ' + id})
         |CREATE (pe:Person {id: id, fullName: 'Person ' + id})
         |CREATE (pe)-[:BOUGHT{when: rand(), quantity: rand() * 1000}]->(pr)
         |RETURN *
    """.stripMargin

    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(fixtureQuery).consume()
        })

    val df: DataFrame = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query", "MATCH (n:Person) WITH n LIMIT 2 RETURN collect(n) AS nodes")
      .load()

    val data = df.collect()
    val count = data.flatMap(row => row.getAs[Seq[Row]]("nodes"))
      .filter(row => row.getAs[Long]("<id>") != null
        && !row.getAs[Seq[String]]("<labels>").isEmpty
        && !row.getAs[String]("fullName").isEmpty
        && row.getAs[Long]("id") != null)
      .size
    assertEquals(2, count)

    val dfString: DataFrame = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query",
        """MATCH (p:Person)-[b:BOUGHT]->(pr:Product)
          |RETURN id(p) AS personId, id(pr) AS productId, {quantity: b.quantity, when: b.when} AS map""".stripMargin)
      .option("schema.strategy", "string")
      .load()

    val dataString = dfString.collect()
    val countString = dataString
      .filter(row => !row.getAs[String]("personId").isEmpty
        && !row.getAs[String]("productId").isEmpty
        && !row.getAs[String]("map").isEmpty)
      .size
    assertEquals(100, countString)

    val dfRel: DataFrame = ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("query",
        """MATCH (p:Person)-[b:BOUGHT]->(pr:Product)
          |RETURN b AS rel""".stripMargin)
      .load()
    val dataRel = dfRel.collect()
    val countRel = dataRel
      .map(_.getAs[Row]("rel"))
      .filter(row =>
        row.getAs[Long]("<rel.id>") != null
        && !row.getAs[String]("<rel.type>").isEmpty
        && row.getAs[Long]("<source.id>") != null
        && row.getAs[Long]("<target.id>") != null
        && row.getAs[Double]("when") != null
        && row.getAs[Double]("quantity") != null
      )
      .size
    assertEquals(100, countRel)
  }

  @Test(expected = classOf[IllegalArgumentException])
  def testThrowsExceptionOnWriteQuery(): Unit = {
    try {
      ss.read.format(classOf[DataSource].getName)
        .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
        .option("query", "CREATE (p:Person)")
        .load()
    } catch {
      case iae: IllegalArgumentException => {
        assertTrue(iae.getMessage.endsWith("Please provide a valid READ query"))
        throw iae
      }
      case _ => fail(s"should be thrown a ${classOf[IllegalArgumentException].getName}")
    }
  }

  private def initTest(query: String): DataFrame = {
    SparkConnectorScalaSuiteIT.session()
      .writeTransaction(
        new TransactionWork[ResultSummary] {
          override def execute(tx: Transaction): ResultSummary = tx.run(query).consume()
        })

    ss.read.format(classOf[DataSource].getName)
      .option("url", SparkConnectorScalaSuiteIT.server.getBoltUrl)
      .option("labels", "Person")
      .load()
  }
}