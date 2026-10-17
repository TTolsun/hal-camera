import {AbsoluteFill, Img, Sequence, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {Audio, Video} from '@remotion/media';
import {SensorPreview} from './SensorPreview';

const font='Segoe UI, Malgun Gothic, sans-serif';
const ink='#ecede9';
const blue='#a6d4f6';

// A single subject per shot. At 305 CSS px, 56 px copy becomes 15.8 px.
const Heading=({label,title}:{label:string;title:string})=><div style={{position:'absolute',left:80,right:80,top:95}}>
  <div style={{fontSize:44,color:blue,marginBottom:24}}>{label}</div>
  <div style={{fontSize:84,fontWeight:600,lineHeight:1.2,whiteSpace:'pre-line'}}>{title}</div>
</div>;

type Screen='photo'|'video'|'streams'|'probe'|'cts-custom'|'cts-vendored'|'cts-running'|'benchmark-running'|'benchmark-result';
const AppShot=({screen,label,title,offset=100}:{screen:Screen;label:string;title:string;offset?:number})=>{
  const native=['photo','video','streams'].includes(screen);
  // Scale the original capture once. The window focuses on the relevant
  // controls; native typography, buttons and preview pixels are not redrawn.
  const s=960/1440;
  return <AbsoluteFill>
    <Heading label={label} title={title}/>
    <div style={{position:'absolute',left:60,top:330,width:960,height:1440,overflow:'hidden',borderRadius:24}}>
      <div style={{position:'absolute',width:960,height:3120*s,top:-offset}}>
        <Img src={staticFile(native?`media/automation/${screen}-native.png`:`screenshots/${screen}.png`)} style={{position:'absolute',width:960,height:3120*s}}/>
        {(screen==='photo'||screen==='video')&&<SensorPreview y={530*s} width={960} height={(screen==='video'?810:1530)*s}/>}
        {screen==='benchmark-running'&&<SensorPreview x={80*s} y={239*s} width={1280*s} height={640*s}/>}
        {screen==='cts-running'&&<SensorPreview x={80*s} y={882*s} width={1280*s} height={720*s}/>}
      </div>
    </div>
  </AbsoluteFill>;
};

const CliShot=({title,command,note}:{title:string;command:string;note:string})=><AbsoluteFill>
  <Heading label="AI AGENT / SHELL" title={title}/>
  <div style={{position:'absolute',left:80,right:80,top:490,padding:'46px 40px',border:'3px solid #6197bf',borderRadius:24,background:'#101e2b'}}>
    <div style={{fontSize:48,color:'#a6dfbd',marginBottom:48}}>● {command.startsWith('$ adb')?'Shell 실행':'halcam · 명령 인자'}</div>
    <div style={{fontFamily:'Consolas, monospace',fontSize:56,lineHeight:1.55,color:'#d4ecff',whiteSpace:'pre-wrap'}}>{command.startsWith('$ adb')?command:command.replace('$ ','')}</div>
  </div>
  <div style={{position:'absolute',left:80,right:80,top:1430,fontSize:56,lineHeight:1.5,color:blue,whiteSpace:'pre-line'}}>{note}</div>
  <div style={{position:'absolute',left:80,bottom:120,fontSize:40,color:'#95a6b5'}}>halcam-cli 스킬 · 작업 흐름 재현</div>
</AbsoluteFill>;

const Prompt=()=> <AbsoluteFill>
  <Heading label="Automation" title={'한 번의 요청으로.'}/>
  <div style={{position:'absolute',left:80,right:80,top:540,fontSize:76,lineHeight:1.55}}>
    카메라를 열고,<br/>설정을 바꾼 뒤<br/>검증해줘.
  </div>
  <div style={{position:'absolute',left:80,top:1340,fontSize:56,color:blue}}>AI agent에게 요청합니다.</div>
</AbsoluteFill>;

const Skill=()=> <AbsoluteFill>
  <Heading label="AI AGENT / READ" title={'스킬을 읽고.'}/>
  <div style={{position:'absolute',left:80,right:80,top:500,padding:40,background:'#152638',borderLeft:'6px solid #9ecffa',borderRadius:12}}>
    <div style={{fontSize:52,color:blue,marginBottom:50}}>halcam-cli 스킬</div>
    <div style={{fontSize:56,lineHeight:1.65,fontFamily:'Consolas, monospace'}}>skills/halcam-cli/<br/>SKILL.md</div>
    <div style={{fontSize:52,color:'#a6dfbd',marginTop:60}}>✓ 실행 방법 확인</div>
  </div>
  <div style={{position:'absolute',left:80,right:80,top:1320,fontSize:56,lineHeight:1.5}}>이제 Shell 도구로<br/>카메라를 제어합니다.</div>
</AbsoluteFill>;

// Preserve the existing animated PIP / Multi app footage, including its
// exact sensor images and gestures. Reframe only the phone rectangle.
const MultiShot=({multi=false}:{multi?:boolean})=>{
  const {fps}=useVideoConfig();
  const scale=900/482;
  return <AbsoluteFill>
    <Heading label={multi?'Multi Device':'PIP'} title={multi?'두 카메라를 함께.':'두 시점을 한 화면에.'}/>
    <div style={{position:'absolute',left:90,top:330,width:900,height:1440,overflow:'hidden',borderRadius:24}}>
      <Video src={staticFile('media/camera-hal-automation-v13.mp4')} trimBefore={(multi?36:32)*fps} muted premountFor={fps}
        style={{position:'absolute',width:1920*scale,height:1080*scale,left:-1013*scale,top:-21*scale-350}}/>
    </div>
  </AbsoluteFill>;
};

const Opening=()=>{
  const f=useCurrentFrame(),{fps}=useVideoConfig();
  return <AbsoluteFill>
    {f<7*fps?<Video src={staticFile('media/camera-hal-reviewed-v8.mp4')} muted objectFit="cover" style={{width:'100%',height:'100%'}}/>
      :<Sequence from={7*fps}><Video src={staticFile('media/automation/sunset.mp4')} muted objectFit="cover" style={{width:'100%',height:'100%'}}/></Sequence>}
    {f>=7*fps&&<div style={{position:'absolute',inset:0,background:'linear-gradient(transparent,#080e16a0)',display:'flex',flexDirection:'column',justifyContent:'flex-end',padding:'0 80px 240px'}}>
      <div style={{fontSize:100,fontWeight:600}}>Camera HAL.</div><div style={{fontSize:56,marginTop:36}}>촬영부터 분석·검증까지.</div>
    </div>}
  </AbsoluteFill>;
};

const Ending=()=> <AbsoluteFill style={{justifyContent:'center',alignItems:'center'}}>
  <div style={{fontSize:112,fontWeight:600}}>hal-camera</div>
  <div style={{fontSize:56,marginTop:48,color:blue}}>촬영부터 분석·검증까지.</div>
</AbsoluteFill>;

export const MobileFilm=()=>{
  const {fps}=useVideoConfig();
  return <AbsoluteFill style={{background:'#0c1015',color:ink,fontFamily:font}}>
    <Sequence durationInFrames={10*fps} name="도입부 원본 장면"><Opening/></Sequence>
    <Sequence from={10*fps} durationInFrames={3*fps} name="Callback"><AppShot screen="video" label="Callback" title="프레임의 흐름을 보다." offset={430}/></Sequence>
    <Sequence from={13*fps} durationInFrames={3*fps} name="Probe"><AppShot screen="probe" label="Probe" title="카메라 사양을 확인." offset={140}/></Sequence>
    <Sequence from={16*fps} durationInFrames={3*fps} name="Custom CTS"><AppShot screen="cts-running" label="Custom CTS" title="반복 동작을 검증." offset={220}/></Sequence>
    <Sequence from={19*fps} durationInFrames={3*fps} name="AOSP CTS"><AppShot screen="cts-vendored" label="AOSP CTS" title="테스트를 선택하고." offset={180}/></Sequence>
    <Sequence from={22*fps} durationInFrames={4*fps} name="Benchmark"><AppShot screen="benchmark-running" label="Benchmark" title="같은 조건으로 측정." offset={80}/></Sequence>
    <Sequence from={26*fps} durationInFrames={4*fps} name="PIP 원본 화면"><MultiShot/></Sequence>
    <Sequence from={30*fps} durationInFrames={4*fps} name="Multi 원본 화면"><MultiShot multi/></Sequence>
    <Sequence from={34*fps} durationInFrames={2*fps} name="사용자 요청"><Prompt/></Sequence>
    <Sequence from={36*fps} durationInFrames={3*fps} name="스킬 읽기"><Skill/></Sequence>
    <Sequence from={39*fps} durationInFrames={2*fps} name="CLI 카메라 열기"><CliShot title="CLI로 열고." command={'$ adb shell sh \\\n  /data/local/tmp/halcam \\\n  preview --camera 0'} note={'카메라 진입부터\n한 번의 요청으로.'}/></Sequence>
    <Sequence from={41*fps} durationInFrames={2*fps} name="실제 Photo 화면"><AppShot screen="photo" label="명령 실행 → 실제 앱" title="카메라가 열립니다." offset={430}/></Sequence>
    <Sequence from={43*fps} durationInFrames={2*fps} name="CLI 스트림 설정"><CliShot title="스트림을 바꾸고." command={'$ preview --camera 0\n  --jpeg-size\n  1920x1080'} note={'Preview · YUV · JPEG\n촬영 조건을 구성합니다.'}/></Sequence>
    <Sequence from={45*fps} durationInFrames={2*fps} name="실제 Streams 화면"><AppShot screen="streams" label="명령 실행 → 실제 앱" title="설정이 적용됩니다." offset={100}/></Sequence>
    <Sequence from={47*fps} durationInFrames={2*fps} name="CLI Video 전환"><CliShot title="모드를 전환하고." command={'$ preview --camera 0\n  --mode Video'} note={'Photo → Video\n같은 CLI로 전환합니다.'}/></Sequence>
    <Sequence from={49*fps} durationInFrames={2*fps} name="실제 Video 화면"><AppShot screen="video" label="명령 실행 → 실제 앱" title="Video로 전환됩니다." offset={430}/></Sequence>
    <Sequence from={51*fps} durationInFrames={2*fps} name="CLI Benchmark"><CliShot title="측정까지 실행." command={'$ benchmark run\n  --camera 0'} note={'Probe · CTS · Benchmark\n검증 도구도 CLI로.'}/></Sequence>
    <Sequence from={53*fps} durationInFrames={2*fps} name="실제 Benchmark 화면"><AppShot screen="benchmark-running" label="명령 실행 → 실제 앱" title="벤치마크 실행." offset={80}/></Sequence>
    <Sequence from={55*fps} durationInFrames={1.5*fps} name="CLI 결과 회수"><CliShot title="결과를 가져오고." command={'$ fetch REQUEST_ID'} note={'AI agent가 PC로\n결과 파일을 회수합니다.'}/></Sequence>
    <Sequence from={56.5*fps} durationInFrames={1.5*fps} name="실제 결과 화면"><AppShot screen="benchmark-result" label="Benchmark 결과" title="결과를 확인합니다." offset={140}/></Sequence>
    <Sequence from={58*fps} durationInFrames={2*fps} name="앱 브랜드 엔딩"><Ending/></Sequence>
    <Audio src={staticFile('media/camera-hal-automation-v13.mp4')} premountFor={fps}/>
  </AbsoluteFill>;
};
