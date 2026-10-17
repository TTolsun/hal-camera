import {AbsoluteFill, Composition, Sequence, staticFile, useVideoConfig} from 'remotion';
import {Audio, Video} from '@remotion/media';
import {Automation} from './Automation';
import {OpeningToolPreviews} from './SensorPreview';

const Ending = () => <AbsoluteFill style={{background:'#0c1015',color:'#ecede9',fontFamily:'Segoe UI, Malgun Gothic, sans-serif',justifyContent:'center',alignItems:'center'}}>
  <div style={{fontSize:108,fontWeight:600}}>hal-camera</div>
  <div style={{fontSize:38,marginTop:32,color:'#b8cee0'}}>촬영부터 분석·검증까지.</div>
</AbsoluteFill>;

const Film = () => {
  const {fps}=useVideoConfig();
  return <AbsoluteFill style={{background:'#0c1015'}}>
    <Video src={staticFile('media/camera-hal-reviewed-v8.mp4')} durationInFrames={40*fps} premountFor={fps}/>
    <OpeningToolPreviews/>
    <Sequence from={40*fps} durationInFrames={18*fps} premountFor={fps} name="프롬프트에서 카메라 제어와 결과 회수까지"><Automation/></Sequence>
    <Sequence from={58*fps} durationInFrames={2*fps} premountFor={fps} name="브랜드 엔딩"><Ending/></Sequence>
    <Audio src={staticFile('media/automation/soundtrack.wav')} from={40*fps} durationInFrames={20*fps} premountFor={fps}/>
  </AbsoluteFill>;
};

const Tail = () => {
  const {fps}=useVideoConfig();
  return <AbsoluteFill>
    <Sequence durationInFrames={18*fps} premountFor={fps}><Automation/></Sequence>
    <Sequence from={18*fps} durationInFrames={2*fps} premountFor={fps}><Ending/></Sequence>
    <Audio src={staticFile('media/automation/soundtrack.wav')} premountFor={fps}/>
  </AbsoluteFill>;
};

export const Root = () => <>
  <Composition id="HALCamera60" component={Film} durationInFrames={1440} fps={24} width={1920} height={1080}/>
  <Composition id="Automation18" component={Automation} durationInFrames={432} fps={24} width={1920} height={1080}/>
  <Composition id="AutomationTail20" component={Tail} durationInFrames={480} fps={24} width={1920} height={1080}/>
</>;
