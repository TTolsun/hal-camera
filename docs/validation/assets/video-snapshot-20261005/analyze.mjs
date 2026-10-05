import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
// Analyze local evidence only. No media pixels are included in the output JSON.
// stts supplies decode timestamps; ctts offsets recover presentation order for B-frames.
if (!process.argv[2]) throw Error('Usage: node analyze.mjs <media-directory>');
const dir=path.resolve(process.argv[2]);
const results=[];
for(const file of fs.readdirSync(dir).filter(f=>/\.(mp4|jpg)$/.test(f))){
 const b=fs.readFileSync(path.join(dir,file));
 const result={file,bytes:b.length,sha256:createHash('sha256').update(b).digest('hex')};
 if(file.endsWith('.jpg')){
  for(let at=2;at+4<b.length;){
   if(b[at]!==255)throw Error('Invalid JPEG marker');
   const marker=b[at+1],n=b.readUInt16BE(at+2);
   if([192,193,194].includes(marker)){result.width=b.readUInt16BE(at+7);result.height=b.readUInt16BE(at+5);break;}
   if(marker===218)break;
   at+=n+2;
  }
 }else{
  function boxes(start,end){const out=[];for(let at=start;at+8<=end;){let n=b.readUInt32BE(at),h=8;if(n===1){n=Number(b.readBigUInt64BE(at+8));h=16;}if(n===0)n=end-at;if(n<h||at+n>end)throw Error('Invalid MP4 bounds');out.push({type:b.toString('ascii',at+4,at+8),start:at+h,end:at+n});at+=n;}return out;}
  const find=(p,t)=>boxes(p.start,p.end).find(x=>x.type===t);
  const moov=find({start:0,end:b.length},'moov');
  for(const trak of boxes(moov.start,moov.end).filter(x=>x.type==='trak')){
   const mdia=find(trak,'mdia'),h=find(mdia,'hdlr');if(b.toString('ascii',h.start+8,h.start+12)!=='vide')continue;
   const mdhd=find(mdia,'mdhd'),ts=b.readUInt32BE(mdhd.start+(b[mdhd.start]===1?20:12));
   const stbl=find(find(mdia,'minf'),'stbl'),stts=find(stbl,'stts'),stsd=find(stbl,'stsd'),sample=boxes(stsd.start+8,stsd.end)[0];
   const runs=[],histogram={};let frames=0,ticks=0;
   for(let i=0;i<b.readUInt32BE(stts.start+4);i++){const count=b.readUInt32BE(stts.start+8+i*8),delta=b.readUInt32BE(stts.start+12+i*8);runs.push({count,delta});histogram[delta]=(histogram[delta]||0)+count;frames+=count;ticks+=count*delta;}
   const mode=Number(Object.entries(histogram).sort((a,b)=>b[1]-a[1])[0][0]);
   const gaps=[];let offset=0,index=0;
   for(const {count,delta} of runs){if(delta>mode*1.5){for(let j=0;j<count;j++)gaps.push({sample:index+j,decodeSeconds:(offset+j*delta)/ts,intervalMs:delta*1000/ts});}offset+=count*delta;index+=count;}
   const ctts=find(stbl,'ctts'),offsets=[];
   if(ctts){for(let i=0;i<b.readUInt32BE(ctts.start+4);i++){const n=b.readUInt32BE(ctts.start+8+i*8),o=b[ctts.start]===1?b.readInt32BE(ctts.start+12+i*8):b.readUInt32BE(ctts.start+12+i*8);for(let j=0;j<n;j++)offsets.push(o);}if(offsets.length!==frames)throw Error('ctts sample count mismatch');}
   const pts=[];let dts=0,k=0;for(const {count,delta} of runs){for(let j=0;j<count;j++){pts.push(dts+(offsets[k++]||0));dts+=delta;}}
   pts.sort((a,b)=>a-b);const intervals=pts.slice(1).map((p,i)=>(p-pts[i])*1000/ts),sorted=[...intervals].sort((a,b)=>a-b),median=sorted[Math.floor(sorted.length/2)];
   const presentationGaps=intervals.flatMap((ms,i)=>ms>median*1.5?[{frame:i,seconds:(pts[i]-pts[0])/ts,intervalMs:ms}]:[]);
   Object.assign(result,{codec:sample.type,width:b.readUInt16BE(sample.start+24),height:b.readUInt16BE(sample.start+26),frames,seconds:ticks/ts,fps:frames*ts/ticks,timescale:ts,decodeIntervalTicks:histogram,modeIntervalMs:mode*1000/ts,gaps,hasCompositionOffsets:!!ctts,presentation:{medianMs:median,maxMs:sorted.at(-1),gaps:presentationGaps}});
  }
 }
 results.push(result);
}
fs.writeFileSync(path.join(dir,'media-analysis.json'),JSON.stringify(results,null,2)+'\n');
console.log(JSON.stringify(results.filter(r=>r.file.endsWith('.mp4')),null,2));
