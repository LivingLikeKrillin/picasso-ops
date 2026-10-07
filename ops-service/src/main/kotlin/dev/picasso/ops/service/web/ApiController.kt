package dev.picasso.ops.service.web

import dev.picasso.ops.service.adapters.AdapterListService
import dev.picasso.ops.service.adapters.AdapterListView
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationRecord
import dev.picasso.ops.service.profiles.ProfileListService
import dev.picasso.ops.service.profiles.ProfileListView
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 화면이 부르는 읽기 API. 화면은 이 서비스만 부른다(스펙 §4). 읽기는 부작용이 없다. */
@RestController
@RequestMapping("/api")
class ApiController(
    private val robots: RobotListService,
    private val adapters: AdapterListService,
    private val operations: OperationLog,
    private val profiles: ProfileListService,
) {
    @GetMapping("/robots")
    fun robots(): RobotListView = robots.read()

    @GetMapping("/adapters")
    fun adapters(): AdapterListView = adapters.read()

    /** 카탈로그 요약과 개정판 목록(P2·S1d 스펙 §8.1). */
    @GetMapping("/profiles")
    fun profiles(): ProfileListView = profiles.read()

    @GetMapping("/operations")
    fun operations(): List<OperationRecord> = operations.list()
}
