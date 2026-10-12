package dev.picasso.ops.host.web

import dev.picasso.ops.host.store.HostRecords
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class ResultEventController(private val records:HostRecords) {
 @GetMapping("/host/result-events")
 fun events(@RequestParam(defaultValue="0") after:Long,@RequestParam(defaultValue="100") limit:Int):ResponseEntity<Any> =
  try {ResponseEntity.ok(records.resultEvents(after,limit))}
  catch(e:IllegalArgumentException){ResponseEntity.badRequest().body(HostRejection("BAD_CURSOR",e.message ?: "invalid cursor"))}
}
