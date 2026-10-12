package dev.picasso.ops.service.requests

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Observation only: this worker never submits work or infers physical completion. */
@Component
class WorkResultCollector(private val requests:WorkRequests) {
 @Volatile private var health:Map<String,Any?> = mapOf("state" to "WAITING","lastSuccessAt" to null,"lastAttemptAt" to null,"observedRequests" to null,"error" to null)
 @Scheduled(fixedDelayString="\${ops.results.poll-ms:5000}",initialDelayString="\${ops.results.poll-ms:5000}")
 fun collect() {
  val at=java.time.Instant.now().toString()
  try { val count=requests.collectResults();health=mapOf("state" to "OK","lastSuccessAt" to at,"lastAttemptAt" to at,"observedRequests" to count,"error" to null) }
  catch(e:Exception) { health=health+mapOf("state" to "UNAVAILABLE","lastAttemptAt" to at,"error" to "호스트 관측 또는 결과 저장 실패") }
 }
 fun status()=health+mapOf("cursor" to requests.cursorStatus())
}
@RestController
class WorkResultCollectorController(private val collector:WorkResultCollector) {
 @GetMapping("/api/work-requests/collector") fun status()=collector.status()
}
