using System;
using System.IO;

// NeyroCraft retro sounds: short, crunchy, low-fi sounds synthesised from scratch (no samples)
// to give the 1.0 world an early-2010s feel: block digging/placing/steps and the player's "oof".
public static class RetroSounds
{
    const int SR = 44100;

    public static void Generate(string dir)
    {
        Directory.CreateDirectory(dir);
        for (int v = 1; v <= 3; v++)
        {
            int s = v * 101;
            Write(dir, "stone_dig" + v, Crunch(s, 0.16, 2600, 0.9, 0.012, 0.0));
            Write(dir, "stone_step" + v, Crunch(s + 1, 0.07, 2200, 0.5, 0.008, 0.0));
            Write(dir, "grass_dig" + v, Rustle(s + 2, 0.18, 1400, 0.85));
            Write(dir, "grass_step" + v, Rustle(s + 3, 0.09, 1200, 0.5));
            Write(dir, "wood_dig" + v, Knock(s + 4, 0.16, 170 + v * 15, 0.9));
            Write(dir, "wood_step" + v, Knock(s + 5, 0.08, 210 + v * 10, 0.5));
            Write(dir, "gravel_dig" + v, Crunch(s + 6, 0.2, 1300, 0.9, 0.004, 0.6));
            Write(dir, "gravel_step" + v, Crunch(s + 7, 0.09, 1200, 0.55, 0.004, 0.6));
            Write(dir, "sand_dig" + v, Rustle(s + 8, 0.17, 700, 0.75));
            Write(dir, "sand_step" + v, Rustle(s + 9, 0.08, 650, 0.45));
        }
        for (int v = 1; v <= 3; v++) Write(dir, "oof" + v, Oof(v));
    }

    // ------------------------------------------------------------ voices

    /** stone/gravel: noise through a resonant band-pass, plus grain impulses for gravel */
    static double[] Crunch(int seed, double len, double freq, double vol, double click, double grains)
    {
        var r = new Random(seed); int n = (int)(len * SR); var o = new double[n];
        var bp = new Biquad(freq * (0.85 + r.NextDouble() * 0.3), 1.6);
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double env = Math.Exp(-t / (len * 0.28));
            double x = r.NextDouble() * 2 - 1;
            if (grains > 0 && r.NextDouble() < 0.02) x *= 6 * grains;
            double y = bp.Run(x) * env;
            if (t < click) y += (r.NextDouble() * 2 - 1) * (1 - t / click) * 0.8;
            o[i] = y * vol;
        }
        return Crush(Norm(o, vol));
    }

    /** grass/sand: low-passed noise with crackles and a soft attack */
    static double[] Rustle(int seed, double len, double cutoff, double vol)
    {
        var r = new Random(seed); int n = (int)(len * SR); var o = new double[n];
        double lp = 0, a = 1 - Math.Exp(-2 * Math.PI * cutoff / SR);
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double env = Math.Min(1, t / 0.008) * Math.Exp(-t / (len * 0.35));
            double x = r.NextDouble() * 2 - 1;
            if (r.NextDouble() < 0.01) x *= 4;
            lp += a * (x - lp);
            o[i] = lp * env;
        }
        return Crush(Norm(o, vol));
    }

    /** wood: a hollow knock (decaying low tones) with a bit of noise */
    static double[] Knock(int seed, double len, double f0, double vol)
    {
        var r = new Random(seed); int n = (int)(len * SR); var o = new double[n];
        var bp = new Biquad(900, 1.2);
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double env = Math.Exp(-t / (len * 0.25));
            double tone = Math.Sin(2 * Math.PI * f0 * t) + 0.5 * Math.Sin(2 * Math.PI * f0 * 2.37 * t) * Math.Exp(-t / 0.02);
            double noise = bp.Run(r.NextDouble() * 2 - 1) * Math.Exp(-t / 0.015);
            o[i] = (tone * 0.8 + noise * 0.6) * env;
        }
        return Crush(Norm(o, vol));
    }

    /** the player's "oof": a short male-like vowel "u" sliding down, ending in a breathy "f" */
    static double[] Oof(int v)
    {
        double len = 0.32; int n = (int)(len * SR); var o = new double[n];
        var r = new Random(700 + v);
        var f1 = new Biquad(330, 6); var f2 = new Biquad(820, 7); var f3 = new Biquad(2300, 9);
        double phase = 0, start = 150 + v * 9;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double f0 = start - 55 * (t / len) + 4 * Math.Sin(2 * Math.PI * 7 * t);
            phase += f0 / SR; if (phase >= 1) phase -= 1;
            double pulse = phase < 0.35 ? Math.Sin(Math.PI * phase / 0.35) : 0;   // glottal-like pulse
            double voiced = f1.Run(pulse) * 1.0 + f2.Run(pulse) * 0.55 + f3.Run(pulse) * 0.18;
            double vEnv = Math.Min(1, t / 0.012) * (t < 0.2 ? 1 : Math.Exp(-(t - 0.2) / 0.03));
            double fEnv = t < 0.17 ? 0 : Math.Exp(-(t - 0.17) / 0.06) * Math.Min(1, (t - 0.17) / 0.02);
            double fric = (r.NextDouble() * 2 - 1) * fEnv * 0.25;
            o[i] = voiced * vEnv + fric;
        }
        return Crush(Norm(o, 0.95));
    }

    // ------------------------------------------------------------ dsp

    class Biquad
    {
        double b0, b1, b2, a1, a2, x1, x2, y1, y2;
        public Biquad(double f, double q)
        {
            double w = 2 * Math.PI * f / SR, al = Math.Sin(w) / (2 * q), c = Math.Cos(w), a0 = 1 + al;
            b0 = al / a0; b1 = 0; b2 = -al / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
        }
        public double Run(double x)
        {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y; return y;
        }
    }

    static double[] Norm(double[] o, double peakTo)
    {
        double p = 1e-9; foreach (double v in o) p = Math.Max(p, Math.Abs(v));
        for (int i = 0; i < o.Length; i++) o[i] = o[i] / p * peakTo;
        int fade = Math.Min(o.Length / 4, 200);
        for (int i = 0; i < fade; i++) o[o.Length - 1 - i] *= i / (double)fade;
        return o;
    }

    /** low-fi: 8-bit-ish quantisation and sample-and-hold at ~16 kHz */
    static double[] Crush(double[] o)
    {
        double held = 0; int hold = SR / 16000;
        for (int i = 0; i < o.Length; i++)
        {
            if (i % Math.Max(1, hold) == 0) held = Math.Round(o[i] * 48) / 48;
            o[i] = held;
        }
        return o;
    }

    static void Write(string dir, string name, double[] o)
    {
        using (var w = new BinaryWriter(File.Create(Path.Combine(dir, name + ".wav"))))
        {
            int len = o.Length * 2;
            w.Write(new[]{'R','I','F','F'}); w.Write(36 + len); w.Write(new[]{'W','A','V','E'});
            w.Write(new[]{'f','m','t',' '}); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(SR); w.Write(SR * 2); w.Write((short)2); w.Write((short)16);
            w.Write(new[]{'d','a','t','a'}); w.Write(len);
            foreach (double v in o) w.Write((short)Math.Round(Math.Max(-1, Math.Min(1, v)) * 32767));
        }
    }
}
