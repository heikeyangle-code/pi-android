import {spawn} from "node:child_process";
const E=process.argv[2];
const p=spawn("node",[E+"/dist/cli.js","--mode","rpc","-e",process.env.EXT||"../ext.ts","--no-session"],{cwd:"cwd",env:{...process.env,PI_CODING_AGENT_DIR:process.cwd()+"/agent",PI_OFFLINE:"1"}});
let buf="";const t0=Date.now();
p.stdout.on("data",d=>{buf+=d;let i;while((i=buf.indexOf("\n"))>=0){const l=buf.slice(0,i);buf=buf.slice(i+1);let m;try{m=JSON.parse(l)}catch{continue}
 if(m.type==="extension_ui_request"){console.log(Date.now()-t0,"ui_req",m.method,(m.title??m.message??m.statusText??"").slice(0,700));
  if(m.method==="input"&&m.title==="paui:custom"){p.stdin.write(JSON.stringify({type:"extension_ui_response",id:m.id,value:'{"clicked":"ok"}'})+"\n")}}}});
setTimeout(()=>p.stdin.write(JSON.stringify({id:"2",type:"prompt",message:process.env.CMD||"/probe"})+"\n"),3000);
setTimeout(()=>{p.kill();process.exit(0)},7000);
