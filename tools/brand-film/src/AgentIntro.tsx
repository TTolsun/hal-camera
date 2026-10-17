import {AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig} from 'remotion';

const font='Segoe UI, Malgun Gothic, sans-serif';
export const AgentIntro=()=>{
  const frame=useCurrentFrame(),{fps}=useVideoConfig(),t=frame/fps;
  const reveal=(at:number)=>interpolate(frame,[at*fps,(at+.22)*fps],[0,1],{extrapolateLeft:'clamp',extrapolateRight:'clamp'});
  return <AbsoluteFill style={{background:'linear-gradient(135deg,#171a1e,#07090c)',fontFamily:font,color:'#ecede9'}}>
    <div style={{position:'absolute',left:130,top:72,fontSize:28,color:'#a5cae8'}}>Automation / AI agent</div>
    <div style={{position:'absolute',left:125,top:140,fontSize:64,fontWeight:600}}>AI agent가 스킬을 읽고, CLI를 실행합니다.</div>
    <div style={{position:'absolute',left:130,top:280,width:1660,height:665,border:'2px solid #506a80',borderRadius:20,background:'#0a1119',overflow:'hidden'}}>
      <div style={{height:70,boxSizing:'border-box',padding:'20px 32px',background:'#1b2a37',fontSize:25,display:'flex',justifyContent:'space-between'}}><span>AI AGENT · 작업 기록</span><span style={{color:'#a6c6df'}}>요청 → 스킬 읽기 → Shell 실행</span></div>
      <div style={{padding:'25px 34px',fontSize:34,lineHeight:1.55,borderBottom:'1px solid #2e3c49'}}><span style={{color:'#8aabca',fontSize:22,display:'block',marginBottom:7}}>사용자</span>halcam-cli 스킬로 카메라를 열고,<br/>스트림·모드를 바꾼 뒤 검증해줘.</div>
      <div style={{margin:'22px 32px',padding:'18px 24px',borderLeft:'4px solid #9ecffa',background:'#152638',opacity:reveal(.8)}}>
        <div style={{fontSize:23,color:'#a0c6e6',marginBottom:10}}>AI agent · 스킬 읽기 {t>=2.5?'✓':''}</div>
        <div style={{fontFamily:'Consolas, monospace',fontSize:36}}>skills/halcam-cli/SKILL.md</div>
        <div style={{fontSize:24,color:'#bbcad7',marginTop:13}}>ADB로 촬영·녹화·CTS·벤치마크를 실행하고 결과 파일을 회수합니다.</div>
      </div>
      <div style={{margin:'24px 32px',opacity:reveal(2.6)}}>
        <div style={{fontSize:24,color:'#a6dfbd',marginBottom:14}}>AI agent → 도구 호출: Shell</div>
        <div style={{fontFamily:'Consolas, monospace',fontSize:30,color:'#c0e1ff',whiteSpace:'pre-line'}}>$ adb shell sh /data/local/tmp/halcam preview --camera 0</div>
      </div>
    </div>
    <div style={{position:'absolute',left:132,top:994,fontSize:19,color:'#7d8d9a'}}>AI agent 작업 흐름을 재현한 장면입니다.</div>
  </AbsoluteFill>;
};
