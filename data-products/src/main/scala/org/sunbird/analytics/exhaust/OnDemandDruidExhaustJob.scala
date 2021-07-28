package org.sunbird.analytics.exhaust

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Encoders, SQLContext, SparkSession}
import org.apache.spark.sql.functions.{col, lit, when}
import org.ekstep.analytics.framework.Level.INFO
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.{CommonUtil, JSONUtils, JobLogger, MergeUtil, RestUtil}
import org.ekstep.analytics.framework.{DruidQueryModel, FrameworkContext, JobConfig, JobContext, StorageConfig, _}
import org.ekstep.analytics.model.{OutputConfig, QueryDateRange, ReportMergeConfig}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.{List, Map}
import scala.collection.mutable
import scala.collection.mutable._
import org.ekstep.analytics.framework.exception.DruidConfigException
import org.ekstep.analytics.framework.fetcher.DruidDataFetcher
import org.ekstep.analytics.framework.util.DatasetUtil.extensions
import org.ekstep.analytics.model.DruidQueryProcessingModel.{getDateRange, getReportDF}
import org.apache.hadoop.conf.Configuration

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier


case class RequestBody(`type`: String,`params`: Map[String,AnyRef])
case class DruidQuery(queryType: String, dataSource: String, intervals: String, granularity: Option[String], aggregations: Option[List[Aggregation]],
                      dimensions: Option[List[DruidDimension]], filters: Option[List[mutable.Map[String,AnyRef]]], having: Option[DruidHavingFilter],
                      postAggregation: Option[List[PostAggregation]], columns: Option[List[String]], sqlDimensions: Option[List[DruidSQLDimension]],
                      threshold: Option[Long], metric: Option[String], descending: Option[String], intervalSlider: Int)
case class Metrics(metric : String, label : String, druidQuery : DruidQuery)
case class ReportConfig(id: String, queryType: String, dateRange: QueryDateRange, metrics: List[Metrics], labels: LinkedHashMap[String, String],
                        output: List[mutable.Map[String,AnyRef]], storageKey: Option[String] = Option(AppConf.getConfig("storage.key.config")),
                        storageSecret: Option[String] = Option(AppConf.getConfig("storage.secret.config")))
<<<<<<< HEAD
case class ReportConfigImmutable(id: String, queryType: String, dateRange: QueryDateRange, metrics: List[Metrics], labels: LinkedHashMap[String, String],
                                 output: List[OutputConfig],mergeConfig: Option[ReportMergeConfig] = None,
                                 storageKey: Option[String] = Option(AppConf.getConfig("storage.key.config")),
=======
case class ReportConfigImmutable(id: String, queryType: String, dateRange: QueryDateRange, metrics: List[Metrics], labels: LinkedHashMap[String, String], output: List[OutputConfig],
                                 mergeConfig: Option[ReportMergeConfig] = None, storageKey: Option[String] = Option(AppConf.getConfig("storage.key.config")),
>>>>>>> 8adf5f30457c73da9a863bd2f8412c260e576e94
                                 storageSecret: Option[String] = Option(AppConf.getConfig("storage.secret.config")))
case class OnDemandDruidResponse(file: List[String], status: String, statusMsg: String, execTime: Long)
case class FinalMetrics(totalRequests: Option[Int], failedRequests: Option[Int], successRequests: Option[Int])

object OnDemandDruidExhaustJob extends optional.Application with BaseReportsJob with Serializable with OnDemandExhaustJob{
  override def getClassName() : String = "org.sunbird.analytics.exhaust.OnDemandDruidExhaustJob"
  val jobName: String = "OnDemandDruidExhaustJob"
  def name(): String = "OnDemandDruidExhaustJob"
  // $COVERAGE-OFF$ Disabling scoverage for main and execute method
  def main(config: String)(implicit sc: Option[SparkContext] = None, fc: Option[FrameworkContext] = None) {
    JobLogger.init("OnDemandDruidExhaustJob")
    JobLogger.start("OnDemandDruidExhaustJob Started executing", Option(Map("config" -> config, "model" -> name)))
    implicit val jobConfig = JSONUtils.deserialize[JobConfig](config)
    implicit val spark: SparkSession = openSparkSession(jobConfig)
    implicit val sc: SparkContext = spark.sparkContext
    implicit val sqlContext = new SQLContext(sc)
    JobContext.parallelization = CommonUtil.getParallelization(jobConfig)

    implicit val frameworkContext: FrameworkContext = getReportingFrameworkContext()
    implicit val conf = spark.sparkContext.hadoopConfiguration
    execute()
    // $COVERAGE-ON$ Disabling scoverage for main and execute method
  }

  def validateEncryptionRequest(request: JobRequest): Boolean = {
    if (request.encryption_key.nonEmpty) true else false;
  }

  def validateRequest(request: JobRequest): Boolean = {
    if (request.request_data != null) true else false
  }

  def getRequests(jobId :String)(implicit spark: SparkSession, fc: FrameworkContext): Array[JobRequest] = {
    val jobStatus = List("SUBMITTED", "FAILED")
    val encoder = Encoders.product[JobRequest]
    val reportConfigsDf = spark.read.jdbc(url, requestsTable, connProperties)
      .where(col("job_id") === jobId && col("iteration") < 3).filter(col("status").isin(jobStatus: _*));
    val requests = reportConfigsDf.withColumn("status", lit("PROCESSING")).as[JobRequest](encoder).collect()
    requests
  }

  def markRequestAsProcessing(request: JobRequest):Boolean = {
    request.status = "PROCESSING";
    updateStatus(request);
  }

  def getStringProperty(config: Map[String, AnyRef], key: String, defaultValue: String) : String = {
    config.getOrElse(key, defaultValue).asInstanceOf[String]
  }
  def druidAlgorithm(reportConfig: ReportConfig)(implicit spark: SparkSession, sqlContext: SQLContext, fc: FrameworkContext, sc:SparkContext, config: JobConfig): RDD[DruidOutput]  ={
    val strConfig = JSONUtils.serialize(reportConfig)
    val reportConfigs = JSONUtils.deserialize[ReportConfig](strConfig)

    val queryDims = reportConfigs.metrics.map { f =>
      f.druidQuery.dimensions.getOrElse(List()).map(f => f.aliasName.getOrElse(f.fieldName))
    }.distinct
    if (queryDims.length > 1) throw new DruidConfigException("Query dimensions are not matching")

    val interval = reportConfigs.dateRange
    val granularity = interval.granularity
    val reportInterval = if (interval.staticInterval.nonEmpty) {
      interval.staticInterval.get
    } else if (interval.interval.nonEmpty) {
      interval.interval.get
    } else {
      throw new DruidConfigException("Both staticInterval and interval cannot be missing. Either of them should be specified")
    }
    val metrics = reportConfigs.metrics.map { f =>

      val queryInterval = if (interval.staticInterval.isEmpty && interval.interval.nonEmpty) {
        val dateRange = interval.interval.get
        getDateRange(dateRange, interval.intervalSlider,f.druidQuery.dataSource)
      } else
        reportInterval

      val queryConfig = if (granularity.nonEmpty)
<<<<<<< HEAD
        JSONUtils.deserialize[Map[String, AnyRef]](JSONUtils.serialize(f.druidQuery)) ++ Map("intervalSlider" -> interval.intervalSlider,
          "intervals" -> queryInterval, "granularity" -> granularity.get)
      else
        JSONUtils.deserialize[Map[String, AnyRef]](JSONUtils.serialize(f.druidQuery)) ++ Map("intervalSlider" -> interval.intervalSlider,
          "intervals" -> queryInterval)
=======
        JSONUtils.deserialize[Map[String, AnyRef]](JSONUtils.serialize(f.druidQuery)) ++ Map("intervalSlider" -> interval.intervalSlider, "intervals" -> queryInterval, "granularity" -> granularity.get)
      else
        JSONUtils.deserialize[Map[String, AnyRef]](JSONUtils.serialize(f.druidQuery)) ++ Map("intervalSlider" -> interval.intervalSlider, "intervals" -> queryInterval)
>>>>>>> 8adf5f30457c73da9a863bd2f8412c260e576e94
      val data = DruidDataFetcher.getDruidData(JSONUtils.deserialize[DruidQueryModel](JSONUtils.serialize(queryConfig)))
      data.map { x =>
        val dataMap = JSONUtils.deserialize[Map[String, AnyRef]](x)
        val key = dataMap.filter(m => (queryDims.flatten).contains(m._1)).values.map(f => f.toString).toList.sorted(Ordering.String.reverse).mkString(",")
        (key, dataMap)

      }
    }
    val finalResult = metrics.fold(sc.emptyRDD)(_ union _)
    finalResult.map { f =>
      DruidOutput(f._2)
    }
  }

  def druidPostProcess(data: RDD[DruidOutput],reportConfig: ReportConfigImmutable, storageConfig:StorageConfig)(implicit spark: SparkSession, sqlContext: SQLContext, fc: FrameworkContext, sc:SparkContext, config: JobConfig): OnDemandDruidResponse = {
    val labelsLookup = reportConfig.labels
    implicit val sqlContext = new SQLContext(sc)
    val dimFields = reportConfig.metrics.flatMap { m =>
      if (m.druidQuery.dimensions.nonEmpty) m.druidQuery.dimensions.get.map(f => f.aliasName.getOrElse(f.fieldName))
      else if(m.druidQuery.sqlDimensions.nonEmpty) m.druidQuery.sqlDimensions.get.map(f => f.fieldName)
      else List()
    }
    val dataCount = sc.longAccumulator("DruidReportCount")
    val filesMutable = scala.collection.mutable.MutableList[String]()
    reportConfig.output.foreach { f =>
      var df = getReportDF(RestUtil,JSONUtils.deserialize[OutputConfig](JSONUtils.serialize(f)),data,dataCount).na.fill(0).drop("__time")
      (df.columns).map(f1 =>{
        df = df.withColumn(f1,when((col(f1)==="unknown")||(col(f1)==="<NULL>"),"Null").otherwise(col(f1)))
      })
      if (dataCount.value > 0) {
        val metricFields = f.metrics
        val fieldsList = (dimFields ++ metricFields).distinct
        val dimsLabels = labelsLookup.filter(x => f.dims.contains(x._1)).values.toList
        val filteredDf = df.select(fieldsList.head, fieldsList.tail: _*)
        val renamedDf = filteredDf.select(filteredDf.columns.map(c => filteredDf.col(c).as(labelsLookup.getOrElse(c, c))): _*).na.fill("unknown")
        val reportFinalId = reportConfig.id + "/" + f.label.get
        val fileSavedToBlob = saveReport(renamedDf, JSONUtils.deserialize[Map[String,AnyRef]](JSONUtils.serialize(config.modelParams.get)) ++
          Map("dims" -> dimsLabels,"reportId" -> reportFinalId, "fileParameters" -> f.fileParameters, "format" -> f.`type`))
        fileSavedToBlob.foreach(y=> filesMutable += y)
        JobLogger.log(reportConfig.id + "Total Records :"+ dataCount.value , None, Level.INFO)
      }
      else {
        JobLogger.log("No data found from druid", None, Level.INFO)
      }
    }
    try {
      if(filesMutable.length > 0) {
        val files = List.empty ++ filesMutable
        OnDemandDruidResponse(files, "SUCCESS", "", System.currentTimeMillis())
      } else {
        OnDemandDruidResponse(List(), "FAILED", "No data found from druid", System.currentTimeMillis())
      }
    }
    catch {
      case ex: Exception => ex.printStackTrace();OnDemandDruidResponse(List(), "FAILED", ex.getMessage, 0);
    }
  }

  def saveReport(data: DataFrame, config: Map[String, AnyRef])(implicit sc: SparkContext,fc:FrameworkContext): List[String] = {
    val container =  getStringProperty(config, "container", "test-container")
    val storageConfig = StorageConfig(getStringProperty(config, "store", "local"),container, getStringProperty(config, "key",
      "/tmp/druid-reports"), config.get("accountKey").asInstanceOf[Option[String]]);
    val format = config.get("format").get.asInstanceOf[String]
    val reportId = config.get("reportId").get.asInstanceOf[String]
    val quoteColumns =config.get("quoteColumns").getOrElse(List()).asInstanceOf[List[String]]
    var duplicateDimsDf = data
    if(quoteColumns.nonEmpty) {
      import org.apache.spark.sql.functions.udf
      val quoteStr = udf((column: String) =>  "\'"+column+"\'")
      quoteColumns.map(column => {
        duplicateDimsDf = duplicateDimsDf.withColumn(column, quoteStr(col(column)))
      })
    }
    val deltaFiles = duplicateDimsDf.saveToBlobStore(storageConfig, format, reportId, Option(Map("header" -> "true")), None)
    deltaFiles
  }

  def processRequest(request: JobRequest, reportConfig:ReportConfig, storageConfig:StorageConfig)(implicit spark: SparkSession, fc: FrameworkContext, sqlContext:SQLContext, sc:SparkContext,config: JobConfig,conf: Configuration): JobRequest ={
    markRequestAsProcessing(request)
    val requestBody = JSONUtils.deserialize[RequestBody](request.request_data)
    val requestParamsBody = requestBody.`params`
    reportConfig.metrics.map(metric => {
      metric.druidQuery.filters.get.map(filt=>{
        (requestBody.`params`.keys).map(ke=> {
          if(filt.get("value").contains("$"+ke)) {
            val dynamicFilterValue = requestParamsBody.get(ke).get.toString
            filt.update("value", dynamicFilterValue)
          }
        })
      })
    })
    reportConfig.output.foreach({ot => {
      ot.update("label",((System.currentTimeMillis())+"_"+request.request_id).toString)
    } })
    val druidData : RDD[DruidOutput] = druidAlgorithm(reportConfig)
    val result = CommonUtil.time(druidPostProcess(druidData,JSONUtils.deserialize[ReportConfigImmutable](JSONUtils.serialize(reportConfig)),storageConfig))
    val response = result._2;
    val failedOnDemandDruidRes = response.status.equals("FAILED")
    if (failedOnDemandDruidRes) {
      markRequestAsFailed(request, response.statusMsg)
    } else {
      if(validateEncryptionRequest(request) == true){
        val storageConfig = getStorageConfig(config, response.file.head)
        request.download_urls = Option(response.file);
        request.execution_time = Option(result._1);
        processRequestEncryption(storageConfig,request)
        request.status = "SUCCESS";
        request.dt_job_completed = Option(System.currentTimeMillis)
      }
      else {
        request.status = "SUCCESS";
        request.download_urls = Option(response.file);
        request.execution_time = Option(result._1);
        request.dt_job_completed = Option(System.currentTimeMillis)
      }
    }
    request
  }
  def saveRequestAsync(request: JobRequest,jobId:String)(implicit conf: Configuration, fc: FrameworkContext): CompletableFuture[JobRequest] = {

    CompletableFuture.supplyAsync(new Supplier[JobRequest]() {
      override def get(): JobRequest = {
        val res = CommonUtil.time(updateRequest(request))
        JobLogger.log("Request is zipped", Some(Map("requestId" -> request.request_id, "timeTakenForZip" -> res._1)), INFO)
        request
      }
    })
  }
  def execute()(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig, sqlContext:SQLContext, sc:SparkContext, conf: Configuration): FinalMetrics = {
    val reportConfig = JSONUtils.deserialize[ReportConfig](JSONUtils.serialize(config.modelParams.get("reportConfig")))
    val jobId : String = reportConfig.id
    val requests = getRequests(jobId)
    val storageConfig = getStorageConfig(config, AppConf.getConfig("collection.exhaust.store.prefix"))
    val totalRequests = new AtomicInteger(requests.length)
    JobLogger.log("Total Requests are ", Some(Map("jobId" -> jobId, "totalRequests" -> requests.length)), INFO)
    val result = for (request <- requests) yield {
      val updRequest: JobRequest = {
        try {
          if (validateRequest(request)) {
            val res = processRequest(request, reportConfig, storageConfig)
            JobLogger.log("The Request is processed. Pending zipping", Some(Map("requestId" -> request.request_id, "timeTaken" -> res.execution_time,
              "remainingRequest" -> totalRequests.getAndDecrement())), INFO)
            res
          } else {
            JobLogger.log("Invalid Request", Some(Map("requestId" -> request.request_id, "remainingRequest" -> totalRequests.getAndDecrement())), INFO)
            markRequestAsFailed(request, "Invalid request")
          }
        } catch {
          case ex: Exception =>
            ex.printStackTrace()
            markRequestAsFailed(request, "Invalid request")
        }
      }
      saveRequestAsync(updRequest,jobId)(spark.sparkContext.hadoopConfiguration, fc)
    }
    CompletableFuture.allOf(result: _*) // Wait for all the async tasks to complete
    val completedResult = result.map(f => f.join()); // Get the completed job requests
<<<<<<< HEAD
    FinalMetrics(totalRequests = Some(requests.length), failedRequests = Some(completedResult.count(x => x.status.toUpperCase() == "FAILED")),
      successRequests = Some(completedResult.count(x => x.status.toUpperCase == "SUCCESS")));
=======
    FinalMetrics(totalRequests = Some(requests.length), failedRequests = Some(completedResult.count(x => x.status.toUpperCase() == "FAILED")), successRequests = Some(completedResult.count(x => x.status.toUpperCase == "SUCCESS")));
>>>>>>> 8adf5f30457c73da9a863bd2f8412c260e576e94
  }
}