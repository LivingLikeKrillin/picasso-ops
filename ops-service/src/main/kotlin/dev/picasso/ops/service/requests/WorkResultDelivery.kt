package dev.picasso.ops.service.requests

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** Local Mock MES PoC only. Stable keys support at-least-once delivery; HTTP success is not an ACK. */
@Component
class WorkResultDelivery(private val jdbc:JdbcClient,
 @Suppress("UNUSED_PARAMETER") requests:WorkRequests,
 @Value("\${ops.integration.callback-url:}") private val target:String,
 @Value("\${ops.integration.callback-token:}") private val token:String,
 @Value("\${ops.integration.source:mock-mes}") private val source:String,
 @Value("\${ops.results.delivery-batch:10}") private val batch:Int=10) {
 private val json=ObjectMapper()
 private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
 /** Set after this process's one catch-up succeeds; later result versions are queued where they are written ([ResultDeliveryQueue]). */
 @Volatile private var caughtUp=false
 init {
  if(target.isNotBlank()) checkTarget(target)
  require(batch in 1..100) {"ops.results.delivery-batch 는 1~100 입니다"}
 }
 @Scheduled(fixedDelayString="\${ops.results.delivery-ms:2000}",initialDelayString="\${ops.results.delivery-ms:2000}")
 fun deliver() {
  if(target.isBlank())return
  try {
   if(!caughtUp) { catchUp(); caughtUp=true }
   repeat(batch) { if(!deliverOne())return }
  } catch(e:Exception) {org.slf4j.LoggerFactory.getLogger(javaClass).warn("로컬 결과 전달 큐 처리 실패: {}",e.javaClass.simpleName)}
 }
 /**
  * Once per process, before its first send: queue result versions of [source] that have no delivery row yet. They were written
  * before write-time queueing existed, while delivery was off, or by a writer without this target. Returns the rows queued.
  */
 fun catchUp():Int = jdbc.sql("""INSERT INTO ops.work_result_delivery(receipt_id,version,target_url)
    SELECT r.receipt_id,v.version,:target FROM ops.work_result v JOIN ops.work_request r USING(receipt_id)
    WHERE r.source_system_id=:source AND NOT EXISTS(SELECT 1 FROM ops.work_result_delivery d WHERE d.receipt_id=v.receipt_id AND d.version=v.version)
    ON CONFLICT(receipt_id,version) DO NOTHING""")
  .param("target",target).param("source",source).update()
 /** Claim commits before local I/O. A crashed sender's lease expires. */
 fun deliverOne():Boolean {
  val claim=UUID.randomUUID()
  val row=jdbc.sql("""WITH candidate AS (
   SELECT receipt_id,version FROM ops.work_result_delivery WHERE state<>'DELIVERED' AND next_attempt_at<=clock_timestamp()
   ORDER BY next_attempt_at,receipt_id,version FOR UPDATE SKIP LOCKED LIMIT 1
  ) UPDATE ops.work_result_delivery d SET state='IN_FLIGHT',attempts=attempts+1,claim_token=:claim,
   next_attempt_at=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
   FROM candidate c WHERE d.receipt_id=c.receipt_id AND d.version=c.version RETURNING d.receipt_id,d.version,d.target_url,d.attempts""")
   .param("claim",claim).query{rs,_ -> Delivery(rs.getObject(1,UUID::class.java),rs.getInt(2),rs.getString(3),rs.getInt(4))}.optional().orElse(null) ?: return false
  var status:Int?=null
  try {
   val result=jdbc.sql("SELECT snapshot,result_hash FROM ops.work_result WHERE receipt_id=:id AND version=:version")
    .param("id",row.id).param("version",row.version).query{rs,_ -> rs.getString(1) to rs.getString(2)}.single()
   val body=json.createObjectNode().put("receiptId",row.id.toString()).put("version",row.version).put("resultHash",result.second)
   body.set<com.fasterxml.jackson.databind.JsonNode>("snapshot",json.readTree(result.first))
   val request=HttpRequest.newBuilder(checkTarget(row.target)).timeout(Duration.ofSeconds(3))
    .header("Content-Type","application/json").header("Idempotency-Key","${row.id}/v${row.version}")
   if(token.isNotBlank())request.header("Authorization","Bearer $token")
   status=client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.discarding()).statusCode()
   if(status in 200..299) {
    jdbc.sql("UPDATE ops.work_result_delivery SET state='DELIVERED',last_error=NULL,delivered_at=clock_timestamp(),updated_at=clock_timestamp() WHERE receipt_id=:id AND version=:version AND claim_token=:claim")
     .param("id",row.id).param("version",row.version).param("claim",claim).update()
    return true
   }
  }catch(e:Exception){if(e is InterruptedException)Thread.currentThread().interrupt()}
  val delay=(1L shl row.attempts.coerceAtMost(8)).coerceAtMost(300)
  jdbc.sql("UPDATE ops.work_result_delivery SET state='RETRY',last_error=:error,next_attempt_at=clock_timestamp()+(:delay * interval '1 second'),updated_at=clock_timestamp() WHERE receipt_id=:id AND version=:version AND claim_token=:claim")
   .param("error",status?.let{"HTTP $it"}?:"로컬 연결 실패 또는 응답 불명").param("delay",delay).param("id",row.id).param("version",row.version).param("claim",claim).update()
  return true
 }
 private data class Delivery(val id:UUID,val version:Int,val target:String,val attempts:Int)
 companion object {
  fun checkTarget(value:String):URI {
   val uri=URI.create(value)
   require(uri.userInfo==null && uri.fragment==null && uri.scheme=="http" && uri.host in setOf("127.0.0.1","[::1]")) {"이 PoC callback은 이 컴퓨터의 IP 리터럴 루프백 HTTP 주소만 허용합니다"}
   return uri
  }
 }
}

/**
 * Queues a result version for the local callback in the transaction that writes it, so no round scans every result.
 * Same properties as [WorkResultDelivery]: a blank [target] queues nothing, and only results of [source] are queued.
 */
class ResultDeliveryQueue(val target:String, val source:String) {
 init { if(target.isNotBlank()) WorkResultDelivery.checkTarget(target) }
 fun enqueue(jdbc:JdbcClient, receipt:UUID, version:Int, receiptSource:String) {
  if(target.isBlank() || receiptSource!=source)return
  jdbc.sql("INSERT INTO ops.work_result_delivery(receipt_id,version,target_url) VALUES(:id,:version,:target) ON CONFLICT(receipt_id,version) DO NOTHING")
   .param("id",receipt).param("version",version).param("target",target).update()
 }
}
