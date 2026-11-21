using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;

// Space-fight starships: the original "NX-2 Стриж" fighter (nose towards +Z like the speeder):
// an armoured cockpit pod, struts to swept wings, engine nacelles at the wingtips with twin cannons,
// glowing exhausts; four team colours. Plus engine, laser, hit, explosion, boost and alarm sounds.
public static class FighterArt
{
    const int SR = 44100;

    public static void Generate(string root, string wavDir)
    {
        string tex = Path.Combine(root, "assets/neyrocraft/textures/item");
        string mod = Path.Combine(root, "assets/neyrocraft/models/item");
        string items = Path.Combine(root, "assets/neyrocraft/items");
        Directory.CreateDirectory(tex); Directory.CreateDirectory(mod); Directory.CreateDirectory(items);
        Plate(Path.Combine(tex, "fighter_hull.png"), Color.FromArgb(178, 190, 196), true);
        Plate(Path.Combine(tex, "fighter_dark.png"), Color.FromArgb(46, 48, 58), false);
        Wing(Path.Combine(tex, "fighter_wing.png"));
        Glow(Path.Combine(tex, "fighter_canopy.png"), Color.FromArgb(90, 220, 255));
        Glow(Path.Combine(tex, "fighter_exhaust.png"), Color.FromArgb(120, 200, 255));
        var colors = new Dictionary<string, Color>
        {
            { "red", Color.FromArgb(230, 60, 50) }, { "blue", Color.FromArgb(60, 150, 255) },
            { "green", Color.FromArgb(70, 220, 110) }, { "gold", Color.FromArgb(255, 190, 50) }
        };
        foreach (var kv in colors)
        {
            Glow(Path.Combine(tex, "fighter_mark_" + kv.Key + ".png"), kv.Value);
            File.WriteAllText(Path.Combine(mod, "fighter_" + kv.Key + ".json"), Model(kv.Key), new UTF8Encoding(false));
            File.WriteAllText(Path.Combine(items, "fighter_" + kv.Key + ".json"),
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"neyrocraft:item/fighter_" + kv.Key + "\"}}", new UTF8Encoding(false));
        }
        Directory.CreateDirectory(wavDir);
        Write(wavDir, "fighter_engine", Engine());
        Write(wavDir, "fighter_laser1", Laser(1.0, 1));
        Write(wavDir, "fighter_laser2", Laser(0.9, 2));
        Write(wavDir, "fighter_hit", Hit());
        Write(wavDir, "fighter_explode", Explode());
        Write(wavDir, "fighter_boost", Boost());
        Write(wavDir, "fighter_alarm", Alarm());
    }

    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    static void Plate(string path, Color c, bool rivets)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb); var r = new Random(c.R);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                int d = r.Next(-7, 7);
                if (x % 8 == 0 || y % 8 == 0) d -= 28;                  // panel seams
                if (rivets && (x % 8 == 2 || x % 8 == 6) && (y % 8 == 2 || y % 8 == 6)) d += 30;
                b.SetPixel(x, y, Color.FromArgb(255, Cl(c.R + d), Cl(c.G + d), Cl(c.B + d)));
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static void Wing(string path)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                bool frame = x == 0 || y == 0 || x == 15 || y == 15 || x == y || x == 15 - y;
                Color c = frame ? Color.FromArgb(150, 160, 168) : ((x + y) % 2 == 0 ? Color.FromArgb(30, 28, 40) : Color.FromArgb(38, 34, 48));
                b.SetPixel(x, y, c);
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static void Glow(string path, Color c)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                double t = Math.Max(0, 1 - Math.Sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5)) / 10.6);
                b.SetPixel(x, y, Color.FromArgb(255, Cl((int)(c.R * 0.7 + 255 * 0.4 * t)), Cl((int)(c.G * 0.7 + 255 * 0.4 * t)), Cl((int)(c.B * 0.7 + 255 * 0.4 * t))));
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static string F(double[] a)
    {
        var inv = System.Globalization.CultureInfo.InvariantCulture; var s = new string[a.Length];
        for (int i = 0; i < a.Length; i++) s[i] = a[i].ToString("0.###", inv);
        return "[" + string.Join(",", s) + "]";
    }

    static string Box(double x1, double y1, double z1, double x2, double y2, double z2, string tex, bool glow)
    {
        var sb = new StringBuilder("{\"from\":" + F(new[] { x1, y1, z1 }) + ",\"to\":" + F(new[] { x2, y2, z2 }));
        if (glow) sb.Append(",\"light_emission\":15");
        sb.Append(",\"faces\":{");
        string[] f = { "north", "south", "east", "west", "up", "down" };
        for (int i = 0; i < f.Length; i++) { if (i > 0) sb.Append(','); sb.Append('"').Append(f[i]).Append("\":{\"uv\":[0,0,16,16],\"texture\":\"#").Append(tex).Append("\"}"); }
        return sb.Append("}}").ToString();
    }

    static string Model(string team)
    {
        var e = new List<string>
        {
            Box(5, 5, 2, 11, 11, 12, "hull", false),            // cockpit pod
            Box(5.5, 5.5, 12, 10.5, 10.5, 14.5, "hull", false), // front ring
            Box(6.5, 6.5, 14.5, 9.5, 9.5, 16, "dark", false),   // nose
            Box(6.8, 6.8, 15.9, 9.2, 9.2, 16.4, "canopy", true),// nose window
            Box(6, 11, 5, 10, 11.8, 11, "canopy", true),        // canopy glass on top
            Box(4.4, 6.5, 4, 5, 9.5, 10, "mark", true),         // side marks
            Box(11, 6.5, 4, 11.6, 9.5, 10, "mark", true),
            Box(-4, 7.2, 5, 5, 8.8, 8, "dark", false),          // left strut
            Box(11, 7.2, 5, 20, 8.8, 8, "dark", false),         // right strut
            Box(-12, 7.6, -1, -4, 8.4, 11, "wing", false),      // left wing
            Box(20, 7.6, -1, 28, 8.4, 11, "wing", false),       // right wing
            Box(-11, 8.4, 2, -5, 8.7, 8, "mark", true),         // wing stripes
            Box(21, 8.4, 2, 27, 8.7, 8, "mark", true),
            Box(-14.5, 6.4, -2, -11.5, 9.6, 12, "hull", false), // left nacelle
            Box(27.5, 6.4, -2, 30.5, 9.6, 12, "hull", false),   // right nacelle
            Box(-14, 6.9, -2.6, -12, 9.1, -2, "exhaust", true), // nacelle exhausts
            Box(28, 6.9, -2.6, 30, 9.1, -2, "exhaust", true),
            Box(-13.4, 7.5, 12, -12.6, 8.5, 18, "dark", false), // cannons
            Box(28.6, 7.5, 12, 29.4, 8.5, 18, "dark", false),
            Box(-13.5, 7.4, 17.6, -12.5, 8.6, 18.2, "mark", true),
            Box(28.5, 7.4, 17.6, 29.5, 8.6, 18.2, "mark", true),
            Box(6.5, 6.5, 0, 9.5, 9.5, 2, "dark", false),       // rear engine
            Box(7, 7, -0.6, 9, 9, 0, "exhaust", true),
            Box(7.6, 11.8, 1, 8.4, 14, 5, "hull", false),       // tail fin
        };
        return "{\"textures\":{\"hull\":\"neyrocraft:item/fighter_hull\",\"dark\":\"neyrocraft:item/fighter_dark\","
            + "\"wing\":\"neyrocraft:item/fighter_wing\",\"canopy\":\"neyrocraft:item/fighter_canopy\","
            + "\"exhaust\":\"neyrocraft:item/fighter_exhaust\",\"mark\":\"neyrocraft:item/fighter_mark_" + team + "\","
            + "\"particle\":\"neyrocraft:item/fighter_hull\"},"
            + "\"elements\":[" + string.Join(",", e) + "],"
            + "\"display\":{\"gui\":{\"rotation\":[25,135,0],\"scale\":[0.36,0.36,0.36]},"
            + "\"ground\":{\"translation\":[0,2,0],\"scale\":[0.25,0.25,0.25]},"
            + "\"fixed\":{\"rotation\":[0,180,0],\"scale\":[0.5,0.5,0.5]}}}";
    }

    // ------------------------------------------------------------ sounds

    /** Engine grain (Hann window, overlapped by the plugin): a deep thrum and the rush of the thrusters. */
    static double[] Engine()
    {
        double len = 1.2; int n = (int)(len * SR); var o = new double[n]; var r = new Random(3); double b1 = 0, b2 = 0, lp = 0, ph = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double hum = Math.Sin(2 * Math.PI * 48 * t) * 0.7 + Math.Sin(2 * Math.PI * 96 * t + 1) * 0.35 + Math.Sin(2 * Math.PI * 144 * t) * 0.12;
            hum *= 0.85 + 0.15 * Math.Sin(2 * Math.PI * 9 * t);
            ph += 620 * (1 + 0.01 * Math.Sin(2 * Math.PI * 3 * t)) / SR;
            double whine = Math.Sin(2 * Math.PI * ph) * 0.08 + Math.Sin(2 * Math.PI * ph * 1.5) * 0.05;
            double x = r.NextDouble() * 2 - 1, a = 2 * Math.PI * 300 / SR;
            b1 += a * (x - b1 - b2 * 0.8); b2 += a * b1;
            lp += 0.015 * (x - lp);
            double w = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (n - 1));
            o[i] = (hum + whine + b2 * 0.6 + lp * 2.5) * w;
        }
        return Norm(o, 0.85, false);
    }

    static double[] Laser(double pitch, int seed)
    {
        double len = 0.36; int n = (int)(len * SR); var o = new double[n]; var r = new Random(seed); double ph = 0, ph2 = 0, lp = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double f = (2600 * Math.Exp(-t * 11) + 260) * pitch;
            ph += f / SR; ph2 += f * 0.503 / SR;
            double x = Math.Sin(2 * Math.PI * ph) * 0.55 + Math.Sin(2 * Math.PI * ph2) * 0.35 + ((ph % 1) < 0.5 ? 0.12 : -0.12);
            double nz = r.NextDouble() * 2 - 1; lp += 0.4 * (nz - lp);
            x += lp * 0.6 * Math.Exp(-t * 50);
            o[i] = x * Math.Min(1, t / 0.002) * Math.Exp(-t * 7.5);
        }
        return Norm(o, 0.9, true);
    }

    static double[] Hit()
    {
        double len = 0.45; int n = (int)(len * SR); var o = new double[n]; var r = new Random(5); double ph = 0, hp = 0, prev = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double nz = r.NextDouble() * 2 - 1; hp = 0.75 * (hp + nz - prev); prev = nz;
            ph += (900 + 400 * Math.Sin(t * 140)) / SR;
            o[i] = (hp * 0.45 + Math.Sin(2 * Math.PI * ph) * 0.45 + Math.Sin(2 * Math.PI * 2300 * t) * 0.15 * Math.Exp(-t * 20)) * Math.Exp(-t * 7);
        }
        return Norm(o, 0.85, true);
    }

    static double[] Explode()
    {
        double len = 2.4; int n = (int)(len * SR); var o = new double[n]; var r = new Random(7); double lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double nz = r.NextDouble() * 2 - 1;
            lp += (0.02 + 0.2 * Math.Exp(-t * 6)) * (nz - lp);
            lp2 += 0.004 * (nz - lp2);
            double boom = Math.Sin(2 * Math.PI * (30 + 60 * Math.Exp(-t * 5)) * t) * Math.Exp(-t * 2.2);
            double crackle = (r.NextDouble() < 0.004 * Math.Exp(-t * 1.5) ? (r.NextDouble() * 2 - 1) * 2 : 0);
            o[i] = (lp * 1.4 + lp2 * 8 + boom * 0.9 + crackle) * Math.Min(1, t / 0.004) * Math.Exp(-t * 1.3);
        }
        return Norm(o, 0.95, true);
    }

    static double[] Boost()
    {
        double len = 1.0; int n = (int)(len * SR); var o = new double[n]; var r = new Random(9); double b1 = 0, b2 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double a = 2 * Math.PI * (250 + 2600 * k) / SR;
            double x = r.NextDouble() * 2 - 1;
            b1 += a * (x - b1 - b2 * 0.5); b2 += a * b1;
            o[i] = (b2 + Math.Sin(2 * Math.PI * (80 + 200 * k) * t) * 0.3) * Math.Min(1, t / 0.05) * (1 - k);
        }
        return Norm(o, 0.85, true);
    }

    static double[] Alarm()
    {
        double len = 0.8; int n = (int)(len * SR); var o = new double[n];
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            double f = t < 0.4 ? 880 : 660;
            double env = (t % 0.4) < 0.3 ? 1 : 0;
            o[i] = (Math.Sin(2 * Math.PI * f * t) * 0.6 + Math.Sin(2 * Math.PI * f * 2 * t) * 0.2) * env;
        }
        return Norm(o, 0.6, true);
    }

    static double[] Norm(double[] o, double peak, bool fade)
    {
        double p = 1e-9; foreach (double v in o) p = Math.Max(p, Math.Abs(v));
        for (int i = 0; i < o.Length; i++) o[i] = o[i] / p * peak;
        if (fade) { int f = 300; for (int i = 0; i < f && i < o.Length; i++) o[o.Length - 1 - i] *= i / (double)f; }
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
