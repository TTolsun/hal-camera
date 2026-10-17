import {AbsoluteFill, CanvasImage, Img, interpolate, Sequence, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {AgentIntro} from './AgentIntro';
import {SensorPreview} from './SensorPreview';

const font = 'Segoe UI, Malgun Gothic, sans-serif';
const accent = '#9bc9ee';
const beats = [
  {at:0, title:'카메라 열기', note:'', command:'preview --camera 0', screen:'photo', preview:true},
  {at:5, title:'카메라 열기', note:'Camera 0 · Photo', command:'preview --camera 0', screen:'photo', preview:true},
  {at:7, title:'스트림 설정', note:'Preview · YUV · JPEG', command:'preview --camera 0\n  --jpeg-size 1920x1080', screen:'streams', preview:false},
  {at:9, title:'사진 촬영', note:'Photo', command:'capture --camera 0', screen:'photo', preview:true},
  {at:10, title:'Video 전환', note:'Photo → Video', command:'preview --camera 0\n  --mode Video', screen:'video', preview:true},
  {at:11.5, title:'사양 확인', note:'Probe', command:'probe', screen:'probe', preview:false},
  {at:12.5, title:'테스트 실행', note:'Custom CTS', command:'cts run --cases\n  custom:fast_on_off', screen:'cts-custom', preview:false},
  {at:13.5, title:'테스트 목록', note:'AOSP CTS', command:'cts cases', screen:'cts-vendored', preview:false},
  {at:14.5, title:'벤치마크 실행', note:'Benchmark', command:'benchmark run --camera 0', screen:'benchmark-running', preview:false},
  {at:16, title:'결과 회수', note:'benchmark.json · PC로 회수', command:'fetch REQUEST_ID', screen:'benchmark-result', preview:false},
] as const;

// Native 1440×3120 captures remain intact. Only the empty camera preview is
// composited with the scenic footage used by the earlier film.
const NativeScreen = ({screen,preview}:{screen:string;preview:boolean}) => {
  const native=['photo','video','streams'].includes(screen);
  return <div style={{position:'absolute',left:1050,top:20,width:512,height:1040,overflow:'hidden',borderRadius:24}}>
    <div style={{position:'absolute',width:512,height:3120*512/1440,top:-40}}>
      <Img src={staticFile(native?`media/automation/${screen}-native.png`:`screenshots/${screen}.png`)} style={{position:'absolute',width:512,height:3120*512/1440}}/>
      {preview && <SensorPreview y={530*512/1440} width={512} height={(screen==='video'?810:1530)*512/1440}/>}
      {screen==='benchmark-running' && <SensorPreview x={80*512/1440} y={239*512/1440} width={1280*512/1440} height={640*512/1440}/>}
    </div>
  </div>;
};

export const Automation = () => {
  const frame=useCurrentFrame(),{fps}=useVideoConfig(),t=frame/fps;
  const beat=beats.reduce<(typeof beats)[number]>((current,next)=>t>=next.at?next:current,beats[0]);
  const appear=interpolate(frame,[beat.at*fps,(beat.at+.2)*fps],[0,1],{extrapolateLeft:'clamp',extrapolateRight:'clamp'});
  return <AbsoluteFill style={{background:'linear-gradient(135deg,#171a1e,#07090c)',fontFamily:font,color:'#ecede9'}}>
    <div style={{position:'absolute',left:135,top:82,fontSize:26,color:'#87929e'}}>hal-camera / Automation</div>
    <div style={{position:'absolute',left:130,top:155,fontSize:33,color:accent}}>AI agent → halcam-cli 스킬 → CLI</div>
    <div style={{position:'absolute',left:130,top:290,width:850,fontSize:68,fontWeight:600,opacity:appear}}>{beat.title}</div>
    <div style={{position:'absolute',left:135,top:390,fontSize:29,color:'#bdc9d4'}}>{beat.note}</div>
    <div style={{position:'absolute',left:130,top:495,width:835,height:360,border:'2px solid #6197bf',borderRadius:16,background:'#080f16',overflow:'hidden'}}>
      <div style={{padding:'22px 27px',fontSize:25,background:'#1c3041',display:'flex',justifyContent:'space-between'}}><span>AI AGENT / SHELL</span><span style={{color:'#9fdbb7'}}>● CLI 실행 중</span></div>
      <div style={{padding:'27px',fontFamily:'Consolas, monospace'}}>
        <div style={{fontSize:25,color:'#a1b6c7',marginBottom:24}}>adb shell sh /data/local/tmp/halcam</div>
        <div style={{fontSize:36,lineHeight:1.5,color:'#c2e4ff',whiteSpace:'pre-line'}}><span style={{color:'#9fdbb7'}}>$ </span>{beat.command}</div>
      </div>
    </div>
    <div style={{position:'absolute',left:135,top:902,fontSize:27,color:t>=16?'#a6dcbc':'#a5bed2'}}>{t>=16?'AI agent가 결과 파일을 PC로 가져옵니다.':'명령 실행 → 실제 앱 화면'}</div>
    <div style={{position:'absolute',left:135,top:990,fontSize:17,color:'#77838e'}}>작업 흐름 재현 · 실제 앱 캡처 · 프리뷰 합성</div>
    <Sequence name="실제 앱 화면" premountFor={fps}><NativeScreen screen={beat.screen} preview={beat.preview}/></Sequence>
    {t<5&&<AgentIntro/>}
    {t<.3&&<CanvasImage src={staticFile('media/automation/bridge.png')} premountFor={fps} style={{position:'absolute',inset:0,width:1920,height:1080,opacity:interpolate(frame,[0,.3*fps],[1,0],{extrapolateRight:'clamp'})}}/>}
  </AbsoluteFill>;
};
