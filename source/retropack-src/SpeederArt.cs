using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;

// Hover-bike ("спидер") for the planet races: an original 3D item model (nose towards +Z),
// metal hull, dark seat, two front engine pods and glowing thrusters; plus engine/boost sounds.
public static class SpeederArt
{
    const int SR = 44100;

    public static void Generate(string root, string wavDir)
    {
        string tex = Path.Combine(root, "assets/neyrocraft/textures/item");
        string mod = Path.Combine(root, "assets/neyrocraft/models/item");
        string items = Path.Combine(root, "assets/neyrocraft/items");
        Directory.CreateDirectory(tex); Directory.CreateDirectory(mod); Directory.CreateDirectory(items);
        Metal(Path.Combine(tex, "speeder_metal.png"));
        Flat(Path.Combine(tex, "speeder_dark.png"), Color.FromArgb(255, 40, 42, 52), 10);
        Glow(Path.Combine(tex, "speeder_glow.png"), Color.FromArgb(255, 255, 150, 40));
        Glow(Path.Combine(tex, "speeder_intake.png"), Color.FromArgb(255, 90, 210, 255));
        File.WriteAllText(Path.Combine(mod, "speeder.json"), Model(), new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(items, "speeder.json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"neyrocraft:item/speeder\"}}", new UTF8Encoding(false));
        Directory.CreateDirectory(wavDir);
        Write(wavDir, "speeder_engine", Engine());
        Write(wavDir, "speeder_boost", Boost());
    }

    static int Cl(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    static void Metal(string path)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb); var r = new Random(3);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                int v = 170 + r.Next(-10, 10);
                if (y % 8 == 0 || x % 8 == 0) v -= 45;                 // panel seams
                if (y >= 6 && y <= 7) { b.SetPixel(x, y, Color.FromArgb(255, 210, 70, 40)); continue; }   // racing stripe
                b.SetPixel(x, y, Color.FromArgb(255, Cl(v), Cl(v + 2), Cl(v + 8)));
            }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static void Flat(string path, Color c, int amp)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb); var r = new Random(5);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) { int d = r.Next(-amp, amp); b.SetPixel(x, y, Color.FromArgb(255, Cl(c.R + d), Cl(c.G + d), Cl(c.B + d))); }
        b.Save(path, ImageFormat.Png); b.Dispose();
    }

    static void Glow(string path, Color c)
    {
        var b = new Bitmap(16, 16, PixelFormat.Format32bppArgb);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
            {
                double d = Math.Sqrt((x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5)) / 10.6;
                double t = Math.Max(0, 1 - d);
                b.SetPixel(x, y, Color.FromArgb(255, Cl((int)(c.R * 0.6 + 255 * 0.4 * t + c.R * 0.4 * t)), Cl((int)(c.G * 0.6 + 255 * 0.5 * t)), Cl((int)(c.B * 0.6 + 255 * 0.5 * t))));
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

    static string Model()
    {
        var els = new System.Collections.Generic.List<string>
        {
            Box(5.5, 3.5, -6, 10.5, 7.5, 14, "metal", false),        // hull
            Box(6.5, 7.5, -5, 9.5, 9, 3, "dark", false),             // seat
            Box(6, 7.5, 3, 10, 8.5, 9, "metal", false),              // tank
            Box(4, 8.5, 8, 12, 9.2, 9, "dark", false),               // handlebar
            Box(2, 4.5, 9, 4, 6.5, 22, "metal", false),              // left fork
            Box(12, 4.5, 9, 14, 6.5, 22, "metal", false),            // right fork
            Box(1, 3.5, 21, 5, 7.5, 30, "metal", false),             // left engine pod
            Box(11, 3.5, 21, 15, 7.5, 30, "metal", false),           // right engine pod
            Box(1.5, 4, 29.9, 4.5, 7, 30.6, "intake", true),         // left intake glow
            Box(11.5, 4, 29.9, 14.5, 7, 30.6, "intake", true),       // right intake glow
            Box(1.5, 4, 20.4, 4.5, 7, 21.1, "glow", true),           // left pod exhaust
            Box(11.5, 4, 20.4, 14.5, 7, 21.1, "glow", true),         // right pod exhaust
            Box(6, 4, -9, 10, 7, -6, "dark", false),                 // rear engine
            Box(6.5, 4.5, -9.8, 9.5, 6.5, -9, "glow", true),         // rear thruster glow
            Box(1.5, 5.5, -6, 5.5, 6.1, 4, "metal", false),          // left fin
            Box(10.5, 5.5, -6, 14.5, 6.1, 4, "metal", false),        // right fin
        };
        return "{\"textures\":{\"metal\":\"neyrocraft:item/speeder_metal\",\"dark\":\"neyrocraft:item/speeder_dark\","
            + "\"glow\":\"neyrocraft:item/speeder_glow\",\"intake\":\"neyrocraft:item/speeder_intake\",\"particle\":\"neyrocraft:item/speeder_metal\"},"
            + "\"elements\":[" + string.Join(",", els) + "],"
            + "\"display\":{\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.42,0.42,0.42]},"
            + "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,2,0],\"scale\":[0.3,0.3,0.3]},"
            + "\"fixed\":{\"rotation\":[0,180,0],\"scale\":[0.5,0.5,0.5]}}}";
    }

    // ------------------------------------------------------------ sounds

    static double Saw(double ph) { ph -= Math.Floor(ph); return ph * 2 - 1; }

    /**
     * Engine grain (1.2 s, Hann window): the plugin overlaps a new grain every quarter second, so the
     * sound is continuous at any pitch. A deep hover hum with a slow pulse, a smooth turbine whine with
     * vibrato and a soft rush of air — no buzzing saw waves.
     */
    static double[] Engine()
    {
        double len = 1.2; int n = (int)(len * SR); var o = new double[n]; var r = new Random(9);
        double b1 = 0, b2 = 0, lp = 0, ph1 = 0, ph2 = 0, ph3 = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR;
            // hover hum: 62 Hz with its octave, pulsing gently
            double hum = (Math.Sin(2 * Math.PI * 62 * t) * 0.8 + Math.Sin(2 * Math.PI * 124 * t + 0.5) * 0.35)
                       * (0.8 + 0.2 * Math.Sin(2 * Math.PI * 6 * t));
            // turbine whine: two detuned tones with a slow vibrato
            double vib = 1 + 0.012 * Math.Sin(2 * Math.PI * 4.5 * t);
            ph1 += 940 * vib / SR; ph2 += 947 * vib / SR; ph3 += 1410 * vib / SR;
            double whine = Math.Sin(2 * Math.PI * ph1) * 0.18 + Math.Sin(2 * Math.PI * ph2) * 0.14 + Math.Sin(2 * Math.PI * ph3) * 0.07;
            // rush of air: band-passed noise around 450 Hz plus a low rumble
            double x = r.NextDouble() * 2 - 1, a = 2 * Math.PI * 450 / SR;
            b1 += a * (x - b1 - b2 * 0.9); b2 += a * b1;
            lp += 0.02 * (x - lp);
            double air = b2 * 0.55 + lp * 2.2;
            double w = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / (n - 1));    // Hann window
            o[i] = (hum + whine + air) * w;
        }
        return Norm(o, 0.85, false);
    }

    static double[] Boost()
    {
        double len = 0.9; int n = (int)(len * SR); var o = new double[n]; var r = new Random(11); double b1 = 0, b2 = 0, ph = 0;
        for (int i = 0; i < n; i++)
        {
            double t = i / (double)SR, k = t / len;
            double fc = 400 + 3200 * k, a = 2 * Math.PI * fc / SR;
            double x = r.NextDouble() * 2 - 1;
            b1 += a * (x - b1 - b2 * 0.4); b2 += a * b1;
            ph += (120 + 500 * k) / SR;
            double env = Math.Min(1, t / 0.05) * (k > 0.7 ? (1 - k) / 0.3 : 1);
            o[i] = (b2 * 0.9 + Saw(ph) * 0.4) * env;
        }
        return Norm(o, 0.9, true);
    }

    static double[] Norm(double[] o, double peak, bool fade)
    {
        double p = 1e-9; foreach (double v in o) p = Math.Max(p, Math.Abs(v));
        for (int i = 0; i < o.Length; i++) o[i] = o[i] / p * peak;
        if (fade) { int f = 400; for (int i = 0; i < f; i++) o[o.Length - 1 - i] *= i / (double)f; }
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
