package dev.picasso.ops.service.missions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.interfaces.InterfaceRejected
import dev.picasso.ops.service.joborders.GenericJobOrderDraft
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.dao.DuplicateKeyException

/** Immutable input contract and declarative order mapping. No executable expressions or scripts. */
class MissionProfiles(private val jdbc:JdbcClient) {
 private val json=ObjectMapper()
 fun list():List<JsonNode> = jdbc.sql("SELECT profile FROM ops.mission_profile ORDER BY work_master_id").query {rs,_ -> json.readTree(rs.getString(1))}.list()
 fun find(id:String):JsonNode? = jdbc.sql("SELECT profile FROM ops.mission_profile WHERE work_master_id=:id").param("id",id).query {rs,_ -> json.readTree(rs.getString(1))}.optional().orElse(null)
 fun create(value:JsonNode,actor:Actor):JsonNode {
  val id=value.path("workMasterId").asText()
  if(!Regex("[A-Z][A-Za-z0-9]{0,63}").matches(id) || id in setOf("InspectAsset","PrepareSequencedRack","DeliverContainer"))fail("새 임무 ID는 대문자로 시작하는 영문·숫자 1~64자이며 기존 업무 ID를 사용할 수 없습니다")
  if(value.path("name").asText().isBlank() || value.path("name").asText().length>120 || value.path("description").asText().length>2000)fail("업무 이름 1~120자와 설명 2000자 이하가 필요합니다")
  val definition=value.path("definition")
  if(!definition.isObject || definition.path("workMasterId").asText()!=id || definition.path("maxEvidence").asText()!=value.path("requiredEvidence").asText())fail("정의의 workMasterId와 최고 근거가 입력 계약과 일치해야 합니다")
  if(!value.path("inputSchema").isObject || value.path("inputSchema").path("type").asText()!="object")fail("입력 스키마는 object여야 합니다")
  validateSchema(value.path("inputSchema"))
  if(value["inputSchema"]["properties"].has("workMasterId"))fail("workMasterId는 서버가 정하는 예약 입력입니다")
  read(value,value.path("sample"))
  val old=find(id)
  if(old!=null) {if(old==value)return old;throw InterfaceRejected("MISSION_EXISTS","같은 임무 ID에 다른 입력 계약을 등록할 수 없습니다",409)}
  try {jdbc.sql("INSERT INTO ops.mission_profile(work_master_id,profile,created_by) VALUES(:id,CAST(:profile AS JSONB),:actor)").param("id",id).param("profile",value.toString()).param("actor",actor.header()).update()}
  catch(e:DuplicateKeyException){if(find(id)!=value)throw InterfaceRejected("MISSION_EXISTS","이미 등록된 임무입니다",409)}
  return find(id)!!
 }
 fun sample(id:String):ObjectNode?=find(id)?.let{read(it,it["sample"]).order.put("jobOrderId","JO-SAMPLE")}
 fun read(profile:JsonNode,input:JsonNode):GenericJobOrderDraft {
  validate(profile.path("inputSchema"),input,"입력")
  val evidence=profile.path("requiredEvidence").asText()
  if(evidence !in setOf("E0","E1","E2"))fail("요구 근거는 E0~E2입니다")
  val order=json.createObjectNode().put("workMasterId",profile["workMasterId"].asText()).put("version",1).put("requiredEvidence",evidence)
  order.putObject("parameters")
  val equipment=order.putArray("equipmentRequirements")
  val materials=order.putArray("materialRequirements")
  val mapping=profile.path("orderMapping")
  if(!mapping.isObject || !mapping.path("equipment").isArray || mapping.path("equipment").isEmpty)fail("설비 변환 규칙이 필요합니다")
  mapping["equipment"].forEach {rule ->
   val items=input.path(rule.path("input").asText())
   if(!items.isArray || items.isEmpty || items.size()>100)fail("${rule.path("input").asText()}는 1~100개 배열이어야 합니다")
   val use=rule.path("equipmentUse").asText();if(use.isBlank() || use.length>64)fail("설비 쓰임이 필요합니다")
   if(!rule.path("properties").isObject)fail("설비 속성 변환 객체가 필요합니다")
   items.forEach {item ->
    val id=item.path(rule.path("id").asText());if(!id.isTextual || id.asText().isBlank() || id.asText().length>64)fail("설비 ID는 1~64자 문자열입니다")
    val props=equipment.addObject().put("id",id.asText()).put("equipmentUse",use).putObject("properties")
    rule["properties"].fields().forEach {(target,source)->
     val v=item.path(source.asText());if(!source.isTextual || !v.isTextual || v.asText().isBlank() || v.asText().length>256)fail("설비 속성 $target 값을 읽지 못했습니다")
     props.put(target,v.asText())
    }
   }
  }
  if(mapping.has("materials")) {
   if(!mapping["materials"].isArray)fail("자재 변환은 배열입니다")
   mapping["materials"].forEach {rule ->
    val items=input.path(rule.path("input").asText());if(!items.isArray || items.size()>100)fail("자재 입력은 100개 이하 배열입니다")
    items.forEach {item ->val id=item.path(rule.path("id").asText());val quantity=item.path(rule.path("quantity").asText())
     if(!id.isTextual || id.asText().isBlank() || !quantity.isIntegralNumber || !quantity.canConvertToInt() || quantity.asInt()<1)fail("자재 ID와 양의 정수 수량이 필요합니다")
     materials.addObject().put("materialDefinitionId",id.asText()).put("quantity",quantity.asInt())
    }
   }
  }
  val ids=equipment.map{it["id"].asText()};if(ids.distinct().size!=ids.size)fail("설비 ID가 겹칩니다")
  return GenericJobOrderDraft(order)
 }
 private fun validateSchema(schema:JsonNode,depth:Int=0) {
  if(depth>6)fail("입력 스키마 깊이는 6 이하입니다")
  val type=schema.path("type").asText()
  val allowed=when(type){"object"->setOf("type","properties","required","additionalProperties");"array"->setOf("type","items","minItems","maxItems");"string"->setOf("type","minLength","maxLength","enum");"integer","number"->setOf("type","minimum","maximum","enum");"boolean"->setOf("type");else->fail("지원하지 않는 스키마 자료형: $type")}
  if(schema.fieldNames().asSequence().any{it !in allowed})fail("지원하지 않는 스키마 키가 있습니다")
  if(type=="object") {
   if(!schema.path("properties").isObject || schema.path("properties").size()>32 || schema.path("additionalProperties").asBoolean(true))fail("객체는 properties(32개 이하)와 additionalProperties:false가 필요합니다")
   val required=schema.path("required");if(!required.isArray || required.any{!it.isTextual || !schema["properties"].has(it.asText())})fail("필수 입력 이름이 잘못되었습니다")
   schema["properties"].fields().forEach{(key,child)->if(!Regex("[A-Za-z][A-Za-z0-9_]{0,63}").matches(key))fail("입력 필드 이름이 잘못되었습니다");validateSchema(child,depth+1)}
  }
  if(type=="array")validateSchema(schema.path("items"),depth+1)
  for(key in listOf("minItems","maxItems","minLength","maxLength"))if(schema.has(key) && (!schema[key].isIntegralNumber || schema[key].asInt()<0 || schema[key].asInt()>1024))fail("${key}는 0~1024 정수입니다")
  for(key in listOf("minimum","maximum"))if(schema.has(key) && !schema[key].isNumber)fail("${key}는 숫자입니다")
  if(schema.has("enum") && (!schema["enum"].isArray || schema["enum"].isEmpty || schema["enum"].size()>100))fail("enum은 1~100개 배열입니다")
 }
 private fun validate(schema:JsonNode,value:JsonNode,path:String) {
  val type=schema.path("type").asText()
  val valid=when(type){"object"->value.isObject;"array"->value.isArray;"string"->value.isTextual;"integer"->value.isIntegralNumber;"number"->value.isNumber;"boolean"->value.isBoolean;else->false}
  if(!valid)fail("$path: $type 자료형이 필요합니다")
  if(schema.has("enum") && schema["enum"].none{it==value})fail("$path: 허용 값이 아닙니다")
  when(type) {
   "object"->{val props=schema["properties"];schema["required"].forEach{if(!value.has(it.asText()))fail("$path.${it.asText()}: 필수 입력입니다")};value.fields().forEach{(key,v)->if(!props.has(key))fail("$path.$key: 허용하지 않는 입력입니다");validate(props[key],v,"$path.$key")}}
   "array"->{if(value.size()<schema.path("minItems").asInt(0) || value.size()>schema.path("maxItems").asInt(100).coerceAtMost(100))fail("$path: 배열 길이가 범위를 벗어났습니다");value.forEachIndexed{i,v->validate(schema["items"],v,"$path[$i]")}}
   "string"->if(value.asText().length<schema.path("minLength").asInt(0) || value.asText().length>schema.path("maxLength").asInt(256).coerceAtMost(1024))fail("$path: 문자열 길이가 범위를 벗어났습니다")
   "integer","number"->if(schema.has("minimum") && value.decimalValue()<schema["minimum"].decimalValue() || schema.has("maximum") && value.decimalValue()>schema["maximum"].decimalValue())fail("$path: 숫자가 범위를 벗어났습니다")
  }
 }
 private fun fail(message:String):Nothing=throw InterfaceRejected("MISSION_BAD_INPUT",message)
}
