package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.requests.RequestRejected
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.stations.StationCoordinator
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@RestController
class StationController(private val stations:StationCoordinator) {
 private val json=ObjectMapper()
 @GetMapping("/api/stations") fun list()=mapOf("stations" to stations.stations(),"runs" to stations.runs())
 @GetMapping("/api/stations/runs/{id}") fun detail(@PathVariable id:UUID)=checked {ResponseEntity.ok(stations.snapshot(id))}
 @GetMapping("/api/stations/runs/{id}/evidence") fun evidence(@PathVariable id:UUID)=checked {ResponseEntity.ok(stations.snapshot(id)+mapOf("schema" to "ops.station.evidence/1","authority" to "deterministic-ops","explanationMayChangeState" to false))}
 @PostMapping("/api/stations") fun create(@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?)=guarded(mode,user,Mode.ENGINEER){actor->checked{ResponseEntity.ok(stations.createStation(actor,read(body)))}}
 @PostMapping("/api/stations/runs") fun enqueue(@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?)=guarded(mode,user,Mode.OPERATOR){actor->checked{ResponseEntity.ok(stations.enqueue(actor,read(body)))}}
 /** A person's source confirmation for a run held on a source-unobserved host answer ([StationCoordinator.confirmSource]). */
 @PostMapping("/api/stations/runs/{id}/source-confirmations") fun confirmSource(@PathVariable id:UUID,@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?)=guarded(mode,user,Mode.OPERATOR){actor->checked{ResponseEntity.ok(stations.confirmSource(id,actor,read(body)))}}
 @PostMapping("/api/stations/runs/{id}/decision") fun decide(@PathVariable id:UUID,@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?)=guarded(mode,user,Mode.OPERATOR){actor->checked{ResponseEntity.ok(stations.decide(id,actor,read(body)))}}
 private fun read(body:ByteArray?)=body?.takeIf{it.size<=65536}?.let{runCatching{json.readTree(it)}.getOrNull()}?.takeIf{it.isObject} ?: throw RequestRejected("BAD_BODY","64KiB 이하 JSON 객체가 필요합니다")
 private inline fun checked(action:()->ResponseEntity<Any>):ResponseEntity<Any> = try{action()}catch(e:RequestRejected){ResponseEntity.status(e.status).body(PreRejection(e.code,e.message))}catch(e:InterfaceRejected){ResponseEntity.status(e.status).body(PreRejection(e.code,e.message))}catch(e:IllegalArgumentException){ResponseEntity.badRequest().body(PreRejection("STATION_INPUT",e.message?:"입력 오류"))}
}
@Component
class StationWorker(private val coordinator:StationCoordinator) {
 @Volatile private var health:Map<String,Any?> = mapOf("state" to "WAITING")
 @Scheduled(fixedDelayString="\${ops.stations.poll-ms:2000}",initialDelayString="\${ops.stations.poll-ms:2000}")
 fun pump(){try{coordinator.tick();health=mapOf("state" to "OK","at" to java.time.Instant.now())}catch(e:Exception){health=mapOf("state" to "UNAVAILABLE","at" to java.time.Instant.now(),"reason" to "스테이션 처리 실패; 점유 유지");org.slf4j.LoggerFactory.getLogger(javaClass).warn("스테이션 처리 실패: {}",e.javaClass.simpleName)}}
 fun status()=health
}
@RestController
class StationHealthController(private val worker:StationWorker) {@GetMapping("/api/stations/health") fun health()=worker.status()}

@RestController
class MissionResourceController(private val requests:dev.picasso.ops.service.requests.WorkRequests,private val interfaces:dev.picasso.ops.service.interfaces.MissionInterfaces) {
 private val json=ObjectMapper()
 @GetMapping("/api/mission-resources") fun list()=mapOf("requirements" to requests.resources.list())
 @PostMapping("/api/mission-resources") fun create(@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?)=guarded(mode,user,Mode.ENGINEER){actor->
  try {
   val p=body?.takeIf{it.size<=4096}?.let{runCatching{json.readTree(it)}.getOrNull()}
   if(p?.isObject!=true || p.fieldNames().asSequence().toSet()!=setOf("workMasterId","stationId","reason") || listOf("workMasterId","stationId","reason").any{p[it]?.isTextual!=true})throw RequestRejected("BAD_RESOURCE","임무·스테이션·사유가 필요합니다")
   val wm=p["workMasterId"].asText()
   if(wm !in dev.picasso.ops.service.joborders.JobOrderForm.WORK_MASTERS && interfaces.profiles.find(wm)==null)throw RequestRejected("BAD_RESOURCE","등록된 임무만 자원 요구를 선언합니다")
   ResponseEntity.ok(requests.resources.create(actor,wm,p["stationId"].asText(),p["reason"].asText()))
  }catch(e:RequestRejected){ResponseEntity.status(e.status).body(PreRejection(e.code,e.message))}
 }
}
