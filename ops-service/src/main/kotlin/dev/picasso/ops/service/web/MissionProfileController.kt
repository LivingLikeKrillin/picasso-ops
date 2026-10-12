package dev.picasso.ops.service.web
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.missions.MissionProfiles
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
@RestController
class MissionProfileController(private val profiles:MissionProfiles) {
 private val json=ObjectMapper()
 @GetMapping("/api/mission-profiles") fun list():Any=mapOf("missions" to profiles.list())
 @PostMapping("/api/mission-profiles",consumes=[MediaType.APPLICATION_JSON_VALUE])
 fun create(@RequestHeader(Actor.MODE_HEADER,required=false) mode:String?,@RequestHeader(Actor.USER_HEADER,required=false) user:String?,@RequestBody(required=false) body:ByteArray?):ResponseEntity<Any> = guarded(mode,user,Mode.ENGINEER) {actor ->
  try {
   if(body==null || body.size>65536)throw InterfaceRejected("MISSION_BAD_INPUT","임무 본문은 64KiB 이하입니다")
   val value=runCatching{json.readTree(body)}.getOrNull()?.takeIf{it.isObject} ?: throw InterfaceRejected("MISSION_BAD_INPUT","JSON 객체가 필요합니다")
   ResponseEntity.ok(profiles.create(value,actor))
  }catch(e:InterfaceRejected){ResponseEntity.status(e.status).body(PreRejection(e.code,e.message))}
 }
}
