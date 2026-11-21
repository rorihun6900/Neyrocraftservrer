using System;
using System.IO;

// Trailer soundtrack (original, synthesised): space pad intro, a riser into the logo drop at 8 s,
// a driving 120 BPM electronic section, a final impact at 36 s; Samantha's lines mixed on top
// with the music ducked under her voice.
public static class TrailerAudio
{
    const int SR = 44100;
    public const double LEN = 43.0;
    static double[] L, R;

    static void Add(double t, double l, double r)
    {
        int i = (int)(t * SR);
        if (i < 0 || i >= L.Length) return;
        L[i] += l; R[i] += r;
    }

    static double Saw(double ph) { ph -= Math.Floor(ph); return ph * 2 - 1; }
    static double Sq(double ph, double w) { ph -= Math.Floor(ph); return ph < w ? 1 : -1; }

    static double Note(int midi) { return 440.0 * Math.Pow(2, (midi - 69) / 12.0); }

    // chords every 2 s: Am F C G
    static readonly int[][] CH = { new[] { 57, 60, 64 }, new[] { 53, 57, 60 }, new[] { 48, 52, 55 }, new[] { 55, 59, 62 } };
    static int[] Chord(double t) { return CH[((int)Math.Floor(t / 2.0)) % 4]; }

    public static void Build(string voDir, string outWav)
    {
        int n = (int)(LEN * SR);
        L = new double[n]; R = new double[n];
        var rnd = new Random(1);

        // ---- pad: detuned saws, slow filter, all the way through (quieter in the middle)
        double[] lp = new double[6];
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            int[] c = Chord(t);
            double s = 0;
            for (int v = 0; v < 3; v++)
            {
                double f = Note(c[v]);
                s += Saw(f * t * 1.003) + Saw(f * t * 0.997) + Saw(f * 0.5 * t);
            }
            s /= 9;
            double cut = 0.02 + 0.03 * (0.5 + 0.5 * Math.Sin(t * 0.7));
            lp[0] += cut * (s - lp[0]); lp[1] += cut * (lp[0] - lp[1]);
            double env = Math.Min(1, t / 3.0);
            double vol = t < 8 ? 1.0 : t < 36 ? 0.3 : 0.8 * Math.Max(0, 1 - (t - 38) / 5.0);
            double chordEdge = (t % 2.0) < 0.05 ? (t % 2.0) / 0.05 : 1;
            double x = lp[1] * env * vol * chordEdge;
            L[i] += x * (1 + 0.2 * Math.Sin(t * 0.9)); R[i] += x * (1 - 0.2 * Math.Sin(t * 0.9));
        }

        // ---- riser 4..8 s and a sub drop
        double b1 = 0, b2 = 0;
        for (double t = 4; t < 8; t += 1.0 / SR)
        {
            double k = (t - 4) / 4;
            double a = 2 * Math.PI * (300 + 5000 * k * k) / SR;
            double x = rnd.NextDouble() * 2 - 1;
            b1 += a * (x - b1 - b2 * 0.5); b2 += a * b1;
            double s = b2 * 0.7 * k * k + Math.Sin(2 * Math.PI * (200 + 800 * k * k) * (t - 4)) * 0.16 * k;
            Add(t, s, s);
        }

        // ---- impacts
        foreach (double at in new[] { 8.0, 36.0 }) Impact(at, rnd);
        // whooshes at the scene cuts
        foreach (double at in new[] { 11.5, 15, 18.5, 22, 25.5, 29, 32.5 }) Whoosh(at - 0.25, rnd);

        // ---- drums, bass and arp 8..36 s
        double beat = 0.5;
        for (double t = 8; t < 36 - 1e-6; t += beat)
        {
            int b = (int)Math.Round((t - 8) / beat);
            Kick(t);
            if (b % 2 == 1) Snare(t, rnd);
            Hat(t + beat / 2, rnd, b % 4 == 3);
            Hat(t, rnd, false);
        }
        // bass: eighth notes on the chord root, an octave jump every 4th
        for (double t = 8; t < 36 - 1e-6; t += 0.25)
        {
            int root = Chord(t)[0] - 24;
            int step = (int)Math.Round((t - 8) / 0.25);
            Bass(t, Note(root + (step % 4 == 3 ? 12 : 0)), 0.22);
        }
        // arp: 16ths across the chord, from 4 s (quiet) and full from 8 s
        int[] pattern = { 0, 1, 2, 1, 0, 2, 1, 2 };
        for (double t = 4; t < 36 - 1e-6; t += 0.125)
        {
            int s = (int)Math.Round((t - 4) / 0.125);
            int[] c = Chord(t);
            int midi = c[pattern[s % 8]] + 12 + (s % 16 >= 8 ? 12 : 0);
            double vol = t < 8 ? 0.05 + 0.05 * (t - 4) / 4 : 0.11;
            Pluck(t, Note(midi), vol, (s % 2 == 0) ? -0.4 : 0.4);
        }
        // a last soft arp over the end card
        for (double t = 36.5; t < 41; t += 0.25) Pluck(t, Note(Chord(t)[(int)(t * 4) % 3] + 24), 0.05 * (41 - t) / 4.5, 0);

        // ---- voice with ducking
        double[][] vo = { Wav(Path.Combine(voDir, "vo1.wav")), Wav(Path.Combine(voDir, "vo2.wav")), Wav(Path.Combine(voDir, "vo3.wav")) };
        double[] at2 = { 0.6, 29.2, 36.6 };
        double[] duck = new double[n];
        for (int i = 0; i < n; i++) duck[i] = 1;
        for (int k = 0; k < 3; k++)
        {
            int s0 = (int)(at2[k] * SR);
            int len = vo[k].Length;
            for (int i = Math.Max(0, s0 - SR / 5); i < Math.Min(n, s0 + len + SR / 3); i++) duck[i] = 0.45;
        }
        // smooth the ducking curve
        double d = 1;
        for (int i = 0; i < n; i++) { d += (duck[i] - d) * 0.0004; L[i] *= d; R[i] *= d; }
        for (int k = 0; k < 3; k++)
        {
            int s0 = (int)(at2[k] * SR);
            for (int i = 0; i < vo[k].Length && s0 + i < n; i++) { double v = vo[k][i] * 1.15; L[s0 + i] += v; R[s0 + i] += v; }
            // a soft echo of the voice, like a ship's intercom
            int del = (int)(0.19 * SR);
            for (int i = 0; i < vo[k].Length && s0 + i + del < n; i++) { double v = vo[k][i] * 0.18; L[s0 + i + del] += v; R[s0 + i + del] += v * 0.7; }
        }

        // ---- master: soft clip, fade in/out
        double peak = 1e-9;
        for (int i = 0; i < n; i++) peak = Math.Max(peak, Math.Max(Math.Abs(L[i]), Math.Abs(R[i])));
        double g = 2.0 / peak;
        using (var w = new BinaryWriter(File.Create(outWav)))
        {
            int bytes = n * 4;
            w.Write(new[] { 'R', 'I', 'F', 'F' }); w.Write(36 + bytes); w.Write(new[] { 'W', 'A', 'V', 'E' });
            w.Write(new[] { 'f', 'm', 't', ' ' }); w.Write(16); w.Write((short)1); w.Write((short)2); w.Write(SR); w.Write(SR * 4); w.Write((short)4); w.Write((short)16);
            w.Write(new[] { 'd', 'a', 't', 'a' }); w.Write(bytes);
            for (int i = 0; i < n; i++)
            {
                double t = i / (double)SR;
                double fade = Math.Min(1, t / 0.4) * Math.Min(1, (LEN - t) / 1.5);
                w.Write((short)(Math.Tanh(L[i] * g) * 0.92 * fade * 32767));
                w.Write((short)(Math.Tanh(R[i] * g) * 0.92 * fade * 32767));
            }
        }
    }

    static void Kick(double t0)
    {
        double ph = 0;
        for (int i = 0; i < SR * 0.4; i++)
        {
            double t = i / (double)SR;
            double f = 45 + 95 * Math.Exp(-t * 28);
            ph += f / SR;
            double s = Math.Sin(2 * Math.PI * ph) * Math.Exp(-t * 7) * 0.9 + (i < 60 ? 0.3 * (1 - i / 60.0) : 0);
            Add(t0 + t, s, s);
        }
    }

    static void Snare(double t0, Random r)
    {
        double hp = 0, prev = 0;
        for (int i = 0; i < SR * 0.22; i++)
        {
            double t = i / (double)SR;
            double x = r.NextDouble() * 2 - 1;
            hp = 0.85 * (hp + x - prev); prev = x;
            double s = hp * Math.Exp(-t * 16) * 0.35 + Math.Sin(2 * Math.PI * 190 * t) * Math.Exp(-t * 25) * 0.3;
            Add(t0 + t, s, s * 0.9);
        }
    }

    static void Hat(double t0, Random r, bool open)
    {
        double hp = 0, prev = 0, dec = open ? 14 : 55;
        for (int i = 0; i < SR * (open ? 0.2 : 0.06); i++)
        {
            double t = i / (double)SR;
            double x = r.NextDouble() * 2 - 1;
            hp = 0.6 * (hp + x - prev); prev = x;
            double s = hp * Math.Exp(-t * dec) * 0.12;
            Add(t0 + t, s * 0.7, s);
        }
    }

    static void Bass(double t0, double f, double vol)
    {
        double lpv = 0;
        for (int i = 0; i < SR * 0.24; i++)
        {
            double t = i / (double)SR;
            double x = Saw(f * t) * 0.7 + Math.Sin(2 * Math.PI * f * t) * 0.6;
            lpv += (0.06 + 0.1 * Math.Exp(-t * 20)) * (x - lpv);
            double env = Math.Min(1, t / 0.004) * Math.Exp(-t * 6);
            Add(t0 + t, lpv * env * vol, lpv * env * vol);
        }
    }

    static void Pluck(double t0, double f, double vol, double pan)
    {
        double lpv = 0;
        for (int i = 0; i < SR * 0.35; i++)
        {
            double t = i / (double)SR;
            double x = Sq(f * t, 0.3) * 0.6 + Saw(f * t * 1.004) * 0.4;
            lpv += (0.05 + 0.4 * Math.Exp(-t * 18)) * (x - lpv);
            double s = lpv * Math.Exp(-t * 9) * vol;
            Add(t0 + t, s * (1 - pan), s * (1 + pan));
            // delay echo
            Add(t0 + t + 0.375, s * 0.35 * (1 + pan), s * 0.35 * (1 - pan));
        }
    }

    static void Impact(double t0, Random r)
    {
        double lpv = 0;
        for (int i = 0; i < SR * 2.5; i++)
        {
            double t = i / (double)SR;
            double boom = Math.Sin(2 * Math.PI * (38 + 40 * Math.Exp(-t * 6)) * t) * Math.Exp(-t * 1.8) * 1.1;
            double x = r.NextDouble() * 2 - 1;
            lpv += 0.3 * (x - lpv);
            double crash = (x - lpv) * Math.Exp(-t * 1.6) * 0.3;
            Add(t0 + t, boom + crash, boom + crash * 0.9);
        }
    }

    static void Whoosh(double t0, Random r)
    {
        double b1 = 0, b2 = 0;
        for (int i = 0; i < SR * 0.5; i++)
        {
            double t = i / (double)SR, k = t / 0.5;
            double a = 2 * Math.PI * (500 + 4000 * k) / SR;
            double x = r.NextDouble() * 2 - 1;
            b1 += a * (x - b1 - b2 * 0.6); b2 += a * b1;
            double env = Math.Sin(Math.PI * k);
            Add(t0 + t, b2 * env * 0.18 * (1 - k), b2 * env * 0.18 * k);
        }
    }

    /** Reads a 16-bit mono WAV and resamples it to 44.1 kHz. */
    static double[] Wav(string path)
    {
        byte[] b = File.ReadAllBytes(path);
        int rate = BitConverter.ToInt32(b, 24);
        int pos = 12, dataAt = -1, dataLen = 0;
        while (pos < b.Length - 8)
        {
            string id = System.Text.Encoding.ASCII.GetString(b, pos, 4);
            int len = BitConverter.ToInt32(b, pos + 4);
            if (id == "data") { dataAt = pos + 8; dataLen = len; break; }
            pos += 8 + len;
        }
        int cnt = Math.Min(dataLen, b.Length - dataAt) / 2;
        var src = new double[cnt];
        for (int i = 0; i < cnt; i++) src[i] = BitConverter.ToInt16(b, dataAt + i * 2) / 32768.0;
        int outN = (int)((long)cnt * SR / rate);
        var o = new double[outN];
        for (int i = 0; i < outN; i++)
        {
            double sp = i * (double)rate / SR; int a = (int)sp; double f = sp - a;
            o[i] = a + 1 < cnt ? src[a] * (1 - f) + src[a + 1] * f : 0;
        }
        return o;
    }
}
