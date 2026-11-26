import http from 'node:http';
import {timingSafeEqual} from 'node:crypto';
const key=process.env.OPENAI_API_KEY, token=process.env.BIKER_ACCESS_TOKEN, model=process.env.OPENAI_MODEL||'gpt-5';
if(!key||!token||token.length<24)throw Error('Set OPENAI_API_KEY and a random BIKER_ACCESS_TOKEN (24+ chars)');
let recent=[],active=0;
function auth(header){const a=Buffer.from(header||''),b=Buffer.from('Bearer '+token);return a.length===b.length&&timingSafeEqual(a,b)}
function json(res,status,data){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data))}
const server=http.createServer(async(req,res)=>{
 if(req.method!=='POST'||req.url!=='/chat')return json(res,404,{error:'Not found'});
 if(!auth(req.headers.authorization))return json(res,401,{error:'Unauthorized'});
 recent=recent.filter(t=>Date.now()-t<60000);if(recent.length>=10||active>=2)return json(res,429,{error:'Too many requests'});
 recent.push(Date.now());active++;
 try{
  let size=0,parts=[];for await(const part of req){size+=part.length;if(size>32768){json(res,413,{error:'Too large'});return;}parts.push(part)}
  let body;try{body=JSON.parse(Buffer.concat(parts).toString('utf8'))}catch{return json(res,400,{error:'Invalid JSON'})}
  const messages=body?.messages;if(!Array.isArray(messages)||messages.length<1||messages.length>12||messages.at(-1)?.role!=='user'||messages.some(m=>!m||!['user','assistant'].includes(m.role)||typeof m.content!=='string'||!m.content.trim()||m.content.length>4000))return json(res,400,{error:'Invalid messages'});
  const response=await fetch('https://api.openai.com/v1/responses',{method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify({model,store:false,instructions:'Ты BikerVoice. Отвечай по-русски, бодро, дружелюбно, с умеренным жаргоном панка, байкера, рокера и хиппи. Без канцелярита. Короткие естественные фразы для озвучки. Не выдавай себя за человека и не придумывай факты.',input:messages.map(({role,content})=>({role,content})),max_output_tokens:1200,reasoning:{effort:'low'}}),signal:AbortSignal.timeout(50000)});
  if(!response.ok)return json(res,response.status===429?429:502,{error:'OpenAI request failed'});
  const data=await response.json();
  const reply=(data.output||[]).filter(x=>x.type==='message').flatMap(x=>x.content||[]).map(x=>x.type==='output_text'?x.text:x.type==='refusal'?x.refusal:'').filter(Boolean).join('\n');
  if(!reply)return json(res,502,{error:'Empty model response'});
  return json(res,200,{reply});
 }catch{return json(res,502,{error:'Gateway request failed'})}finally{active--}
});
server.requestTimeout=20000;server.headersTimeout=10000;
server.listen(Number(process.env.PORT||8080),process.env.HOST||'127.0.0.1');
