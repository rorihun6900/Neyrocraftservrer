using System;
using System.IO;

// Samantha's voice colour: a touch higher and brighter, a soft "AI" chorus and a small room reverb.
public static class SamanthaFx
{
    public static void Process(string inWav, string outWav, double semitones)
    {
        int sr; short ch;
        double[] x = Read(inWav, out sr, out ch);

        // 1) gentle pitch/formant lift by resampling
        double ratio = Math.Pow(2, semitones / 12.0);
        int n = (int)(x.Length / ratio);
        double[] y = new double[n];
        for (int i = 0; i < n; i++)
        {
            double p = i * ratio; int a = (int)p; double f = p - a;
            double s0 = a < x.Length ? x[a] : 0, s1 = a + 1 < x.Length ? x[a + 1] : 0;
            y[i] = s0 + (s1 - s0) * f;
        }

        // 2) brightness: add a bit of the high band (presence, "airy" female timbre)
        double lp = 0;
        for (int i = 0; i < n; i++) { lp += 0.18 * (y[i] - lp); y[i] = y[i] + 0.35 * (y[i] - lp); }

        // 3) soft chorus — the synthetic shimmer of a ship AI
        double[] c = new double[n];
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)sr;
            double d1 = (0.018 + 0.003 * Math.Sin(2 * Math.PI * 0.6 * t)) * sr;
            double d2 = (0.027 + 0.004 * Math.Sin(2 * Math.PI * 0.43 * t + 1.3)) * sr;
            c[i] = y[i] + 0.16 * Tap(y, i - d1) + 0.12 * Tap(y, i - d2);
        }

        // 4) small room reverb, plus a tail
        int tail = (int)(0.6 * sr);
        double[] o = new double[n + tail];
        Array.Copy(c, o, n);
        int[] combs = { 1116, 1188, 1277, 1356 };
        double[] wet = new double[o.Length];
        foreach (int c0 in combs)
        {
            int len = c0 * sr / 44100; double[] buf = new double[len]; int p = 0; double damp = 0;
            for (int i = 0; i < o.Length; i++)
            {
                double outv = buf[p]; damp = outv * 0.6 + damp * 0.4;
                buf[p] = o[i] + damp * 0.72; p = (p + 1) % len; wet[i] += outv;
            }
        }
        for (int i = 0; i < o.Length; i++) o[i] = o[i] + 0.16 * wet[i] / combs.Length;

        // 5) normalize to -3 dBFS, short fades
        double peak = 1e-9; foreach (double v in o) peak = Math.Max(peak, Math.Abs(v));
        double g = Math.Pow(10, -3 / 20.0) / peak;
        int fade = sr / 200;
        for (int i = 0; i < o.Length; i++)
        {
            double e = Math.Min(1.0, Math.Min(i, o.Length - 1 - i) / (double)fade);
            o[i] *= g * e;
        }
        Write(outWav, o, sr);
    }

    static double Tap(double[] y, double pos)
    {
        if (pos < 0) return 0; int a = (int)pos; double f = pos - a;
        if (a + 1 >= y.Length) return 0;
        return y[a] + (y[a + 1] - y[a]) * f;
    }

    static double[] Read(string path, out int sr, out short ch)
    {
        byte[] b = File.ReadAllBytes(path);
        int pos = 12; sr = 48000; ch = 1; int bits = 16; int dataPos = -1, dataLen = 0;
        while (pos + 8 <= b.Length)
        {
            string id = System.Text.Encoding.ASCII.GetString(b, pos, 4); int len = BitConverter.ToInt32(b, pos + 4);
            if (id == "fmt ") { ch = BitConverter.ToInt16(b, pos + 10); sr = BitConverter.ToInt32(b, pos + 12); bits = BitConverter.ToInt16(b, pos + 22); }
            if (id == "data") { dataPos = pos + 8; dataLen = Math.Min(len, b.Length - dataPos); break; }
            pos += 8 + len + (len & 1);
        }
        if (bits != 16) throw new Exception("16-bit WAV expected");
        int frames = dataLen / (2 * ch);
        double[] x = new double[frames];
        for (int i = 0; i < frames; i++)
        {
            double s = 0;
            for (int k = 0; k < ch; k++) s += BitConverter.ToInt16(b, dataPos + (i * ch + k) * 2) / 32768.0;
            x[i] = s / ch;
        }
        return x;
    }

    static void Write(string path, double[] o, int sr)
    {
        using (var w = new BinaryWriter(File.Create(path)))
        {
            int len = o.Length * 2;
            w.Write(new[]{'R','I','F','F'}); w.Write(36 + len); w.Write(new[]{'W','A','V','E'});
            w.Write(new[]{'f','m','t',' '}); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(sr); w.Write(sr * 2); w.Write((short)2); w.Write((short)16);
            w.Write(new[]{'d','a','t','a'}); w.Write(len);
            foreach (double v in o) w.Write((short)Math.Round(Math.Max(-1, Math.Min(1, v)) * 32767));
        }
    }
}
