package filodb.query.exec

import scala.concurrent.Future

import kamon.trace.Span
import monix.execution.Scheduler

import filodb.core.DatasetRef
import filodb.core.metadata.Column.ColumnType
import filodb.core.query._
import filodb.memory.format.ZeroCopyUTF8String._
import filodb.memory.format.vectors.{CustomBuckets, MutableHistogram}
import filodb.query._
import filodb.query.AggregationOperator.Avg

case class PromQlRemoteExec(queryEndpoint: String,
                            requestTimeoutMs: Long,
                            queryContext: QueryContext,
                            dispatcher: PlanDispatcher,
                            dataset: DatasetRef,
                            remoteExecHttpClient: RemoteExecHttpClient) extends RemoteExec {
  private val defaultColumns = Seq(ColumnInfo("timestamp", ColumnType.TimestampColumn),
    ColumnInfo("value", ColumnType.DoubleColumn))

//TODO Don't use PromQL API to talk across clusters
  val columns= Map("histogram" -> Seq(ColumnInfo("timestamp", ColumnType.TimestampColumn),
    ColumnInfo("h", ColumnType.HistogramColumn)),
  Avg.entryName -> (defaultColumns :+  ColumnInfo("count", ColumnType.LongColumn)) ,
  "default" -> defaultColumns, QueryFunctionConstants.stdVal -> (defaultColumns ++
      Seq(ColumnInfo("mean", ColumnType.DoubleColumn), ColumnInfo("count", ColumnType.LongColumn))))

  val recordSchema = Map("histogram" -> SerializedRangeVector.toSchema(columns.get("histogram").get),
    Avg.entryName -> SerializedRangeVector.toSchema(columns.get(Avg.entryName).get),
    "default" -> SerializedRangeVector.toSchema(columns.get("default").get),
    QueryFunctionConstants.stdVal -> SerializedRangeVector.toSchema(columns.get(QueryFunctionConstants.stdVal).get))

  val resultSchema = Map("histogram" -> ResultSchema(columns.get("histogram").get, 1),
    Avg.entryName -> ResultSchema(columns.get(Avg.entryName).get, 1),
    "default" -> ResultSchema(columns.get("default").get, 1),
    QueryFunctionConstants.stdVal -> ResultSchema(columns.get(QueryFunctionConstants.stdVal).get, 1))

  private val builder = SerializedRangeVector.newBuilder()

  override val urlParams = Map("query" -> promQlQueryParams.promQl)
  private val dummyQueryStats = QueryStats()

  override def sendHttpRequest(execPlan2Span: Span, httpTimeoutMs: Long)
                              (implicit sched: Scheduler): Future[QueryResponse] = {

    import PromCirceSupport._
    import io.circe.parser
    remoteExecHttpClient.httpPost(queryEndpoint, requestTimeoutMs,
      queryContext.submitTime, getUrlParams(), queryContext.traceInfo)
      .map { response =>
        // Error response from remote partition is a nested json present in response.body
        // as response status code is not 2xx
        if (response.body.isLeft) {
          parser.decode[ErrorResponse](response.body.left.get) match {
            case Right(errorResponse) =>
              QueryError(queryContext.queryId, readQueryStats(errorResponse.queryStats),
              RemoteQueryFailureException(response.code.toInt, errorResponse.status, errorResponse.errorType,
                errorResponse.error))
            case Left(ex)             => QueryError(queryContext.queryId, QueryStats(), ex)
          }
        }
        else {
          response.unsafeBody match {
            case Left(error)            => QueryError(queryContext.queryId, QueryStats(), error.error)
            case Right(successResponse) => toQueryResponse(successResponse, queryContext.queryId, execPlan2Span)
          }
        }
      }
  }

  // TODO: Set histogramMap=true and parse histogram maps.  The problem is that code below assumes normal double
  //   schema.  Would need to detect ahead of time to use TransientHistRow(), so we'd need to add schema to output,
  //   and detect it in execute() above.  Need to discuss compatibility issues with Prometheus.
  def toQueryResponse(response: SuccessResponse, id: String, parentSpan: kamon.trace.Span): QueryResponse = {
    val queryResponse = if (response.data.result.isEmpty) {
      logger.debug("PromQlRemoteExec generating empty QueryResult as result is empty")
      QueryResult(
        id, ResultSchema.empty, Seq.empty,
        readQueryStats(response.queryStats), readQueryWarnings(response.queryWarnings),
        if (response.partial.isDefined) response.partial.get else false,
        response.message
      )
    } else {
      if (response.data.result.head.aggregateResponse.isDefined) genAggregateResult(response, id)
      else {
        val samples = response.data.result.head.values.getOrElse(Seq(response.data.result.head.value.get))
        if (samples.isEmpty) {
          logger.debug("PromQlRemoteExec generating empty QueryResult as samples is empty")
          QueryResult(
            id, ResultSchema.empty, Seq.empty,
            readQueryStats(response.queryStats),
            readQueryWarnings(response.queryWarnings),
            if (response.partial.isDefined) response.partial.get else false,
            response.message
          )
        } else {
          samples.head match {
            // Passing histogramMap = true so DataSampl will be HistSampl for histograms
            case HistSampl(timestamp, buckets) => genHistQueryResult(response, id)
            case _ => genDefaultQueryResult(response, id)
          }
        }
      }
    }
    queryResponse
  }

  def genAggregateResult(response: SuccessResponse, id: String): QueryResult = {

    val aggregateResponse = response.data.result.head.aggregateResponse.get
    if (aggregateResponse.aggregateSampl.isEmpty) {
      QueryResult(
        id, ResultSchema.empty, Seq.empty,
        readQueryStats(response.queryStats),
        readQueryWarnings(response.queryWarnings),
        if (response.partial.isDefined) response.partial.get else false,
        response.message
      )
    } else {
      aggregateResponse.aggregateSampl.head match {
        case _: AvgSampl    => genAvgQueryResult(response, id)
        case _: StdValSampl => genStdValQueryResult(response, id)
      }
    }
  }

  def genDefaultQueryResult(response: SuccessResponse, id: String): QueryResult = {
    val rangeVectors = response.data.result.map { r =>
      val samples = r.values.getOrElse(Seq(r.value.get))

      val rv = new RangeVector {
        val row = new TransientRow()

        override def key: RangeVectorKey = CustomRangeVectorKey(r.metric.map(m => m._1.utf8 -> m._2.utf8))

        override def rows(): RangeVectorCursor = {
          import NoCloseCursor._
          samples.iterator.collect { case v: Sampl =>
            row.setLong(0, v.timestamp * 1000)
            row.setDouble(1, v.value)
            row
          }
        }
        override def numRows: Option[Int] = Option(samples.size)

        override def outputRange: Option[RvRange] = Some(RvRange(promQlQueryParams.startSecs * 1000,
                                                            promQlQueryParams.stepSecs * 1000,
                                                            promQlQueryParams.endSecs * 1000))
      }
      // dont add this size to queryStats since it was already added by callee use dummy QueryStats()
      SerializedRangeVector(rv, builder, recordSchema.get("default").get,
        queryWithPlanName(queryContext), dummyQueryStats)
      // TODO: Handle stitching with verbose flag
    }
    QueryResult(
      id, resultSchema.get("default").get, rangeVectors,
      readQueryStats(response.queryStats),
      readQueryWarnings(response.queryWarnings),
      if (response.partial.isDefined) response.partial.get else false,
      response.message
    )
  }

  def genHistQueryResult(response: SuccessResponse, id: String): QueryResult = {

    val rangeVectors = response.data.result.map { r =>
      val samples = r.values.getOrElse(Seq(r.value.get))

      val rv = new RangeVector {
        val row = new TransientHistRow()

        override def key: RangeVectorKey = CustomRangeVectorKey(r.metric.map(m => m._1.utf8 -> m._2.utf8))

        override def rows(): RangeVectorCursor = {
          import NoCloseCursor._

          samples.iterator.collect { case v: HistSampl =>
            row.setLong(0, v.timestamp * 1000)
            val sortedBucketsWithValues = v.buckets.toArray.map { h =>
              if (h._1.toLowerCase.equals("+inf")) (Double.PositiveInfinity, h._2) else (h._1.toDouble, h._2)
            }.sortBy(_._1)
            val hist = MutableHistogram(CustomBuckets(sortedBucketsWithValues.map(_._1)),
              sortedBucketsWithValues.map(_._2))
            row.setValues(v.timestamp * 1000, hist)
            row
          }
        }

        override def numRows: Option[Int] = Option(samples.size)

        override def outputRange: Option[RvRange] = Some(RvRange(promQlQueryParams.startSecs * 1000,
          promQlQueryParams.stepSecs * 1000,
          promQlQueryParams.endSecs * 1000))

      }
      // dont add this size to queryStats since it was already added by callee use dummy QueryStats()
      SerializedRangeVector(rv, builder, recordSchema.get("histogram").get, queryContext.origQueryParams.toString,
        dummyQueryStats)
      // TODO: Handle stitching with verbose flag
    }
    QueryResult(
      id, resultSchema.get("histogram").get, rangeVectors,
      readQueryStats(response.queryStats),
      readQueryWarnings(response.queryWarnings),
      if (response.partial.isDefined) response.partial.get else false,
      response.message
    )
  }

  def genAvgQueryResult(response: SuccessResponse, id: String): QueryResult = {
    val rangeVectors = response.data.result.map { d =>
      val rv = new RangeVector {
          val row = new AvgAggTransientRow()

          override def key: RangeVectorKey = CustomRangeVectorKey(d.metric.map(m => m._1.utf8 -> m._2.utf8))

          override def rows(): RangeVectorCursor = {
            import NoCloseCursor._
            d.aggregateResponse.get.aggregateSampl.iterator.collect { case a: AvgSampl =>
              row.setLong(0, a.timestamp * 1000)
              row.setDouble(1, a.value)
              row.setLong(2, a.count)
              row
            }
          }
          override def numRows: Option[Int] = Option(d.aggregateResponse.get.aggregateSampl.size)

          override def outputRange: Option[RvRange] = Some(RvRange(promQlQueryParams.startSecs * 1000,
            promQlQueryParams.stepSecs * 1000,
            promQlQueryParams.endSecs * 1000))
      }
      // dont add this size to queryStats since it was already added by callee use dummy QueryStats()
      SerializedRangeVector(rv, builder, recordSchema.get(Avg.entryName).get,
        queryWithPlanName(queryContext), dummyQueryStats)
    }

    // TODO: Handle stitching with verbose flag
    QueryResult(
      id, resultSchema.get(Avg.entryName).get, rangeVectors,
      readQueryStats(response.queryStats),
      readQueryWarnings(response.queryWarnings),
      if (response.partial.isDefined) response.partial.get else false,
      response.message
    )
  }

  def genStdValQueryResult(response: SuccessResponse, id: String): QueryResult = {
    val rangeVectors = response.data.result.map { d =>
      val rv = new RangeVector {
        val row = new StdValAggTransientRow()

        override def key: RangeVectorKey = CustomRangeVectorKey(d.metric.map(m => m._1.utf8 -> m._2.utf8))

        override def rows(): RangeVectorCursor = {
          import NoCloseCursor._
          d.aggregateResponse.get.aggregateSampl.iterator.collect { case a: StdValSampl =>
            row.setLong(0, a.timestamp * 1000)
            row.setDouble(1, a.stddev)
            row.setDouble(2, a.mean)
            row.setLong(3, a.count)
            row
          }
        }
        override def numRows: Option[Int] = Option(d.aggregateResponse.get.aggregateSampl.size)

        override def outputRange: Option[RvRange] = Some(RvRange(promQlQueryParams.startSecs * 1000,
          promQlQueryParams.stepSecs * 1000,
          promQlQueryParams.endSecs * 1000))
      }
      // dont add this size to queryStats since it was already added by callee use dummy QueryStats()
      SerializedRangeVector(rv, builder, recordSchema.get(QueryFunctionConstants.stdVal).get,
        queryWithPlanName(queryContext), dummyQueryStats)
    }

    // TODO: Handle stitching with verbose flag
    QueryResult(
      id, resultSchema.get("stdval").get, rangeVectors,
      readQueryStats(response.queryStats),
      readQueryWarnings(response.queryWarnings),
      if (response.partial.isDefined) response.partial.get else false,
      response.message)
  }

}
