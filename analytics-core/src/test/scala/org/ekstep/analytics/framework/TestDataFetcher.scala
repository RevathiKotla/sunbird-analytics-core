package org.ekstep.analytics.framework

import org.ekstep.analytics.framework.exception.DataFetcherException
import org.ekstep.analytics.framework.util.JSONUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers
import org.sunbird.cloud.storage.BaseStorageService
import org.ekstep.analytics.framework.fetcher.{AzureDataFetcher, DruidDataFetcher, S3DataFetcher}

/**
 * @author Santhosh
 */


case class GroupByPid(time: String, producer_id: String, producer_pid: String, total_duration: Double, count: Int)
case class TimeSeriesData(time: String, count: Int)

class TestDataFetcher extends SparkSpec with Matchers with MockFactory {

    "DataFetcher" should "fetch the streaming events matching query" in {

        val rdd = DataFetcher.fetchStreamData(null, null);
        rdd should be (null);

    }

    it should "fetch the events from local file" in {

        implicit val fc = new FrameworkContext();
        fc.inputEventsCount = sc.longAccumulator("Count");
        val search = Fetcher("local", None, Option(Array(
            Query(None, None, None, None, None, None, None, None, None, Option("src/test/resources/sample_telemetry.log"))
        )));
        val rdd = DataFetcher.fetchBatchData[Event](search);
        rdd.count should be (7437)

        val search0 = Fetcher("local", None, Option(Array(
            Query(None, None, None, None, None, None, None, None, None, Option("src/test/resources/sample_telemetry_2.log"))
        )));
        val rddString = DataFetcher.fetchBatchData[String](search0);
        rddString.count should be (19)

        val search1 = Fetcher("local", None, Option(Array(
            Query(None, None, None, None, None, None, None, None, None, Option("src/test/resources/sample_telemetry.log"))
        )));
        val rdd1 = DataFetcher.fetchBatchData[TestDataFetcher](search1);
        rdd1.count should be (0)

        val search2 = Fetcher("local", None, Option(Array(
            Query(None, None, None, None, None, None, None, None, None, None)
        )));
        val rdd2 = DataFetcher.fetchBatchData[TestDataFetcher](search2);
        rdd1.count should be (0)
    }

    it should "fetch no file from S3 and return an empty RDD" in {

        implicit val mockFc = mock[FrameworkContext];
        val mockStorageService = mock[BaseStorageService]
        mockFc.inputEventsCount = sc.longAccumulator("Count");
        (mockFc.getStorageService(_:String):BaseStorageService).expects("aws").returns(mockStorageService);
        (mockStorageService.searchObjects _).expects("dev-data-store", "abc/", Option("2012-01-01"), Option("2012-02-01"), None, "yyyy-MM-dd").returns(null);
        (mockStorageService.getPaths _).expects("dev-data-store", null).returns(List("src/test/resources/sample_telemetry_2.log"))
        val queries = Option(Array(
            Query(Option("dev-data-store"), Option("abc/"), Option("2012-01-01"), Option("2012-02-01"))
        ));
        val rdd = DataFetcher.fetchBatchData[Event](Fetcher("S3", None, queries));
        rdd.count should be (19)
    }

    it should "throw DataFetcherException" in {

        implicit val fc = new FrameworkContext();
        // Throw unknown fetcher type found
        the[DataFetcherException] thrownBy {
            DataFetcher.fetchBatchData[Event](Fetcher("s3", None, None));
        }

        the[DataFetcherException] thrownBy {
            val fileFetcher = Fetcher("file", None, Option(Array(
                Query(None, None, None, None, None, None, None, None, None, Option("src/test/resources/sample_telemetry.log"))
            )));
            DataFetcher.fetchBatchData[Event](fileFetcher);
        } should have message "Unknown fetcher type found"
    }

    it should "fetch the batch events from azure" in {

        implicit val mockFc = mock[FrameworkContext];
        mockFc.inputEventsCount = sc.longAccumulator("Count");
        val mockStorageService = mock[BaseStorageService]
        (mockFc.getStorageService(_:String):BaseStorageService).expects("azure").returns(mockStorageService);
        (mockStorageService.searchObjects _).expects("dev-data-store", "raw/", Option("2017-08-31"), Option("2017-08-31"), None, "yyyy-MM-dd").returns(null);
        (mockStorageService.getPaths _).expects("dev-data-store", null).returns(List("src/test/resources/sample_telemetry_2.log"))
        val queries = Option(Array(
            Query(Option("dev-data-store"), Option("raw/"), Option("2017-08-31"), Option("2017-08-31"))
        ));
        val rdd = DataFetcher.fetchBatchData[Event](Fetcher("azure", None, queries));
        rdd.count should be (19)
    }

    it should "invoke the druid data fetcher" in {

        implicit val fc = new FrameworkContext();
        val unknownQuery = DruidQueryModel("scan", "telemetry-events", "LastWeek", Option("day"), None, None, Option(List(DruidFilter("in", "eid", None, Option(List("START", "END"))))))
        the[DataFetcherException] thrownBy {
            DataFetcher.fetchBatchData[TimeSeriesData](Fetcher("druid", None, None, Option(unknownQuery)));
        } should have message "Unknown druid query type found"
    }

    it should "fetch no data for none fetcher type" in {
        implicit val fc = new FrameworkContext();
        fc.getStorageService("azure") should not be (null)
        val rdd = DataFetcher.fetchBatchData[Event](Fetcher("none", None, None));
        rdd.isEmpty() should be (true)
    }

    it should "cover the missing branches in S3DataFetcher, AzureDataFetcher and DruidDataFetcher" in {
      implicit val fc = new FrameworkContext();
      var query = JSONUtils.deserialize[Query]("""{"bucket":"test-container","prefix":"test/","folder":"true","endDate":"2020-01-10"}""")
      S3DataFetcher.getObjectKeys(Array(query)).head should be ("s3n://test-container/test/2020-01-10")
      AzureDataFetcher.getObjectKeys(Array(query)).head should be ("wasb://test-container@azure-test-key.blob.core.windows.net/test/2020-01-10")

      query = JSONUtils.deserialize[Query]("""{"bucket":"test-container","prefix":"test/","folder":"true","endDate":"2020-01-10","excludePrefix":"test"}""")
      S3DataFetcher.getObjectKeys(Array(query)).size should be (0)
      AzureDataFetcher.getObjectKeys(Array(query)).size should be (0)

    }


    it should "check for getFilteredKeys from azure via partitions" in {

        // with single partition
        val query1 = Query(Option("dev-data-store"), Option("raw/"), Option("2020-06-10"), Option("2020-06-11"), None, None, None, None, None, None, None, None, None, None, Option(List(0)))
        val keys1 = DataFetcher.getFilteredKeys(query1, Array("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-1-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-1-1591845501666.json.gz"), Option(List(0)))
        keys1.length should be (2)
        keys1.head should be ("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-0-1591845501666.json.gz")

        // with mutilple partition
        val query2 = Query(Option("dev-data-store"), Option("raw/"), Option("2020-06-11"), Option("2020-06-11"), None, None, None, None, None, None, None, None, None, None, Option(List(0,1)))
        val keys2 = DataFetcher.getFilteredKeys(query2, Array("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-1-1591845501666.json.gz"), Option(List(0,1)))
        keys2.length should be (2)
        keys2.head should be ("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz")

        // without partition
        val query3 = Query(Option("dev-data-store"), Option("raw/"), Option("2020-06-11"), Option("2020-06-11"), None, None, None, None, None, None, None, None, None, None, None)
        val keys3 = DataFetcher.getFilteredKeys(query3, Array("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-1-1591845501666.json.gz"), None)
        keys3.length should be (2)
        keys3.head should be ("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz")

        // without only end date
        val query4 = Query(Option("dev-data-store"), Option("raw/"), None, Option("2020-06-11"), None, None, None, None, None, None, None, None, None, None, Option(List(0,1)))
        val keys4 = DataFetcher.getFilteredKeys(query4, Array("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-1-1591845501666.json.gz"), Option(List(0,1)))
        keys4.length should be (2)
        keys4.head should be ("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz")

        // without only end date and delta
        val query5 = Query(Option("dev-data-store"), Option("raw/"), None, Option("2020-06-11"), Option(1), None, None, None, None, None, None, None, None, None, Option(List(0)))
        val keys5 = DataFetcher.getFilteredKeys(query5, Array("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-1-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-0-1591845501666.json.gz", "https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-11-1-1591845501666.json.gz"), Option(List(0)))
        keys5.length should be (2)
        keys5.head should be ("https://sunbirddevprivate.blob.core.windows.net/dev-data-store/raw/2020-06-10-0-1591845501666.json.gz")
    }

    "TestDataFetcher" should "test this" in {

        implicit val fc = new FrameworkContext
        //val request = s"""{"queryType": "groupBy","dataSource": "content-model-snapshot","intervals": "1901-01-01T00:00:00+00:00/2101-01-01T00:00:00+00:00","aggregations": [{"name": "count","type": "count"}],"dimensions": [{"fieldName": "channel","aliasName": "channel"}, {"fieldName": "identifier","aliasName": "identifier","type": "Extraction","outputType": "STRING","extractionFn": [{"type": "javascript","fn": "function(str){return str == null ? null: str.split('.')[0]}"}]}, {"fieldName": "name","aliasName": "name"}, {"fieldName": "pkgVersion","aliasName": "pkgVersion"}, {"fieldName": "contentType","aliasName": "contentType"}, {"fieldName": "lastSubmittedOn","aliasName": "lastSubmittedOn"}, {"fieldName": "mimeType","aliasName": "mimeType"}, {"fieldName": "resourceType","aliasName": "resourceType"}, {"fieldName": "createdFor","aliasName": "createdFor"}, {"fieldName": "createdOn","aliasName": "createdOn"}, {"fieldName": "lastPublishedOn","aliasName": "lastPublishedOn"}, {"fieldName": "creator","aliasName": "creator"}, {"fieldName": "board","aliasName": "board"}, {"fieldName": "medium","aliasName": "medium"}, {"fieldName": "gradeLevel","aliasName": "gradeLevel"}, {"fieldName": "subject","aliasName": "subject"}, {"fieldName": "status","aliasName": "status"}],"filters": [{"type": "equals","dimension": "contentType","value": "Resource"}, {"type": "in","dimension": "status","values": ["Live", "Draft", "Review", "Unlisted"]}, {"type": "equals","dimension": "createdFor","value": "01241974041332940818"}],"postAggregation": [],"descending": "false","limitSpec": {"type": "default","limit": 1000000,"columns": [{"dimension": "count","direction": "descending"}]}}""".stripMargin
        //val request = s"""{"queryType":"groupBy","dataSource":"summary-rollup-syncts","granularity":"all","intervals":"LastDay","aggregations":[{"type":"longSum","name":"sum__total_count","fieldName":"total_count"}],"dimensions":[{"fieldName":"dimensions_mode","aliasName":"mode"},{"fieldName":"derived_loc_state","aliasName":"state"}],"postAggregation":[]}""".stripMargin
                val request = s"""{"intervals":"LastDay","dataSource":"summary-rollup-syncts","descending":"false","dimensions":[{"fieldName":"dimensions_pdata_id","aliasName":"pid"},{"fieldName":"dimensions_pdata_pid","aliasName":"pdata_id"},{"fieldName":"dimensions_type","aliasName":"dim_type"},{"fieldName":"dimensions_pdata_ver","aliasName":"pdata_ver"},{"fieldName":"dimensions_mode","aliasName":"dimensions_mode"},{"fieldName":"content_name","aliasName":"content_name"},{"fieldName":"content_board","aliasName":"content_board"},{"fieldName":"content_mimetype","aliasName":"content_mimetype"},{"fieldName":"content_channel","aliasName":"content_channel"},{"fieldName":"content_subject","aliasName":"content_subject"},{"fieldName":"content_created_for","aliasName":"content_created_for"},{"fieldName":"object_id","aliasName":"object_id"},{"fieldName":"object_type","aliasName":"object_type"},{"fieldName":"object_rollup_l1","aliasName":"object_rollup_l1"},{"fieldName":"dialcode_channel","aliasName":"dialcode_channel"},{"fieldName":"derived_loc_state","aliasName":"state"},{"fieldName":"derived_loc_district","aliasName":"district"},{"fieldName":"derived_loc_from","aliasName":"derived_loc_from"},{"fieldName":"device_first_access","aliasName":"device_first_access"},{"fieldName":"collection_name","aliasName":"collection_name"},{"fieldName":"collection_board","aliasName":"collection_board"},{"fieldName":"collection_type","aliasName":"collection_type"},{"fieldName":"collection_channel","aliasName":"collection_channel"},{"fieldName":"collection_subject","aliasName":"collection_subject"},{"fieldName":"collection_created_for","aliasName":"collection_created_for"}],"aggregations":[{"name":"total_scans","type":"longSum","fieldName":"total_count"},{"name":"total_interact","type":"longSum","fieldName":"total_interactions"},{"name":"total_time","type":"longSum","fieldName":"total_time_spent"}],"queryType":"groupBy"}"""
                val druidQuery = JSONUtils.deserialize[DruidQueryModel](request)
                println(druidQuery)
                val druidResponse = DruidDataFetcher.getDruidData(druidQuery,true)
                println(druidResponse)


    }

}