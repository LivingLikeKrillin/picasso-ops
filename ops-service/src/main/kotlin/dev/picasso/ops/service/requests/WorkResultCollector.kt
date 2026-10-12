package dev.picasso.ops.service.requests

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Observation only: this worker never submits work or infers physical completion. */
@Component
class WorkResultCollector(private val requests:WorkRequests,@Value("\${ops.requests.stale-seconds:300}") private val staleSeconds:Long) {
 @Volatile private var health:Map<String,Any?> = mapOf("state" to "WAITING","lastSuccessAt" to null,"lastAttemptAt" to null,"observedRequests" to null,"error" to null)
 @Scheduled(fixedDelayString="\${ops.results.poll-ms:5000}",initialDelayString="\${ops.results.poll-ms:5000}")
 fun collect() {
  val at=java.time.Instant.now().toString()
  // Stale intake closes first so a stopped submission becomes IN_DOUBT before observation can promote it. Never blocks collection.
  try { requests.sweepStale(java.time.Duration.ofSeconds(staleSeconds)) }
  catch(e:Exception) { org.slf4j.LoggerFactory.getLogger(javaClass).warn("중단 접수 정리 실패: {}",e.javaClass.simpleName) }
  try { val count=requests.collectResults();health=mapOf("state" to "OK","lastSuccessAt" to at,"lastAttemptAt" to at,"observedRequests" to count,"error" to null) }
  catch(e:Exception) { health=health+mapOf("state" to "UNAVAILABLE","lastAttemptAt" to at,"error" to "호스트 관측 또는 결과 저장 실패") }
 }
 fun status()=health+mapOf("cursor" to requests.cursorStatus())
}
@RestController
class WorkResultCollectorController(private val collector:WorkResultCollector) {
 @GetMapping("/api/work-requests/collector") fun status()=collector.status()
}
