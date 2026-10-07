package dev.picasso.ops.service.web

import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationRecord
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 화면이 부르는 API. 화면은 이 서비스만 부른다(스펙 §4). S1a 는 읽기 2개다. */
@RestController
@RequestMapping("/api")
class ApiController(
    private val robots: RobotListService,
    private val operations: OperationLog,
) {
    @GetMapping("/robots")
    fun robots(): RobotListView = robots.read()

    @GetMapping("/operations")
    fun operations(): List<OperationRecord> = operations.list()
}
