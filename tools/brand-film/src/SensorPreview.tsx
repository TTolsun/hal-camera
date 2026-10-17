import {Easing, interpolate, Loop, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {Video} from '@remotion/media';

/** Image content from the opening: piano, prism, ripples, and sunset.
 * The opening's title and phone UI are excluded from the sensor image. */
export const SensorPreview=({x=0,y=0,width,height}:{x?:number;y?:number;width:number;height:number})=>{
  const {fps}=useVideoConfig();
  return <div style={{position:'absolute',left:x,top:y,width,height,overflow:'hidden'}}>
    <Loop durationInFrames={12*fps}>
      <Video src={staticFile('media/automation/opening-sensor.mp4')} muted premountFor={fps} objectFit="cover" style={{position:'absolute',width:'100%',height:'100%'}}/>
    </Loop>
  </div>;
};

// Keep the approved first forty seconds and replace only the CTS/benchmark
// sensor rectangles. Match NativeUi.Slide's easing, clipping, and movement so
// outgoing previews remain covered by the incoming native screen.
const ToolPreview=({start,end,y,height}:{start:number;end:number;y:number;height:number})=>{
  const frame=useCurrentFrame(),{fps}=useVideoConfig(),t=frame/fps;
  if(t<start||t>=end+.32)return null;
  const ease=(at:number)=>interpolate(frame,[at*fps,(at+.32)*fps],[0,1],{extrapolateLeft:'clamp',extrapolateRight:'clamp',easing:Easing.bezier(.22,1,.36,1)});
  const enter=ease(start),leave=ease(end);
  return <div style={{position:'absolute',left:1013,top:21,width:482,height:1044,overflow:'hidden',borderRadius:29}}>
    <div style={{position:'absolute',width:720,height:1560,scale:482/720,transformOrigin:'top left'}}>
      <div style={{position:'absolute',width:720*(1-leave),height:1560,overflow:'hidden'}}>
        <SensorPreview x={40+720*(1-enter)-90*leave} y={y} width={640} height={height}/>
      </div>
    </div>
  </div>;
};

export const OpeningToolPreviews=()=> <>
  <ToolPreview start={21.05} end={22.5} y={441} height={360}/>
  <ToolPreview start={24.35} end={26} y={441} height={360}/>
  <ToolPreview start={27.2} end={29.1} y={119} height={320}/>
</>;
