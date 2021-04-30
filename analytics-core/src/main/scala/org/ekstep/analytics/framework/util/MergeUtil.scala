package org.ekstep.analytics.framework.util

import org.apache.commons.io.FilenameUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.sql.functions.{col, unix_timestamp, _}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.ekstep.analytics.framework.Level.INFO
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.DatasetUtil.extensions
import org.ekstep.analytics.framework.{FrameworkContext, MergeConfig, StorageConfig}
import org.joda.time
import org.joda.time.format.DateTimeFormat

case class MergeResult(updatedReportDF: DataFrame, oldReportDF: DataFrame, storageConfig: StorageConfig)

case class ReportJson(data: Map[String, Nothing], keys: List[String], tableData: List[List[String]])

class MergeUtil {
  implicit val className = "org.ekstep.analytics.framework.util.MergeUtil"
  val druidDateFormat =AppConf.getConfig("druid.report.date.format")
  def mergeFile(mergeConfig: MergeConfig)(implicit sc: SparkContext, fc: FrameworkContext): Unit = {
    implicit val sqlContext = new SQLContext(sc)
    var reportSchema: StructType = null

    mergeConfig.merge.files.foreach(filePaths => {
      val path = new Path(filePaths("reportPath"))
      val postContainer= mergeConfig.postContainer.getOrElse(AppConf.getConfig("druid.report.default.container"))
      val storageType = mergeConfig.`type`.getOrElse(AppConf.getConfig("druid.report.default.storage"))
      val mergeResult = storageType.toLowerCase() match {
        case "local" =>
          val deltaDF = sqlContext.read.options(Map("header" -> "true")).csv(filePaths("deltaPath"))
          val reportDF = if (new java.io.File(filePaths("reportPath")).exists)
            sqlContext.read.options(Map("header" -> "true")).csv(filePaths("reportPath"))
          else deltaDF
          MergeResult(mergeReport(deltaDF, reportDF, mergeConfig, mergeConfig.merge.dims), reportDF,
            StorageConfig(storageType, null, FilenameUtils.getFullPathNoEndSeparator(filePaths("reportPath"))))
        case "azure" =>
          val deltaDF = fetchBlobFile(filePaths("deltaPath"),
            mergeConfig.deltaFileAccess.getOrElse(true), mergeConfig.container)
          val reportFile = fetchBlobFile(filePaths("reportPath"), mergeConfig.reportFileAccess.getOrElse(true),
            postContainer)
          val reportDF = if (null == reportFile) {
            sqlContext.createDataFrame(sc.emptyRDD[Row], if (null == reportSchema) {
              deltaDF.schema
            } else reportSchema
            )
          }
          else {
            reportSchema = reportFile.schema
            reportFile
          }
          MergeResult(mergeReport(deltaDF, reportDF, mergeConfig, mergeConfig.merge.dims), reportDF,
            StorageConfig(storageType, postContainer, path.getParent.getName))

        case _ =>
          throw new Exception("Merge type unknown")
      }
      // Rename old file by appending date and store it
      try {
        mergeResult.oldReportDF.saveToBlobStore(mergeResult.storageConfig, "csv",
          String.format("%s-%s", FilenameUtils.removeExtension(path.getName), new time.DateTime().toString(druidDateFormat)),
          Option(Map("header" -> "true", "mode" -> "overwrite")), None)
        convertReportToJsonFormat(sqlContext, mergeResult.oldReportDF).saveToBlobStore(mergeResult.storageConfig, "json",
          String.format("%s-%s", FilenameUtils.removeExtension(path.getName), new time.DateTime().toString(druidDateFormat)),
          Option(Map("header" -> "true", "mode" -> "overwrite")), None)

        // Append new data to report file
        mergeResult.updatedReportDF.saveToBlobStore(mergeResult.storageConfig, "csv", FilenameUtils.removeExtension(path.getName),
          Option(Map("header" -> "true", "mode" -> "overwrite")), None)
        convertReportToJsonFormat(sqlContext, mergeResult.updatedReportDF).saveToBlobStore(mergeResult.storageConfig, "json", FilenameUtils.removeExtension(path.getName),
          Option(Map("header" -> "true", "mode" -> "overwrite")), None)
      }catch {
        case ex : Exception =>{
          Console.println("Merge failed while saving to blob", ex.printStackTrace)
          JobLogger.log(ex.getMessage, None, INFO)
        }
      }
    })
  }


  def mergeReport(delta: DataFrame, reportDF: DataFrame, mergeConfig: MergeConfig, dims: List[String]): DataFrame = {
    val rollupColOption = """\|\|""".r.split(mergeConfig.rollupCol.getOrElse("Date"))
    val rollupCol = rollupColOption.apply(0)

    if (mergeConfig.rollup > 0) {
      val rollupFormat = mergeConfig.rollupFormat.getOrElse({
        if (rollupColOption.length > 1) rollupColOption.apply(1).replaceAll("%Y", "yyyy").replaceAll("%m", "MM")
          .replaceAll("%d", "dd") else  druidDateFormat
    })
      val reportDfColumns = reportDF.columns
      val deltaDF = delta.withColumn(rollupCol, date_format(col(rollupCol), rollupFormat)).dropDuplicates()
        .drop(delta.columns.filter(p => !reportDfColumns.contains(p)): _*)
        .select(reportDfColumns.head, reportDfColumns.tail: _*)
      val filteredDf = mergeConfig.rollupCol.map { rollupCol =>
        val rollupColumn = """\|\|""".r.split(rollupCol).apply(0)
        reportDF.as("report").join(deltaDF.as("delta"),
          col("report." + rollupColumn) === col("delta." + rollupColumn), "inner")
          .select("report.*")
      }.getOrElse({
        reportDF.as("report").join(deltaDF.as("delta"), dims, "inner")
          .select("report.*")
      })

      val finalDf = reportDF.except(filteredDf).union(deltaDF)
      rollupReport(finalDf, mergeConfig, rollupCol, rollupFormat).orderBy(unix_timestamp(col(rollupCol), rollupFormat))
    }
    else
      delta
  }

  def rollupReport(reportDF: DataFrame, mergeConfig: MergeConfig, rollupCol: String, rollupFormat: String): DataFrame = {
    val subtract = (x: Int, y: Int) => x - y
    val rollupRange = subtract(mergeConfig.rollupRange.get, 1)
    val maxDate = reportDF.agg(max(unix_timestamp(col(rollupCol)
      , rollupFormat)) as "Max").collect().apply(0).getAs[Long]("Max")
    val convert = (x: Long) => x * 1000L
    val endDate = new time.DateTime(convert(maxDate))
    var endYear = endDate.year().get()
    var endMonth = endDate.monthOfYear().get()
    val startDate = mergeConfig.rollupAge.get match {
      case "ACADEMIC_YEAR" =>
        if (endMonth <= 5)
          endYear = subtract(subtract(endYear, 1), rollupRange)
        else
          endYear = subtract(endYear, rollupRange)
        new time.DateTime(endYear, 6, 1, 0, 0, 0)
      case "GEN_YEAR" =>
        endYear = subtract(endYear, rollupRange)
        new time.DateTime(endYear, 1, 1, 0, 0, 0)
      case "MONTH" =>
        endMonth = subtract(endMonth, rollupRange)
        endYear = if (endMonth < 1) endYear + ((if (endMonth != 0) endMonth else -1) / 12).floor.toInt else endYear
        endMonth = if (endMonth < 1) endMonth + 12 else endMonth
        new time.DateTime(endYear, endMonth, 1, 0, 0, 0)
      case "WEEK" =>
        endDate.withDayOfWeek(1).minusWeeks(rollupRange)
      case "DAY" =>
        endDate.minusDays(rollupRange.toInt)
      case _ =>
        new time.DateTime(1970, 1, 1, 0, 0, 0)
    }
    reportDF.filter(p => DateTimeFormat.forPattern(rollupFormat)
      .parseDateTime(p.getAs[String]("Date"))
      .getMillis >= startDate.asInstanceOf[time.DateTime].getMillis)
  }

  def fetchBlobFile(filePath: String, isPrivate: Boolean, container: String)(implicit sqlContext: SQLContext, fc: FrameworkContext): DataFrame = {

    val storageService =
      if (isPrivate)
        fc.getStorageService("azure", "azure_storage_key", "azure_storage_secret")
      else
        fc.getStorageService("azure", "report_storage_key", "report_storage_secret")
    val keys = storageService.searchObjects(container, filePath)
    val reportPaths = storageService.getPaths(container, keys).toArray.mkString(",")
    if (reportPaths.length > 0)
      sqlContext.read.options(Map("header" -> "true")).csv(reportPaths)
    else null
  }

  def convertReportToJsonFormat(sqlContext: SQLContext, df: DataFrame): DataFrame = {
    import sqlContext.implicits._
    val cols = df.columns
    df.map(f => (f.getValuesMap[String](cols).keys.toSeq, f.getValuesMap[String](cols).values.toSeq, f.getValuesMap[String](cols)))
      .groupBy("_1").agg(collect_list("_2").alias("tableData"),
      collect_list("_3").alias("data")).withColumnRenamed("_1", "keys")
  }
}