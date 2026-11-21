import math, wave, os
import numpy as np
sr=44100
out=os.path.dirname(__file__)
for track, notes in enumerate(([57,60,64,67,64,60,55,59,62,67,62,59], [50,57,62,65,62,57,53,57,60,65,60,57]),1):
    duration=36
    t=np.arange(sr*duration)/sr
    result=np.zeros(len(t))
    for i in range(48):
        start=i*0.75
        length=1.4
        n=int(sr*length); local=np.arange(n)/sr
        freq=440*2**((notes[i%len(notes)]-69)/12)
        env=np.minimum(local/0.08,1)*np.exp(-local*2.1)
        sig=(np.sin(2*np.pi*freq*local)+0.18*np.sin(4*np.pi*freq*local))*env*0.18
        a=int(start*sr); end=min(len(result),a+n)
        result[a:end]+=sig[:end-a]
    result+=0.035*np.sin(2*np.pi*(110 if track==1 else 87.3)*t)*(0.5+0.5*np.sin(2*np.pi*t/12))
    result*=np.minimum(np.minimum(t/2,(duration-t)/2),1)
    data=(np.clip(result,-1,1)*32767).astype('<i2')
    with wave.open(os.path.join(out,'survival_%d.wav'%track),'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(sr); f.writeframes(data.tobytes())
