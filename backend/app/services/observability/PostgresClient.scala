package services.observability
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.Json
import scalikejdbc._
import services.PostgresConfig

import scala.util.{Failure, Success, Try}
import utils.Logging
import utils.attempt.{PostgresReadFailure, PostgresWriteFailure, Failure => GiantFailure}

trait PostgresClient {
    def insertEvent(event: IngestionEvent): Either[GiantFailure, Unit]
    def insertMetadata(metaData: BlobMetadata): Either[GiantFailure, Unit]
    def getEvents (ingestId: String, ingestIdIsPrefix: Boolean): Either[GiantFailure, List[BlobStatus]]

    def deleteBlobIngestionEventsAndMetadata(blobId: String): Either[GiantFailure, Long]
}

class PostgresClientDoNothing extends PostgresClient {
    override def insertEvent(event: IngestionEvent): Either[GiantFailure, Unit] = Right(())

    override def insertMetadata(metaData: BlobMetadata): Either[GiantFailure, Unit] = Right(())

    override def getEvents (ingestId: String, ingestIdIsPrefix: Boolean): Either[GiantFailure, List[BlobStatus]] = Right(List())

    def deleteBlobIngestionEventsAndMetadata(blobId: String): Either[GiantFailure, Long] = Right(0)

}

object PostgresHelpers {
  def postgresEpochToDateTime(epoch: Double) = new DateTime((epoch*1000).toLong, DateTimeZone.UTC)
}

class PostgresClientImpl(postgresConfig: PostgresConfig) extends PostgresClient with Logging {
    val dbHost = s"jdbc:postgresql://${postgresConfig.host}:${postgresConfig.port}/giant"
    // initialize JDBC driver & connection pool
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton(dbHost, postgresConfig.username, postgresConfig.password)
    implicit val session: AutoSession.type = AutoSession

    import EventDetails.detailsFormat

    def insertMetadata(metaData: BlobMetadata): Either[GiantFailure, Unit] = {
        Try {
            sql"""
            INSERT INTO blob_metadata (
              ingest_id,
                blob_id,
                file_size,
                path,
              insert_time
            ) VALUES (
              ${metaData.ingestId},
                ${metaData.blobId},
        ${metaData.fileSize},
        ${metaData.path},
              now()
            );""".execute().apply()
        } match {
            case Success(_) => Right(())
            case Failure(exception) =>
                logger.warn(s"""
              An exception occurred while inserting blob metadata
              blobId: ${metaData.blobId}, ingestId: ${metaData.ingestId} path: ${metaData.path}
              exception: ${exception.getMessage()}""", exception
                )
                Left(PostgresWriteFailure(exception))
        }
    }
    def insertEvent(event: IngestionEvent): Either[GiantFailure, Unit] = {
        Try {
            val detailsJson = event.details.map(Json.toJson(_).toString).getOrElse("{}")
            sql"""
            INSERT INTO ingestion_events (
                blob_id,
                ingest_id,
                type,
                status,
                details,
                event_time
            ) VALUES (
                ${event.metadata.blobId},
                ${event.metadata.ingestId},
                ${event.eventType.toString()},
                ${event.status.toString()},
                $detailsJson::JSONB,
                now()
            );""".execute().apply()
        } match {
            case Success(_) => Right(())
            case Failure(exception) =>
                logger.warn(s"""
          An exception occurred while inserting ingestion event
          blobId: ${event.metadata.blobId}, ingestId: ${event.metadata.ingestId} eventType: ${event.eventType.toString()}
          exception: ${exception.getMessage()}"""
                )
                Left(PostgresWriteFailure(exception))
        }
    }

    def getEvents(ingestId: String, ingestIdIsPrefix: Boolean): Either[PostgresReadFailure, List[BlobStatus]] = {
        Try {
          /**
            * The aim of this query is to merge ingestion events for each blob into a single row, containing the success/failure
            * status of each extractor that was expected to run on the ingestion.
            *
            * The subqueries are as follows:
            *   blob_extractors - get the extractors expected to run for each blob
            *   extractor_statuses - get the success/failure status for the extractors identified in blob_extractors
            *
            */
          val results = sql"""
          WITH problem_blobs AS (
            -- assume that blobs with more than 100 ingestion_events are failing to be ingested in an infinite loop
            SELECT blob_id
            from ingestion_events
            WHERE ingest_id LIKE ${if(ingestIdIsPrefix) LikeConditionEscapeUtil.beginsWith(ingestId) else ingestId}
            group by 1
            having count(*) > 100
          ),
          blob_extractors AS (
            -- get all the extractors expected for a given blob
            SELECT ingest_id, blob_id, jsonb_array_elements_text(details -> 'extractors') as extractor from ingestion_events
            WHERE ingest_id LIKE ${if(ingestIdIsPrefix) LikeConditionEscapeUtil.beginsWith(ingestId) else ingestId}
            AND type = ${IngestionEventType.MimeTypeDetected.toString}
            AND blob_id NOT IN (SELECT blob_id FROM problem_blobs)
          ),
          extractor_statuses as (
            -- Aggregate all the status updates for the relevant extractors for a given blob
            SELECT
              blob_extractors.blob_id,
              blob_extractors.ingest_id,
              blob_extractors.extractor,
              -- As the same status update may happen multiple times if a blob is reingested, it's useful to have the time
              -- this field is destined to be converted to a string so use epoch time (seconds) to make getting it back into
              -- a date a bit less of a faff
              ARRAY_AGG(EXTRACT(EPOCH from ingestion_events.event_time)) AS extractor_event_times,
              ARRAY_AGG(ingestion_events.status) AS extractor_event_statuses
            FROM blob_extractors
            LEFT JOIN ingestion_events
              ON blob_extractors.blob_id = ingestion_events.blob_id
              AND blob_extractors.ingest_id = ingestion_events.ingest_id
              -- there is no index on extractorName but we aren't expecting too many events for the same blob_id/ingest_id
              AND blob_extractors.extractor = ingestion_events.details ->> 'extractorName'
              AND ingestion_events.type = 'RunExtractor'
            -- A file may be uploaded multiple times within different ingests - use group by to merge them together
            GROUP BY 1,2,3
          )
          SELECT
            ie.blob_id,
            ie.ingest_id,
            ie.ingest_start,
            ie.most_recent_event,
            ie.event_types,
            ie.event_times,
            ie.event_statuses,
            ie.errors,
            ie.workspace_name AS "workspaceName",
            ie.mime_types AS "mimeTypes",
            ie.infinite_loop AS "infiniteLoop",
            ARRAY_AGG(DISTINCT blob_metadata.path ) AS paths,
            (ARRAY_AGG(blob_metadata.file_size))[1] as "fileSize",
            ARRAY_REMOVE(ARRAY_AGG(extractor_statuses.extractor), NULL) AS extractors,
            -- You can't array_agg arrays of varying cardinality so here we convert to string
            ARRAY_REMOVE(ARRAY_AGG(ARRAY_TO_STRING(extractor_statuses.extractor_event_times, ',','null')), NULL) AS "extractorEventTimes",
            ARRAY_REMOVE(ARRAY_AGG(ARRAY_TO_STRING(extractor_statuses.extractor_event_statuses, ',','null')), NULL) AS "extractorStatuses"
          FROM (
            SELECT
              blob_id,
              ingest_id,
              MIN(EXTRACT(EPOCH FROM event_time)) AS ingest_start,
              MAX(EXTRACT(EPOCH FROM event_time)) AS most_recent_event,
              ARRAY_AGG(type) as event_types,
              ARRAY_AGG(EXTRACT(EPOCH from event_time)) as event_times,
              ARRAY_AGG(status) as event_statuses,
              ARRAY_AGG(details -> 'errors') as errors,
              (ARRAY_AGG(details ->> 'workspaceName')  FILTER (WHERE details ->> 'workspaceName' IS NOT NULL))[1] as workspace_name,
              (ARRAY_AGG(details ->> 'mimeTypes')  FILTER (WHERE details ->> 'mimeTypes' IS NOT NULL))[1] as mime_types,
              FALSE AS infinite_loop
            FROM ingestion_events
            WHERE ingest_id LIKE ${if(ingestIdIsPrefix) LikeConditionEscapeUtil.beginsWith(ingestId) else ingestId}
            AND blob_id NOT IN (SELECT blob_id FROM problem_blobs)
            GROUP BY 1,2
            UNION
            -- blobs in the ingestion that are failing in an infinite loop
            SELECT DISTINCT
              blob_id,
              ingest_id,
              MIN(EXTRACT(EPOCH FROM event_time)) AS ingest_start,
              MAX(EXTRACT(EPOCH FROM event_time)) AS most_recent_event,
              array[]::text[] AS event_types,
              array[]::numeric[] AS event_times,
              array[]::text[] AS event_statuses,
              array['[]'::jsonb] AS errors,
              NULL AS workspace_name,
              NULL AS mime_types,
              TRUE AS infinite_loop
            FROM ingestion_events
            WHERE ingest_id LIKE ${if(ingestIdIsPrefix) LikeConditionEscapeUtil.beginsWith(ingestId) else ingestId}
            AND blob_id IN (SELECT blob_id FROM problem_blobs)
            GROUP BY 1,2
          ) AS ie
          LEFT JOIN blob_metadata USING(ingest_id, blob_id)
          LEFT JOIN extractor_statuses
            ON extractor_statuses.blob_id = ie.blob_id
            AND extractor_statuses.ingest_id = ie.ingest_id
          GROUP BY 1,2,3,4,5,6,7,8,9,10,11
          ORDER by ingest_start desc
     """.map(rs => {
            val eventTypes = rs.array("event_types").getArray.asInstanceOf[Array[String]]
                BlobStatus(
                  EventMetadata(
                      rs.string("blob_id"),
                      rs.string("ingest_id")
                  ),
                  BlobStatus.parsePathsArray(rs.array("paths").getArray().asInstanceOf[Array[String]]),
                  rs.longOpt("fileSize"),
                  rs.stringOpt("workspaceName"),
                  PostgresHelpers.postgresEpochToDateTime(rs.double("ingest_start")),
                  PostgresHelpers.postgresEpochToDateTime(rs.double("most_recent_event")),
                  IngestionEventStatus.parseEventStatus(
                    rs.array("event_times").getArray.asInstanceOf[Array[java.math.BigDecimal]].map(t =>PostgresHelpers.postgresEpochToDateTime(t.doubleValue)),
                    eventTypes,
                    rs.array("event_statuses").getArray.asInstanceOf[Array[String]]
                  ),
                  rs.arrayOpt("extractors").map { extractors =>
                      ExtractorStatus.parseDbStatusEvents(
                          extractors.getArray().asInstanceOf[Array[String]],
                          rs.array("extractorEventTimes").getArray().asInstanceOf[Array[String]],
                          rs.array("extractorStatuses").getArray().asInstanceOf[Array[String]]
                      )
                  }.getOrElse(List()),
                  IngestionError.parseIngestionErrors(
                    rs.array("errors").getArray.asInstanceOf[Array[String]],
                    eventTypes
                  ),
                  rs.stringOpt("mimeTypes"),
                  rs.boolean("infiniteLoop")
                )
            }
            ).list().apply()
            results
        }
        match {
            case Success(results) => Right(results)
            case Failure(exception) => Left(PostgresReadFailure(exception, s"getEvents failed: ${exception.getMessage}"))
        }
    }

  def deleteBlobIngestionEventsAndMetadata(blobId: String): Either[GiantFailure, Long] = {
    Try {
      DB.localTx { implicit session =>
        val numIngestionDeleted = sql"DELETE FROM ingestion_events WHERE blob_id = ${blobId}".executeUpdate().apply()
        val numBlobMetadataDeleted = sql"DELETE FROM blob_metadata WHERE blob_id = ${blobId}".executeUpdate().apply()
        numIngestionDeleted + numBlobMetadataDeleted
      }
    } match {
      case Success(numRowsDeleted) =>
        Right(numRowsDeleted)
      case Failure(exception) => Left(PostgresWriteFailure(exception))
    }
  }

}
