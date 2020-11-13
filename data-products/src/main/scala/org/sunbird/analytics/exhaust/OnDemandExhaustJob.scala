package org.sunbird.analytics.exhaust

import java.io.File
import java.nio.file.Paths
import java.util.Properties

import org.apache.spark.sql.Encoders
import org.apache.spark.sql.SparkSession
import org.ekstep.analytics.framework.FrameworkContext
import org.ekstep.analytics.framework.StorageConfig
import org.ekstep.analytics.framework.conf.AppConf
import org.ekstep.analytics.framework.util.CommonUtil
import org.apache.spark.sql.functions._
import org.apache.commons.lang.StringUtils
import org.ekstep.analytics.framework.Level.INFO

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.spark.sql.SaveMode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import org.ekstep.analytics.framework.util.HadoopFileUtil
import java.util.concurrent.CompletableFuture
import org.apache.hadoop.conf.Configuration
import java.util.function.Supplier
import org.ekstep.analytics.framework.util.JobLogger

case class JobRequest(tag: String, request_id: String, job_id: String, var status: String, request_data: String, requested_by: String, requested_channel: String,
                      dt_job_submitted: Long, var download_urls: Option[List[String]], var dt_file_created: Option[Long], var dt_job_completed: Option[Long],
                      var execution_time: Option[Long], var err_message: Option[String], var iteration: Option[Int], encryption_key: Option[String]) {
  def this() = this("", "", "", "", "", "", "", 0, None, None, None, None, None, None, None)
}

trait OnDemandExhaustJob {
  
  implicit val className: String = getClassName;
  val connProperties: Properties = CommonUtil.getPostgresConnectionProps()
  val db: String = AppConf.getConfig("postgres.db")
  val url: String = AppConf.getConfig("postgres.url") + s"$db"
  val requestsTable: String = AppConf.getConfig("postgres.table.job_request")
  val jobStatus = List("SUBMITTED", "FAILED")
  val maxIterations = 3;
  val dbc: Connection = DriverManager.getConnection(url, connProperties.getProperty("user"), connProperties.getProperty("password"));
  dbc.setAutoCommit(true);

  def getClassName(): String;
  
  def cleanUp() {
    dbc.close();
  }

  def zipEnabled(): Boolean = true;

  def getRequests(jobId: String)(implicit spark: SparkSession, fc: FrameworkContext): Array[JobRequest] = {

    val encoder = Encoders.product[JobRequest]
    val reportConfigsDf = spark.read.jdbc(url, requestsTable, connProperties)
      .where(col("job_id") === jobId && col("iteration") < 3).filter(col("status").isin(jobStatus: _*));

    val requests = reportConfigsDf.withColumn("status", lit("PROCESSING")).as[JobRequest](encoder).collect()
    requests;
  }

  def updateStatus(request: JobRequest) = {
    val updateQry = s"UPDATE $requestsTable SET iteration = ?, status=?, dt_job_completed=?, execution_time=?, err_message=? WHERE tag=? and request_id=?";
    val pstmt: PreparedStatement = dbc.prepareStatement(updateQry);
    pstmt.setInt(1, request.iteration.getOrElse(0));
    pstmt.setString(2, request.status);
    pstmt.setTimestamp(3, if (request.dt_job_completed.isDefined) new Timestamp(request.dt_job_completed.get) else null);
    pstmt.setLong(4, request.execution_time.getOrElse(0L));
    pstmt.setString(5, StringUtils.abbreviate(request.err_message.getOrElse(""), 300));
    pstmt.setString(6, request.tag);
    pstmt.setString(7, request.request_id);
    pstmt.execute()
  }

  def updateRequest(request: JobRequest): Boolean = {
    val updateQry = s"UPDATE $requestsTable SET iteration = ?, status=?, download_urls=?, dt_file_created=?, dt_job_completed=?, execution_time=?, err_message=? WHERE tag=? and request_id=?";
    val pstmt: PreparedStatement = dbc.prepareStatement(updateQry);
    pstmt.setInt(1, request.iteration.getOrElse(0));
    pstmt.setString(2, request.status);
    val downloadURLs = request.download_urls.getOrElse(List()).toArray.asInstanceOf[Array[Object]];
    pstmt.setArray(3, dbc.createArrayOf("text", downloadURLs))
    pstmt.setTimestamp(4, if (request.dt_file_created.isDefined) new Timestamp(request.dt_file_created.get) else null);
    pstmt.setTimestamp(5, if (request.dt_job_completed.isDefined) new Timestamp(request.dt_job_completed.get) else null);
    pstmt.setLong(6, request.execution_time.getOrElse(0L));
    pstmt.setString(7, StringUtils.abbreviate(request.err_message.getOrElse(""), 300));
    pstmt.setString(8, request.tag);
    pstmt.setString(9, request.request_id);
    pstmt.execute()
  }

  private def updateRequests(requests: Array[JobRequest]) = {
    if (requests != null && requests.length > 0) {
      val updateQry = s"UPDATE $requestsTable SET iteration = ?, status=?, download_urls=?, dt_file_created=?, dt_job_completed=?, execution_time=?, err_message=? WHERE tag=? and request_id=?";
      val pstmt: PreparedStatement = dbc.prepareStatement(updateQry);
      for (request <- requests) {
        pstmt.setInt(1, request.iteration.getOrElse(0));
        pstmt.setString(2, request.status);
        val downloadURLs = request.download_urls.getOrElse(List()).toArray.asInstanceOf[Array[Object]];
        pstmt.setArray(3, dbc.createArrayOf("text", downloadURLs))
        pstmt.setTimestamp(4, if (request.dt_file_created.isDefined) new Timestamp(request.dt_file_created.get) else null);
        pstmt.setTimestamp(5, if (request.dt_job_completed.isDefined) new Timestamp(request.dt_job_completed.get) else null);
        pstmt.setLong(6, request.execution_time.getOrElse(0L));
        pstmt.setString(7, StringUtils.abbreviate(request.err_message.getOrElse(""), 300));
        pstmt.setString(8, request.tag);
        pstmt.setString(9, request.request_id);
        pstmt.addBatch();
      }
      val updateCounts = pstmt.executeBatch();
    }

  }

  def saveRequests(storageConfig: StorageConfig, requests: Array[JobRequest])(implicit conf: Configuration, fc: FrameworkContext) = {
    val zippedRequests = for (request <- requests) yield processRequestEncryption(storageConfig, request)
    updateRequests(zippedRequests)
  }

  def saveRequestAsync(storageConfig: StorageConfig, request: JobRequest)(implicit conf: Configuration, fc: FrameworkContext): CompletableFuture[JobRequest] = {

    CompletableFuture.supplyAsync(new Supplier[JobRequest]() {
      override def get() : JobRequest =  {
        val res = CommonUtil.time(saveRequest(storageConfig, request))
        JobLogger.log("Request is zipped", Some(Map("requestId" -> request.request_id, "timeTakenForZip" -> res._1)), INFO)
        request
      }
    })

  }

  def saveRequest(storageConfig: StorageConfig, request: JobRequest)(implicit conf: Configuration, fc: FrameworkContext): Boolean = {
    updateRequest(processRequestEncryption(storageConfig, request))
  }

  def processRequestEncryption(storageConfig: StorageConfig, request: JobRequest)(implicit conf: Configuration, fc: FrameworkContext): JobRequest = {
    val downloadURLs = CommonUtil.time(for (url <- request.download_urls.getOrElse(List())) yield {
      if (zipEnabled())
        try zipAndEncrypt(url, storageConfig, request)
        catch {
          case ex: Exception => ex.printStackTrace();
            if(canZipExceptionBeIgnored()) {
              url
            } else {
              markRequestAsFailed(request, "Zip, encrypt and upload failed")
              ""
            }

        }
      else
        url
    });
    request.execution_time = Some((downloadURLs._1 + request.execution_time.getOrElse(0L).asInstanceOf[Long]).asInstanceOf[Long])
    request.download_urls = Option(downloadURLs._2);
    request
  }

  def canZipExceptionBeIgnored(): Boolean = true

  @throws(classOf[Exception])
  private def zipAndEncrypt(url: String, storageConfig: StorageConfig, request: JobRequest)(implicit conf: Configuration, fc: FrameworkContext): String = {

    val path = Paths.get(url);
    val storageService = fc.getStorageService(storageConfig.store, storageConfig.accountKey.getOrElse(""), storageConfig.secretKey.getOrElse(""));
    val tempDir = AppConf.getConfig("spark_output_temp_dir") + request.request_id + "/"
    val localPath = tempDir + path.getFileName;
    fc.getHadoopFileUtil().delete(conf, tempDir);
    val filePrefix = storageConfig.store.toLowerCase() match {
      case "s3" =>
        CommonUtil.getS3File(storageConfig.container, "");
      case "azure" =>
        CommonUtil.getAzureFile(storageConfig.container, "", storageConfig.accountKey.getOrElse("azure_storage_key"))
      case _ =>
        storageConfig.fileName
    }
    val objKey = url.replace(filePrefix, "");
    if (storageConfig.store.equals("local")) {
      fc.getHadoopFileUtil().copy(objKey, localPath, conf)
    } else {
      storageService.download(storageConfig.container, objKey, tempDir, Some(false));
    }

    val zipPath = localPath.replace("csv", "zip")
    val zipObjectKey = objKey.replace("csv", "zip")

    request.encryption_key.map(key => {
      val zipParameters = new ZipParameters();
      zipParameters.setEncryptFiles(true);
      zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD); // AES encryption is not supported by default with various OS.
      val zipFile = new ZipFile(zipPath, key.toCharArray());
      zipFile.addFile(localPath, zipParameters)
    }).getOrElse({
      new ZipFile(zipPath).addFile(new File(localPath));
    })
    val resultFile = if (storageConfig.store.equals("local")) {
      fc.getHadoopFileUtil().copy(zipPath, zipObjectKey, conf)
    } else {
      storageService.upload(storageConfig.container, zipPath, zipObjectKey, Some(false), Some(0), Some(3), None);
    }
    fc.getHadoopFileUtil().delete(conf, tempDir);
    resultFile;
  }

  def markRequestAsFailed(request: JobRequest, failedMsg: String): JobRequest = {
    request.status = "FAILED";
    request.dt_job_completed = Option(System.currentTimeMillis());
    request.iteration = Option(request.iteration.getOrElse(0) + 1);
    request.err_message = Option(failedMsg);
    request
  }
}