import type { MissionProfile } from './api'
export function missionTemplate(kind:'inspect'|'move',id:string,name:string,description:string):MissionProfile {
 const property='location'
 return {
  workMasterId:id,name,description,requiredEvidence:'E0',
  inputSchema:{type:'object',properties:{targets:{type:'array',minItems:1,maxItems:20,items:{type:'object',properties:{id:{type:'string',minLength:1,maxLength:64},[property]:{type:'string',minLength:1,maxLength:64}},required:['id',property],additionalProperties:false}}},required:['targets'],additionalProperties:false},
  orderMapping:{equipment:[{input:'targets',equipmentUse:'mission_target',id:'id',properties:{[property]:property}}],materials:[]},
  sample:{targets:[{id:'T1',[property]:'bay-7'}]},
  definition:{schemaVersion:1,workMasterId:id,maxEvidence:'E0',preferredOptionals:{},steps:[{kind:'unit',id:kind,skill:kind==='inspect'?'inspect':'navigate_to',forEach:'mission_target',unitId:{from:'ITEM_ID'},parameters:kind==='inspect'?{target:{from:'ITEM_ID'}}:{location:{from:'ITEM_PROPERTY',property}},destination:{from:'ITEM_PROPERTY',property}}]}
 }
}
