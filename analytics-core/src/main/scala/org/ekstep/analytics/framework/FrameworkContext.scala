package org.ekstep.analytics.framework

import ing.wbaa.druid.{ DruidConfig,QueryHost }
import ing.wbaa.druid.client.DruidClient
import org.sunbird.cloud.storage.BaseStorageService
import org.sunbird.cloud.storage.conf.AppConf
import org.sunbird.cloud.storage.factory.{ StorageServiceFactory }

import scala.collection.mutable.Map
import org.ekstep.analytics.framework.util.HadoopFileUtil
import org.apache.spark.util.LongAccumulator

class FrameworkContext {

  var dc: DruidClient = null;
  var drc: DruidClient = null;
  var storageContainers: Map[String, BaseStorageService] = Map();
  val fileUtil = new HadoopFileUtil();
  
  var inputEventsCount: LongAccumulator = _
  var outputEventsCount: LongAccumulator = _

  def initialize(storageServices: Option[Array[(String, String, String)]]) {
    dc = DruidConfig.DefaultConfig.client;
    if (storageServices.nonEmpty) {
      storageServices.get.foreach(f => {
        getStorageService(f._1, f._2, f._3);
      })
    }
  }

  def getStorageService(storageType: String): BaseStorageService = {
    getStorageService(storageType, storageType, storageType);
  }

  def getHadoopFileUtil(): HadoopFileUtil = {
    return fileUtil;
  }

  def getStorageService(storageType: String, storageKey: String, storageSecret: String): BaseStorageService = {
    if("local".equals(storageType)) {
      return null;
    }
    if (!storageContainers.contains(storageType + "|" + storageKey)) {
      storageContainers.put(storageType + "|" + storageKey, StorageServiceFactory.getStorageService(org.sunbird.cloud.storage.factory.StorageConfig(storageType, AppConf.getStorageKey(storageKey), AppConf.getStorageSecret(storageSecret))));
    }
    storageContainers.get(storageType + "|" + storageKey).get
  }

  def setDruidClient(druidClient: DruidClient, druidRollupClient: DruidClient) {
    dc = druidClient;
    drc = druidRollupClient;
  }

  def getDruidClient(): DruidClient = {
    if (null == dc) {
      dc = DruidConfig.DefaultConfig.client;
    }
    return dc;
  }

  def getDruidRollUpClient(): DruidClient = {
    if (null == drc) {
      val conf = DruidConfig.DefaultConfig
      drc = DruidConfig.apply(
        Seq(QueryHost(AppConf.getConfig("druid.rollup.host"), AppConf.getConfig("druid.rollup.port").toInt)),
        conf.secure,
        conf.url,conf.healthEndpoint,conf.datasource,conf.responseParsingTimeout,conf.clientBackend,conf.clientConfig,conf.scanQueryLegacyMode,conf.zoneId,conf.system).client
    }
    return drc;
  }

  def shutdownDruidClient() = {
    if (dc != null) dc.actorSystem.terminate()
  }

  def shutdownDruidRollUpClien() = {
    if (drc != null) drc.actorSystem.terminate()
  }

  def shutdownStorageService() = {
    if (storageContainers.nonEmpty) {
      storageContainers.foreach(f => f._2.closeContext());
    }
  }

  def closeContext() = {
    shutdownDruidClient();
    shutdownDruidRollUpClien();
    shutdownStorageService();
  }

}