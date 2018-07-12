package cn.i4.iql.adaptor

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import cn.i4.iql.IQLSQLExecListener
import cn.i4.iql.antlr.IQLParser._
import cn.i4.iql.utils.PropsUtils
import org.apache.spark.sql._
import org.apache.spark.sql.streaming.{DataStreamWriter, Trigger}

//save new_tr as json.`/tmp/todd
class SaveAdaptor(scriptSQLExecListener: IQLSQLExecListener) extends DslAdaptor {
  override def parse(ctx: SqlContext): Unit = {
    var oldDF: DataFrame = null
    var mode = SaveMode.ErrorIfExists
    var final_path = ""
    var format = ""
    var option = Map[String, String]()
    var tableName = ""
    var partitionByCol = Array[String]()
    var numPartition:Int = 1

    (0 until ctx.getChildCount).foreach { tokenIndex =>
      ctx.getChild(tokenIndex) match {
        case s: FormatContext =>
          format = s.getText
          format match {
            case "hive" =>
            case _ =>
              format = s.getText
          }
        case s: PathContext =>
          format match {
            case "jdbc" | "hive" | "kafka8" | "kafka9" | "hbase" | "redis" | "es" | "json" =>
              final_path = cleanStr(s.getText)
            case _ =>
          }
        case s: TableNameContext =>
          tableName = s.getText
          oldDF = scriptSQLExecListener.sparkSession.table(s.getText)
        case _: OverwriteContext =>
          mode = SaveMode.Overwrite
        case _: AppendContext =>
          mode = SaveMode.Append
        case _: ErrorIfExistsContext =>
          mode = SaveMode.ErrorIfExists
        case _: IgnoreContext =>
          mode = SaveMode.Ignore
        case _: UpdateContext =>
          option += ("savemode" -> "update")
        case s: ColContext =>
          partitionByCol = cleanStr(s.getText).split(",")
        case s: ExpressionContext =>
          option += (cleanStr(s.identifier().getText) -> cleanStr(s.STRING().getText))
        case s: BooleanExpressionContext =>
          option += (cleanStr(s.expression().identifier().getText) -> cleanStr(s.expression().STRING().getText))
        case s: NumPartitionContext =>
          numPartition = s.getText.toInt
        case _ =>
      }
    }
    if(scriptSQLExecListener.env().contains("stream")){
      new StreamSaveAdaptor(scriptSQLExecListener,option,oldDF,final_path,tableName,format,mode,partitionByCol,numPartition).parse
    }else {
      new BatchSaveAdaptor(scriptSQLExecListener,option,oldDF,final_path,tableName,format,mode,partitionByCol,numPartition).parse
    }
  }
}


class BatchSaveAdaptor(val scriptSQLExecListener: IQLSQLExecListener,
                       var option: Map[String, String],
                       var oldDF: DataFrame,
                       var final_path: String,
                       var tableName: String,
                       var format: String,
                       var mode: SaveMode,
                       var partitionByCol: Array[String],
                       val numPartition:Int
                      ) {
  def parse = {
    var writer = oldDF.write
    writer = writer.format(format).mode(mode).partitionBy(partitionByCol: _*).options(option)
    format match {
      case "json" | "csv" | "orc" | "parquet" | "text" =>
        val tmpPath = "/tmp/iql/tmp/"+System.currentTimeMillis()
        writer.option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ").option("header", "true").format(format).save(tmpPath)//写
        scriptSQLExecListener.sparkSession.read.option("header", "true").option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ").format(format).load(tmpPath).coalesce(numPartition)//读
          .write.mode(mode).partitionBy(partitionByCol: _*).options(option).option("timestampFormat", "yyyy/MM/dd HH:mm:ss ZZ")
          .format(format).save(final_path)//写
      case "es" =>
        writer.save(final_path)
      case "hive" =>
        oldDF.coalesce(numPartition).write.format(option.getOrElse("file_format", "parquet")).mode(mode).options(option)
          .insertInto(final_path)
      //        writer.saveAsTable(final_path)

      case "kafka8" | "kafka9" =>
        writer.option("topics", final_path).format("com.hortonworks.spark.sql.kafka08").save()
      case "hbase" =>
        writer.option("hbase.table.name", final_path).format("org.apache.spark.sql.execution.datasources.hbase").save()
      case "redis" =>
        writer.option("outputTableName", final_path).format("org.apache.spark.sql.execution.datasources.redis").save()
      case "jdbc" =>
        writer
          .option("driver",option.getOrElse("driver",PropsUtils.get("jdbc.driver")))
          .option("url",option.getOrElse("url",PropsUtils.get("jdbc.url")))
          .option("dbtable",final_path)
          .save()
      case _ =>
        writer.save(final_path)
    }
  }
}


class StreamSaveAdaptor(val scriptSQLExecListener: IQLSQLExecListener,
                        var option: Map[String, String],
                        var oldDF: DataFrame,
                        var final_path: String,
                        var tableName: String,
                        var format: String,
                        var mode: SaveMode,
                        var partitionByCol: Array[String],
                        val numPartition:Int
                       ) {
  def parse:Unit = {
    var writer: DataStreamWriter[Row] = oldDF.writeStream

    require(option.contains("checkpointLocation"), "checkpointLocation is required")
    require(option.contains("duration"), "duration is required")
    require(option.contains("mode"), "mode is required")

    writer = writer.format(option.getOrElse("implClass", format)).outputMode(option("mode")).
      partitionBy(partitionByCol: _*).
      options(option - "mode" - "duration")

    val dbtable = if (option.contains("dbtable")) option("dbtable") else final_path

    if (dbtable != null && dbtable != "-") {
      writer.option("path", dbtable)
    }
    option.get("streamName") match {
      case Some(name) => writer.queryName(name)
      case None =>
    }
    val query = writer.trigger(Trigger.ProcessingTime(option("duration").toInt, TimeUnit.SECONDS)).start()
    scriptSQLExecListener.iqlSession.streamJob.put(scriptSQLExecListener.iqlSession.engineInfo + "_" + query.name + "_" + query.id,query)
  }
}