using System;
using System.IO;

// Energy-sword sounds synthesised from scratch: hum, ignite/retract, swings, clashes, hits,
// and the abilities (lightning, push, dash, double jump). No recordings are used.
public static class SaberSounds
{
    const int SR = 44100;
    static Random R = new Random(42);

    public static void Generate(string dir)
    {
        Directory.CreateDirectory(dir);
        Write(dir, "saber_hum", Hum(2.0, 93));
        Write(dir, "saber_on", Ignite(true));
        Write(dir, "saber_off", Ignite(false));
        for (int v = 1; v <= 3; v++) Write(dir, "saber_swing" + v, Swing(0.38 + v * 0.06, 0.45 + v * 0.15, v));
        for (int v = 1; v <= 2; v++) Write(dir, "saber_clash" + v, Clash(v));
        Write(dir, "saber_hit", Hit());
        Write(dir, "saber_zap", Zap(1.25));
        Write(dir, "saber_push", Push());
        Write(dir, "saber_dash", Whoosh(0.36, 500, 2200, 0.9));
        Write(dir, "saber_jump", Whoosh(0.3, 300, 1400, 0.7));
    }

    static double Saw(double ph) { ph -= Math.Floor(ph); return ph * 2 - 1; }

    /** the steady energy hum: two slightly detuned saws, low-passed, with a slow wobble (loops cleanly) */
    static double[] Hum(double len, double f0)
    {
        int n = (int)(len * SR); var o = new double[n]; double lp = 0, lp2 = 0, nz = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double x = Saw(f0 * t) + 0.7 * Saw(f0 * 1.0215 * t) + 0.35 * Math.Sin(2 * Math.PI * 2 * f0 * t);
            nz += 0.02 * ((R.NextDouble() * 2 - 1) - nz);
            x += nz * 0.6;
            lp += 0.09 * (x - lp); lp2 += 0.09 * (lp - lp2);
            o[i] = lp2 * (1 + 0.1 * Math.Sin(2 * Math.PI * 3.5 * t));
        }
        return Norm(o, 0.8, false);
    }

    static double[] Ignite(bool on)
    {
        double len = 0.75; int n = (int)(len * SR); var o = new double[n]; double ph = 0, ph2 = 0, lp = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double sweep = on ? Math.Min(1, k / 0.45) : Math.Max(0, 1 - k / 0.55);
            double f = 35 + 160 * Math.Pow(sweep, 0.7) * (on && k > 0.45 ? 0.58 : 1);
            ph += f / SR; ph2 += f * 1.02 / SR;
            double x = Saw(ph) + 0.6 * Saw(ph2);
            if (R.NextDouble() < 0.004 * (1 - sweep * 0.5)) x += (R.NextDouble() * 2 - 1) * 4;    // crackle
            lp += 0.15 * (x - lp);
            double env = on ? Math.Min(1, t / 0.02) * (k > 0.8 ? (1 - k) / 0.2 : 1) : (1 - k) * Math.Min(1, t / 0.01);
            o[i] = lp * env;
        }
        return Norm(o, 0.9, true);
    }

    static double[] Swing(double len, double bend, int seed)
    {
        var r = new Random(seed * 17); int n = (int)(len * SR); var o = new double[n]; double ph = 0, ph2 = 0, lp = 0, bp1 = 0, bp2 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double env = Math.Sin(Math.PI * Math.Pow(k, 0.8));
            double f = 93 * (1 + bend * Math.Sin(Math.PI * k));                   // Doppler-like rise and fall
            ph += f / SR; ph2 += f * 1.02 / SR;
            double hum = Saw(ph) + 0.7 * Saw(ph2);
            lp += 0.12 * (hum - lp);
            double fc = 300 + 1600 * Math.Sin(Math.PI * k);
            double a = 2 * Math.PI * fc / SR, x = r.NextDouble() * 2 - 1;
            bp1 += a * (x - bp1 - bp2 * 0.6); bp2 += a * bp1;                     // swept noise (air)
            o[i] = (lp * 1.0 + bp2 * 0.5) * env;
        }
        return Norm(o, 0.9, true);
    }

    static double[] Clash(int v)
    {
        var r = new Random(100 + v); double len = 0.55; int n = (int)(len * SR); var o = new double[n];
        double[] fr = { 1180 + v * 70, 2690, 4130, 5510 };
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double ring = 0; for (int k = 0; k < fr.Length; k++) ring += Math.Sin(2 * Math.PI * fr[k] * t) * Math.Exp(-t / (0.16 / (k + 1))) / (k + 1);
            double burst = (r.NextDouble() * 2 - 1) * Math.Exp(-t / 0.012);
            double crack = r.NextDouble() < 0.03 * Math.Exp(-t / 0.15) ? (r.NextDouble() * 2 - 1) * 3 : 0;
            double sizzle = (r.NextDouble() * 2 - 1) * 0.25 * Math.Exp(-t / 0.2);
            o[i] = ring * 0.8 + burst * 1.2 + crack + sizzle;
        }
        return Norm(o, 0.95, true);
    }

    static double[] Hit()
    {
        double len = 0.5; int n = (int)(len * SR); var o = new double[n]; double hp = 0, prev = 0, ph = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double x = R.NextDouble() * 2 - 1;
            hp = 0.85 * (hp + x - prev); prev = x;                                    // bright sizzle
            ph += (140 - 60 * t / len) / SR;
            double env = Math.Min(1, t / 0.005) * Math.Exp(-t / 0.16);
            o[i] = (hp * (0.5 + 0.5 * R.NextDouble()) * 0.7 + Saw(ph) * 0.5) * env;
        }
        return Norm(o, 0.9, true);
    }

    static double[] Zap(double len)
    {
        int n = (int)(len * SR); var o = new double[n]; double lp = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double buzz = Math.Sign(Math.Sin(2 * Math.PI * 118 * t + 3 * Math.Sin(2 * Math.PI * 7 * t))) * 0.35;
            double crack = R.NextDouble() < 0.06 ? (R.NextDouble() * 2 - 1) * 2.5 : 0;
            double hiss = (R.NextDouble() * 2 - 1) * 0.4;
            lp += 0.5 * (buzz + crack + hiss - lp);
            double env = Math.Min(1, t / 0.02) * (t > len - 0.15 ? (len - t) / 0.15 : 1) * (0.75 + 0.25 * Math.Sin(2 * Math.PI * 11 * t));
            o[i] = lp * env;
        }
        return Norm(o, 0.9, true);
    }

    static double[] Push()
    {
        double len = 0.7; int n = (int)(len * SR); var o = new double[n]; double lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double env = Math.Sin(Math.PI * Math.Pow(k, 0.5));
            double a = 0.01 + 0.06 * k;
            lp += a * ((R.NextDouble() * 2 - 1) - lp); lp2 += a * (lp - lp2);
            double sub = Math.Sin(2 * Math.PI * (55 - 20 * k) * t) * Math.Exp(-t / 0.3);
            o[i] = (lp2 * 6 + sub * 0.8) * env;
        }
        return Norm(o, 0.95, true);
    }

    static double[] Whoosh(double len, double f0, double f1, double vol)
    {
        int n = (int)(len * SR); var o = new double[n]; double b1 = 0, b2 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double fc = f0 + (f1 - f0) * k, a = 2 * Math.PI * fc / SR;
            double x = R.NextDouble() * 2 - 1;
            b1 += a * (x - b1 - b2 * 0.5); b2 += a * b1;
            o[i] = b2 * Math.Sin(Math.PI * k);
        }
        return Norm(o, vol, true);
    }

    static double[] Norm(double[] o, double peakTo, bool fades)
    {
        double p = 1e-9; foreach (double v in o) p = Math.Max(p, Math.Abs(v));
        for (int i = 0; i < o.Length; i++) o[i] = o[i] / p * peakTo;
        if (fades) { int f = Math.Min(o.Length / 8, 300); for (int i = 0; i < f; i++) o[o.Length - 1 - i] *= i / (double)f; }
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
