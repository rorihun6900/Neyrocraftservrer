"""Five original electronic instrumentals for Neyro-City, 60 seconds each."""
import os, wave
import numpy as np
sr=44100; duration=60; count=sr*duration; out=os.path.dirname(__file__)
styles=[(108,[45,41,48,43],[0,7,12,7,3,7,10,7]),(122,[40,36,43,38],[0,0,7,10,12,7,3,7]),(96,[50,46,41,48],[0,3,7,10,12,10,7,3]),(132,[43,39,46,41],[0,7,3,10,0,12,7,10]),(114,[48,43,45,41],[0,12,7,3,10,7,5,7])]
for track,(bpm,progression,sequence) in enumerate(styles,1):
    rng=np.random.default_rng(1300+track); beat=60/bpm
    l=np.zeros(count,dtype=np.float32); r=np.zeros(count,dtype=np.float32)
    def add(start,sig,pan=0):
        pos=int(start*sr); length=min(len(sig),count-pos)
        if length>0:
            l[pos:pos+length]+=sig[:length]*(1-pan*.55)
            r[pos:pos+length]+=sig[:length]*(1+pan*.55)
    for i in range(int(duration/beat)):
        start=i*beat; root=progression[(i//8)%4]; bar=i//4
        if bar%16 not in (0,):
            u=np.arange(int(sr*.3))/sr
            add(start,.30*np.sin(2*np.pi*(48*u+18*(1-np.exp(-u*32))/32))*np.exp(-u*16))
            if i%4 in (1,3):
                u=np.arange(int(sr*.18))/sr; add(start,rng.normal(0,1,len(u))*np.exp(-u*27)*.075,.2)
        for j in (0,.5):
            u=np.arange(int(sr*.045))/sr
            add(start+j*beat,rng.normal(0,1,len(u))*np.exp(-u*90)*.026,(-1)**i*.5)
        f=440*2**((root+sequence[i%8]-69)/12); u=np.arange(int(sr*beat*.9))/sr
        env=np.minimum(u/.02,1)*np.exp(-u*4.2)
        bass=(np.sin(2*np.pi*f*u)*.12+(2*((u*f)%1)-1)*.045)*env
        add(start,bass)
        if i%2==0 and bar%16>=2:
            melody=[12,15,19,22,19,15,17,19,24,22,19,15,14,17,19,15]
            offset=melody[((i//2)+(track*3))%len(melody)]
            freq=440*2**((root+offset-69)/12); u=np.arange(int(sr*beat*1.6))/sr
            env=np.minimum(u/.06,1)*np.exp(-u*3.2)
            tone=(np.sin(2*np.pi*freq*u)+.24*np.sin(4*np.pi*freq*u))*env*.065
            pan=(-1)**(i//2)*.55; add(start,tone,pan); add(start+beat*.55,tone*.22,-pan)
        if i%8==0:
            u=np.arange(int(sr*beat*7.5))/sr; env=np.minimum(u/.7,1)*np.minimum((len(u)/sr-u)/.8,1)
            pad=np.zeros(len(u))
            for d in (0,3,7):
                f=440*2**((root+d+12-69)/12)
                pad+=np.sin(2*np.pi*f*u)+.35*np.sin(2*np.pi*f*1.003*u)
            add(start,pad*env*.012, .3 if track%2 else -.3)
    t=np.arange(count)/sr; fade=np.clip(np.minimum(t/2,(duration-t)/2),0,1)
    stereo=np.stack((l*fade,r*fade),axis=1); stereo=np.tanh(stereo*1.4)*.75
    with wave.open(os.path.join(out,'cyber_%d.wav'%track),'wb') as f:
        f.setnchannels(2); f.setsampwidth(2); f.setframerate(sr); f.writeframes((stereo*32767).astype('<i2').tobytes())
